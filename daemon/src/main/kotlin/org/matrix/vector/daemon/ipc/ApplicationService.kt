package org.matrix.vector.daemon.ipc

import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.RemoteException
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import org.lsposed.lspd.models.Module
import org.lsposed.lspd.service.ILSPApplicationService
import org.matrix.vector.daemon.data.ConfigCache
import org.matrix.vector.daemon.data.FileSystem
import org.matrix.vector.daemon.data.ProcessScope

private const val TAG = "VectorAppService"
private const val DEX_TRANSACTION_CODE =
    ('_'.code shl 24) or ('D'.code shl 16) or ('E'.code shl 8) or 'X'.code
private const val OBFUSCATION_MAP_TRANSACTION_CODE =
    ('_'.code shl 24) or ('O'.code shl 16) or ('B'.code shl 8) or 'F'.code

object ApplicationService : ILSPApplicationService.Stub() {

  // Tracks active processes linked to their heartbeat binders
  private val processes = ConcurrentHashMap<ProcessScope, ProcessInfo>()

  private class ProcessInfo(val scope: ProcessScope, val heartBeat: IBinder) :
      IBinder.DeathRecipient {
    init {
      heartBeat.linkToDeath(this, 0)
      processes[scope] = this
    }

    override fun binderDied() {
      heartBeat.unlinkToDeath(this, 0)
      processes.remove(scope)
    }
  }

  override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
    when (code) {
      DEX_TRANSACTION_CODE -> {
        val shm = FileSystem.getPreloadDex(ConfigCache.isDexObfuscateEnabled()) ?: return false
        reply?.writeNoException()
        reply?.let { shm.writeToParcel(it, 0) }
        reply?.writeLong(shm.size.toLong())
        return true
      }
      OBFUSCATION_MAP_TRANSACTION_CODE -> {
        val obfuscation = ConfigCache.isDexObfuscateEnabled()
        val signatures = org.matrix.vector.daemon.utils.ObfuscationManager.getSignatures()
        reply?.writeNoException()
        reply?.writeInt(signatures.size * 2)
        for ((key, value) in signatures) {
          reply?.writeString(key)
          reply?.writeString(if (obfuscation) value else key)
        }
        return true
      }
    }
    return super.onTransact(code, data, reply, flags)
  }

  fun registerHeartBeat(uid: Int, pid: Int, processName: String, heartBeat: IBinder): Boolean {
    return runCatching {
          ProcessInfo(ProcessScope(processName, uid), heartBeat)
          true
        }
        .getOrDefault(false)
  }

  fun hasRegister(uid: Int, pid: Int): Boolean {
    // We only check UID here as the map key is ProcessScope, but PID is implied by the active
    // heartbeat.
    return processes.keys.any { it.uid == uid }
  }

  private fun ensureRegistered(): ProcessScope {
    val uid = getCallingUid()
    val scope = processes.keys.firstOrNull { it.uid == uid }
    if (scope == null) {
      Log.w(TAG, "Unauthorized IPC call from uid=$uid")
      throw RemoteException("Not registered")
    }
    return scope
  }

  override fun getModulesList(): List<Module> {
    val scope = ensureRegistered()
    if (scope.uid == Process.SYSTEM_UID && scope.processName == "system") {
      return ConfigCache.getModulesForSystemServer() // Needs implementation in ConfigCache
    }
    if (ManagerService.isRunningManager(getCallingPid(), scope.uid)) {
      return emptyList()
    }
    return ConfigCache.getModulesForProcess(scope.processName, scope.uid).filter { !it.file.legacy }
  }

  override fun getLegacyModulesList(): List<Module> {
    val scope = ensureRegistered()
    return ConfigCache.getModulesForProcess(scope.processName, scope.uid).filter { it.file.legacy }
  }

  override fun isLogMuted(): Boolean = !ManagerService.isVerboseLog

  override fun getPrefsPath(packageName: String): String {
    val scope = ensureRegistered()
    return ConfigCache.getPrefsPath(packageName, scope.uid) // Needs implementation in ConfigCache
  }

  override fun requestInjectedManagerBinder(
      binderList: MutableList<IBinder>
  ): ParcelFileDescriptor? {
    val scope = ensureRegistered()
    val pid = getCallingPid()

    if (ManagerService.postStartManager(pid, scope.uid) || ConfigCache.isManager(scope.uid)) {
      val heartBeat = processes[scope]?.heartBeat ?: throw RemoteException("No heartbeat")
      binderList.add(ManagerService.obtainManagerBinder(heartBeat, pid, scope.uid))
    }

    return runCatching {
          ParcelFileDescriptor.open(
              FileSystem.managerApkPath.toFile(), ParcelFileDescriptor.MODE_READ_ONLY)
        }
        .onFailure { Log.e(TAG, "Failed to open manager APK", it) }
        .getOrNull()
  }
}
