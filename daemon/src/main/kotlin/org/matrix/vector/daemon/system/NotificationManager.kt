package org.matrix.vector.daemon.system

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import io.github.libxposed.service.IXposedScopeCallback
import java.util.UUID
import org.matrix.vector.daemon.BuildConfig
import org.matrix.vector.daemon.utils.FakeContext

private const val TAG = "VectorNotifManager"
private const val STATUS_CHANNEL_ID = "lsposed_status"
private const val UPDATED_CHANNEL_ID = "lsposed_module_updated"
private const val SCOPE_CHANNEL_ID = "lsposed_module_scope"
private const val STATUS_NOTIF_ID = 2000

object NotificationManager {
  val openManagerAction = UUID.randomUUID().toString()
  val moduleScopeAction = UUID.randomUUID().toString()

  private val nm: android.app.INotificationManager? by
      SystemService(
          Context.NOTIFICATION_SERVICE, android.app.INotificationManager.Stub::asInterface)
  private val opPkg =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "android" else "com.android.settings"

  private fun createChannels() {
    val context = FakeContext()
    val list =
        listOf(
            NotificationChannel(
                    STATUS_CHANNEL_ID,
                    "Vector Status",
                    android.app.NotificationManager.IMPORTANCE_MIN)
                .apply { setShowBadge(false) },
            NotificationChannel(
                    UPDATED_CHANNEL_ID,
                    "Module Updated",
                    android.app.NotificationManager.IMPORTANCE_HIGH)
                .apply { setShowBadge(false) },
            NotificationChannel(
                    SCOPE_CHANNEL_ID,
                    "Scope Request",
                    android.app.NotificationManager.IMPORTANCE_HIGH)
                .apply { setShowBadge(false) })

    runCatching {
          // ParceledListSlice is required for system_server IPC
          nm?.createNotificationChannelsForPackage(
              "android", 1000, android.content.pm.ParceledListSlice(list))
        }
        .onFailure { Log.e(TAG, "Failed to create notification channels", it) }
  }

  fun notifyStatusNotification() {
    val context = FakeContext()
    val intent = Intent(openManagerAction).apply { setPackage("android") }
    val pi =
        PendingIntent.getBroadcast(
            context, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    val notif =
        Notification.Builder(context, STATUS_CHANNEL_ID)
            .setContentTitle("Vector is active")
            .setContentText("The daemon is running.")
            .setSmallIcon(android.R.drawable.ic_dialog_info) // Fallback icon
            .setContentIntent(pi)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .setOngoing(true)
            .build()
            .apply { extras.putString("android.substName", BuildConfig.FRAMEWORK_NAME) }

    createChannels()
    runCatching {
      nm?.enqueueNotificationWithTag("android", opPkg, null, STATUS_NOTIF_ID, notif, 0)
    }
  }

  fun cancelStatusNotification() {
    runCatching {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        nm?.cancelNotificationWithTag("android", "android", null, STATUS_NOTIF_ID, 0)
      } else {
        nm?.cancelNotificationWithTag("android", null, STATUS_NOTIF_ID, 0)
      }
    }
  }

  fun requestModuleScope(
      modulePkg: String,
      moduleUserId: Int,
      scopePkg: String,
      callback: IXposedScopeCallback
  ) {
    val context = FakeContext()
    val intent =
        Intent(moduleScopeAction).apply {
          setPackage("android")
          data =
              Uri.Builder()
                  .scheme("module")
                  .encodedAuthority("$modulePkg:$moduleUserId")
                  .encodedPath(scopePkg)
                  .appendQueryParameter("action", "approve")
                  .build()
          putExtras(Bundle().apply { putBinder("callback", callback.asBinder()) })
        }
    val pi =
        PendingIntent.getBroadcast(
            context, 4, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    val notif =
        Notification.Builder(context, SCOPE_CHANNEL_ID)
            .setContentTitle("Scope Request")
            .setContentText("Module $modulePkg requested injection into $scopePkg.")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .addAction(Notification.Action.Builder(null, "Approve", pi).build())
            .setAutoCancel(true)
            .build()
            .apply { extras.putString("android.substName", BuildConfig.FRAMEWORK_NAME) }

    createChannels()
    runCatching {
      nm?.enqueueNotificationWithTag("android", opPkg, modulePkg, modulePkg.hashCode(), notif, 0)
    }
  }

  fun cancelNotification(channel: String, modulePkg: String, moduleUserId: Int) {
    runCatching {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        nm?.cancelNotificationWithTag("android", "android", modulePkg, modulePkg.hashCode(), 0)
      } else {
        nm?.cancelNotificationWithTag("android", modulePkg, modulePkg.hashCode(), 0)
      }
    }
  }
}
