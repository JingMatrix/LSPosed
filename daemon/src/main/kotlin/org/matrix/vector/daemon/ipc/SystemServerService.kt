package org.matrix.vector.daemon.ipc

import android.os.Build
import android.os.IBinder
import android.os.IServiceCallback
import android.os.Parcel
import android.os.SystemProperties
import android.util.Log
import org.lsposed.lspd.service.ILSPApplicationService
import org.lsposed.lspd.service.ILSPSystemServerService

private const val TAG = "VectorSystemServer"

class SystemServerService(private val maxRetry: Int, private val proxyServiceName: String) :
    ILSPSystemServerService.Stub(), IBinder.DeathRecipient {

  private var originService: IBinder? = null
  private var requestedRetryCount = -maxRetry

  // Hardcoded transaction code from BridgeService
  private val BRIDGE_TRANSACTION_CODE =
      ('_'.code shl 24) or ('V'.code shl 16) or ('E'.code shl 8) or 'C'.code

  init {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      val callback =
          object : IServiceCallback.Stub() {
            override fun onRegistration(name: String, binder: IBinder?) {
              if (name == proxyServiceName &&
                  binder != null &&
                  binder !== this@SystemServerService) {
                Log.d(TAG, "Intercepted system service registration: $name")
                originService = binder
                runCatching { binder.linkToDeath(this@SystemServerService, 0) }
              }
            }
          }
      runCatching {
            android.os.ServiceManager.getIServiceManager()
                .registerForNotifications(proxyServiceName, callback)
          }
          .onFailure { Log.e(TAG, "Failed to register IServiceCallback", it) }
    }
  }

  fun putBinderForSystemServer() {
    android.os.ServiceManager.addService(proxyServiceName, this)
    binderDied()
  }

  override fun requestApplicationService(
      uid: Int,
      pid: Int,
      processName: String,
      heartBeat: IBinder?
  ): ILSPApplicationService? {
    requestedRetryCount = 1
    if (uid != 1000 || heartBeat == null || processName != "system") return null

    // Return the ApplicationService singleton if successfully registered
    return if (ApplicationService.registerHeartBeat(uid, pid, processName, heartBeat)) {
      ApplicationService
    } else null
  }

  override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
    originService?.let {
      return it.transact(code, data, reply, flags)
    }

    when (code) {
      BRIDGE_TRANSACTION_CODE -> {
        val uid = data.readInt()
        val pid = data.readInt()
        val processName = data.readString()
        val heartBeat = data.readStrongBinder()

        val service = requestApplicationService(uid, pid, processName, heartBeat)
        if (service != null) {
          reply?.writeNoException()
          reply?.writeStrongBinder(service.asBinder())
          return true
        }
        return false
      }
      // Route DEX and OBFUSCATION transactions to ApplicationService
      else -> return ApplicationService.onTransact(code, data, reply, flags)
    }
  }

  override fun binderDied() {
    originService?.unlinkToDeath(this, 0)
    originService = null
  }

  fun maybeRetryInject() {
    if (requestedRetryCount < 0) {
      Log.w(TAG, "System server injection fails, triggering restart...")
      requestedRetryCount++
      val restartTarget =
          if (Build.SUPPORTED_64_BIT_ABIS.isNotEmpty() &&
              Build.SUPPORTED_32_BIT_ABIS.isNotEmpty()) {
            "zygote_secondary"
          } else {
            "zygote"
          }
      SystemProperties.set("ctl.restart", restartTarget)
    }
  }
}
