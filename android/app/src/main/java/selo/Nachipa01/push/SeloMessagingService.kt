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
