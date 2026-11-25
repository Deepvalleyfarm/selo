package selo.Nachipa01.push

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import selo.Nachipa01.BuildConfig
import selo.Nachipa01.MainActivity
import selo.Nachipa01.R

/**
 * Receives order, dispatch and payout pushes, plus silent `config-updates` topic messages
 * sent when an admin saves platform configuration in the back office.
 */
class SeloMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        FirebaseFunctions.getInstance(BuildConfig.FUNCTIONS_REGION).getHttpsCallable("user_registerFcmToken")
            .call(mapOf("token" to token))
        FirebaseMessaging.getInstance().subscribeToTopic("config-updates")
    }

    override fun onMessageReceived(message: RemoteMessage) {
        // Delivery code for this phone's owner (the receiver): keep it locally, acknowledge so the server skips the SMS fallback.
        if (message.data["type"] == "DELIVERY_CODE") {
            val kind = message.data["kind"] ?: "order"; val id = message.data["orderId"] ?: return; val code = message.data["code"] ?: return
            DeliveryCodeStore.init(this)
            DeliveryCodeStore.put(selo.Nachipa01.data.model.ReceivedCode(kind, id, code, message.data["expiresAt"]?.toLongOrNull() ?: (System.currentTimeMillis() + 15 * 60_000), System.currentTimeMillis()))
            FirebaseFunctions.getInstance(BuildConfig.FUNCTIONS_REGION).getHttpsCallable("deliveryCode_ack").call(mapOf("kind" to kind, "orderId" to id))
            val intent = Intent(this, MainActivity::class.java).apply { putExtra("deepLink", "selo://code/$kind/$id"); flags = Intent.FLAG_ACTIVITY_CLEAR_TOP }
            val pi = PendingIntent.getActivity(this, id.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            // The notification itself does not repeat the digits on the lock screen; tapping opens the protected code screen.
            val notif = NotificationCompat.Builder(this, "delivery_code").setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("Your delivery code is ready").setContentText("Your rider has arrived. Tap to see the code — share it only when the item is in your hands.")
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setAutoCancel(true).setContentIntent(pi).setPriority(NotificationCompat.PRIORITY_MAX).build()
            getSystemService(NotificationManager::class.java).notify(("code_" + id).hashCode(), notif)
            return
        }
        // Data-only config pushes just wake the process; Firestore listeners pick up the change.
        val n = message.notification ?: return
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("deepLink", message.data["deepLink"]); flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notif = NotificationCompat.Builder(this, "orders")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(n.title).setContentText(n.body).setAutoCancel(true).setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH).build()
        getSystemService(NotificationManager::class.java).notify(message.messageId.hashCode(), notif)
    }
}
