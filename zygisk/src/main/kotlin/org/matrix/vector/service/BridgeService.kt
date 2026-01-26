package org.matrix.vector.service

import android.app.ActivityThread
import android.os.Binder
import android.os.IBinder
import android.os.IBinder.DeathRecipient
import android.os.Parcel
import android.os.Process
import hidden.HiddenApiBridge.Binder_allowBlocking
import hidden.HiddenApiBridge.Context_getActivityToken
import org.lsposed.lspd.util.Utils.Log

/**
 * Manages manual Binder transactions for the Vector framework.
 *
 * This service is not registered in ServiceManager. Instead, the Zygisk native module intercepts
 * [Binder.execTransact] and redirects calls with the [TRANSACTION_CODE] to this class.
 */
object BridgeService {
    private const val TRANSACTION_CODE =
        ('_'.toInt() shl 24) or ('L'.toInt() shl 16) or ('S'.toInt() shl 8) or 'P'.toInt()
    private const val DESCRIPTOR = "LSPosed"
    private const val TAG = "LSPosed-Bridge"

    /** Actions supported by the manual IPC bridge. */
    private enum class Action {
        UNKNOWN,
        SEND_BINDER, // Daemon sending the system service binder
        GET_BINDER, // Process requesting its specific application service
        ENABLE_MANAGER, // Toggle manager state
    }

    @Volatile private var serviceBinder: IBinder? = null

    @Volatile private var service: ILSPosedService? = null

    /** Cleans up service references if the remote LSPosed process crashes. */
    private val serviceRecipient = DeathRecipient {
        Log.e(TAG, "LSPosed system service died.")
        serviceBinder?.unlinkToDeath(this, 0)
        serviceBinder = null
        service = null
    }

    /** Returns the active LSPosed system service interface. */
    @JvmStatic fun getService(): ILSPosedService? = service

    /**
     * Initializes the client-side connection to the LSPosed system service.
     *
     * @param binder The raw binder for [ILSPosedService].
     */
    private fun receiveFromBridge(binder: IBinder?) {
        if (binder == null) {
            Log.e(TAG, "Received null binder from bridge.")
            return
        }

        // Cleanup old death recipient if we are re-initializing
        val token = Binder.clearCallingIdentity()
        try {
            serviceBinder?.unlinkToDeath(serviceRecipient, 0)
        } finally {
            Binder.restoreCallingIdentity(token)
        }

        // Allow blocking calls since we are often in a synchronous fork path
        val blockingBinder = Binder_allowBlocking(binder)
        serviceBinder = blockingBinder
        service = ILSPosedService.Stub.asInterface(blockingBinder)

        runCatching { blockingBinder.linkToDeath(serviceRecipient, 0) }
            .onFailure { Log.e(TAG, "Failed to link to service death", it) }

        // Provide the system context to the service so it can manage system-wide states
        runCatching {
                val activityThread = ActivityThread.currentActivityThread()
                val atBinder = activityThread.applicationThread.asBinder()
                val systemCtx = activityThread.systemContext
                service?.dispatchSystemServerContext(
                    atBinder,
                    Context_getActivityToken(systemCtx),
                    "Zygisk",
                )
            }
            .onFailure { Log.e(TAG, "Failed to dispatch system context", it) }

        Log.i(TAG, "LSPosed system service binder linked.")
    }

    /** Handles manual parcel transactions. Called via reflection/JNI from the native hook. */
    @JvmStatic
    fun onTransact(data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (!data.enforceInterface(DESCRIPTOR)) return false

        return try {
            val actionIdx = data.readInt()
            val action = Action.values().getOrElse(actionIdx) { Action.UNKNOWN }

            Log.d(TAG, "onTransact: action=$action, callerUid=${Binder.getCallingUid()}")

            when (action) {
                Action.SEND_BINDER -> {
                    // Only allow root (UID 0) to push the initial service binder
                    if (Binder.getCallingUid() == 0) {
                        receiveFromBridge(data.readStrongBinder())
                        reply?.writeNoException()
                        true
                    } else false
                }

                Action.GET_BINDER -> {
                    val processName = data.readString()
                    val heartBeat = data.readStrongBinder()
                    val appService =
                        service?.requestApplicationService(
                            Binder.getCallingUid(),
                            Binder.getCallingPid(),
                            processName,
                            heartBeat,
                        )

                    if (appService != null && reply != null) {
                        reply.writeNoException()
                        reply.writeStrongBinder(appService.asBinder())
                        true
                    } else false
                }

                Action.ENABLE_MANAGER -> {
                    val uid = Binder.getCallingUid()
                    // Restricted to Root, System, or Shell
                    if (
                        (uid == 0 || uid == Process.SHELL_UID || uid == Process.SYSTEM_UID) &&
                            service != null
                    ) {
                        val enabled = data.readInt() == 1
                        val result = service?.setManagerEnabled(enabled) ?: false
                        reply?.writeInt(if (result) 1 else 0)
                        true
                    } else false
                }

                else -> false
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error handling bridge transaction", e)
            false
        }
    }

    /**
     * Entry point for the JNI hook in [IPCBridge.cpp].
     *
     * @param obj The Binder object being called.
     * @param code The transaction code.
     * @param dataObj Native pointer to the data Parcel.
     * @param replyObj Native pointer to the reply Parcel.
     * @param flags Transaction flags.
     * @return True if the transaction was handled.
     */
    @JvmStatic
    fun execTransact(obj: IBinder, code: Int, dataObj: Long, replyObj: Long, flags: Int): Boolean {
        if (code != TRANSACTION_CODE) return false

        val data = dataObj.asParcel()
        val reply = replyObj.asParcel()

        if (data == null || reply == null) {
            Log.w(TAG, "Transaction dropped: null parcel pointers.")
            return false
        }

        return try {
            onTransact(data, reply, flags)
        } catch (e: Exception) {
            if (flags and IBinder.FLAG_ONEWAY == 0) {
                reply.setDataPosition(0)
                reply.writeException(e)
            }
            Log.e(TAG, "Exception during execTransact", e)
            true // We handled it, even if by returning an exception
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
}
