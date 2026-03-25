package org.matrix.vector.daemon.data

import android.content.res.AssetManager
import android.content.res.Resources
import android.os.Process
import android.os.SELinux
import android.os.SharedMemory
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import hidden.HiddenApiBridge
import java.io.File
import java.io.InputStream
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.zip.ZipFile
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import org.lsposed.lspd.models.PreLoadedApk
import org.matrix.vector.daemon.utils.ObfuscationManager

private const val TAG = "VectorFileSystem"

object FileSystem {
  val basePath: Path = Paths.get("/data/adb/lspd")
  val modulePath: Path = basePath.resolve("modules")
  val daemonApkPath: Path = Paths.get(System.getProperty("java.class.path", ""))
  val managerApkPath: Path = daemonApkPath.parent.resolve("manager.apk")
  val configDirPath: Path = basePath.resolve("config")
  val dbPath: File = configDirPath.resolve("modules_config.db").toFile()
  val magiskDbPath = File("/data/adb/magisk.db")

  private val lockPath: Path = basePath.resolve("lock")
  private var fileLock: FileLock? = null
  private var lockChannel: FileChannel? = null

  init {
    runCatching {
          Files.createDirectories(basePath)
          SELinux.setFileContext(basePath.toString(), "u:object_r:system_file:s0")
          Files.createDirectories(configDirPath)
        }
        .onFailure { Log.e(TAG, "Failed to initialize directories", it) }
  }

  /** Tries to lock the daemon lockfile. Returns false if another daemon is running. */
  fun tryLock(): Boolean {
    return runCatching {
          val permissions =
              PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
          lockChannel =
              FileChannel.open(
                  lockPath, setOf(StandardOpenOption.CREATE, StandardOpenOption.WRITE), permissions)
          fileLock = lockChannel?.tryLock()
          fileLock?.isValid == true
        }
        .getOrDefault(false)
  }

  /** Clears all special file attributes (like immutable) on a directory. */
  fun chattr0(path: Path): Boolean {
    return runCatching {
          val fd = Os.open(path.toString(), OsConstants.O_RDONLY, 0)
          // 0x40086602 for 64-bit, 0x40046602 for 32-bit (FS_IOC_SETFLAGS)
          val req = if (Process.is64Bit()) 0x40086602 else 0x40046602
          HiddenApiBridge.Os_ioctlInt(fd, req, 0)
          Os.close(fd)
          true
        }
        .recover { e -> if (e is ErrnoException && e.errno == OsConstants.ENOTSUP) true else false }
        .getOrDefault(false)
  }

  /** Recursively sets SELinux context. Crucial for modules to read their data. */
  fun setSelinuxContextRecursive(path: Path, context: String) {
    runCatching {
          SELinux.setFileContext(path.toString(), context)
          if (path.isDirectory()) {
            Files.list(path).use { stream ->
              stream.forEach { setSelinuxContextRecursive(it, context) }
            }
          }
        }
        .onFailure { Log.e(TAG, "Failed to set SELinux context for $path", it) }
  }

  /**
   * Lazily loads resources from the daemon's APK path via reflection. This allows FakeContext to
   * access strings/drawables without a real application context.
   */
  val resources: Resources by lazy {
    val am = AssetManager::class.java.newInstance()
    val addAssetPath =
        AssetManager::class.java.getDeclaredMethod("addAssetPath", String::class.java).apply {
          isAccessible = true
        }
    addAssetPath.invoke(am, daemonApkPath.toString())
    @Suppress("DEPRECATION") Resources(am, null, null)
  }

  /** Loads a single DEX file into SharedMemory, optionally applying obfuscation. */
  private fun readDex(inputStream: InputStream, obfuscate: Boolean): SharedMemory {
    var memory = SharedMemory.create(null, inputStream.available())
    val byteBuffer = memory.mapReadWrite()
    Channels.newChannel(inputStream).read(byteBuffer)
    SharedMemory.unmap(byteBuffer)

    if (obfuscate) {
      val newMemory = ObfuscationManager.obfuscateDex(memory)
      if (memory !== newMemory) {
        memory.close()
        memory = newMemory
      }
    }
    memory.setProtect(OsConstants.PROT_READ)
    return memory
  }

  /** Parses the module APK, extracts init lists, and loads DEXes into SharedMemory. */
  fun loadModule(apkPath: String, obfuscate: Boolean): PreLoadedApk? {
    val file = File(apkPath)
    if (!file.exists()) return null

    val preLoadedApk = PreLoadedApk()
    val preLoadedDexes = mutableListOf<SharedMemory>()
    val moduleClassNames = mutableListOf<String>()
    val moduleLibraryNames = mutableListOf<String>()
    var isLegacy = false

    runCatching {
          ZipFile(file).use { zip ->
            // 1. Read all classes*.dex files
            var secondary = 1
            while (true) {
              val entryName = if (secondary == 1) "classes.dex" else "classes$secondary.dex"
              val dexEntry = zip.getEntry(entryName) ?: break
              zip.getInputStream(dexEntry).use { preLoadedDexes.add(readDex(it, obfuscate)) }
              secondary++
            }

            // 2. Read initialization lists
            fun readList(name: String, dest: MutableList<String>) {
              zip.getEntry(name)?.let { entry ->
                zip.getInputStream(entry).bufferedReader().useLines { lines ->
                  lines
                      .map { it.trim() }
                      .filter { it.isNotEmpty() && !it.startsWith("#") }
                      .forEach { dest.add(it) }
                }
              }
            }

            readList("META-INF/xposed/java_init.list", moduleClassNames)
            if (moduleClassNames.isEmpty()) {
              isLegacy = true
              readList("assets/xposed_init", moduleClassNames)
              readList("assets/native_init", moduleLibraryNames)
            } else {
              readList("META-INF/xposed/native_init.list", moduleLibraryNames)
            }
          }
        }
        .onFailure {
          Log.e(TAG, "Failed to load module $apkPath", it)
          return null
        }

    if (preLoadedDexes.isEmpty() || moduleClassNames.isEmpty()) return null

    // 3. Apply obfuscation to class names if required
    if (obfuscate) {
      // TODO
      val signatures = ObfuscationManager.getSignatures()
      for (i in moduleClassNames.indices) {
        val s = moduleClassNames[i]
        signatures.entries
            .firstOrNull { s.startsWith(it.key) }
            ?.let { moduleClassNames[i] = s.replace(it.key, it.value) }
      }
    }

    preLoadedApk.preLoadedDexes = preLoadedDexes
    preLoadedApk.moduleClassNames = moduleClassNames
    preLoadedApk.moduleLibraryNames = moduleLibraryNames
    preLoadedApk.legacy = isLegacy

    return preLoadedApk
  }
}
