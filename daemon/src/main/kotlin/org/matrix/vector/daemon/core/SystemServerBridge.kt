package org.matrix.vector.daemon.core

import android.os.IBinder
import android.os.Parcel
import android.system.Os
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.matrix.vector.daemon.BuildConfig
import org.matrix.vector.daemon.ipc.ManagerService
import org.matrix.vector.daemon.ipc.SystemServerService

private const val TAG = "VectorBridge"
private const val TRANSACTION_CODE =
    ('_'.code shl 24) or ('V'.code shl 16) or ('E'.code shl 8) or 'C'.code

object SystemServerBridge {

    @Suppress("DEPRECATION")
  fun sendToBridge(binder: IBinder, isRestart: Boolean, systemServerService: SystemServerService) {
    CoroutineScope(Dispatchers.IO).launch {
      runCatching {
            Os.seteuid(0)

            var bridgeService: IBinder?
            while (true) {
              bridgeService = android.os.ServiceManager.getService("activity")
              if (bridgeService?.pingBinder() == true) break
              Log.i(TAG, "activity service not ready, waiting 1s...")
              delay(1000)
            }

            if (isRestart) Log.w(TAG, "System Server restarted...")

            // Setup death recipient to handle system_server crashes
            val deathRecipient =
                object : IBinder.DeathRecipient {
                  override fun binderDied() {
                    Log.w(TAG, "System Server died! Clearing caches and re-injecting...")
                    bridgeService.unlinkToDeath(this, 0)
                    systemServerService.putBinderForSystemServer()
                    ManagerService.guard = null // ManagerGuard binderDied
                    sendToBridge(binder, isRestart = true, systemServerService)
                  }
                }
            bridgeService.linkToDeath(deathRecipient, 0)

            // Try sending the Binder payload (up to 3 times)
            var success = false
            for (i in 0 until 3) {
              val data = Parcel.obtain()
              val reply = Parcel.obtain()
              try {
                data.writeInt(1) // ACTION_SEND_BINDER
                data.writeStrongBinder(binder)
                success = bridgeService.transact(TRANSACTION_CODE, data, reply, 0) == true
                reply.readException()
                if (success) break
              } finally {
                data.recycle()
                reply.recycle()
              }
              Log.w(TAG, "No response from bridge, retrying...")
              delay(1000)
            }

            if (success) Log.i(TAG, "Successfully injected Vector into system_server")
            else {
              Log.e(TAG, "Failed to inject Vector into system_server")
              systemServerService.maybeRetryInject()
            }
          }
          .onFailure { Log.e(TAG, "Error during System Server bridging", it) }
          .also { if (!BuildConfig.DEBUG) runCatching { Os.seteuid(1000) } }
    }
  }
}
