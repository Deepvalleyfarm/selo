package selo.Nachipa01

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import selo.Nachipa01.data.repo.Backend
import selo.Nachipa01.data.repo.DemoBackend
import selo.Nachipa01.data.repo.FirebaseBackend

class SeloApp : Application() {
    lateinit var backend: Backend
        private set

    override fun onCreate() {
        super.onCreate()
        val app = if (BuildConfig.HAS_FIREBASE_CONFIG) FirebaseApp.initializeApp(this) else null
        backend = if (app != null) {
            FirebaseAppCheck.getInstance().installAppCheckProviderFactory(PlayIntegrityAppCheckProviderFactory.getInstance())
            FirebaseBackend(app)
        } else DemoBackend()

        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("orders", getString(R.string.channel_orders), NotificationManager.IMPORTANCE_HIGH)
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("delivery_code", "Delivery codes", NotificationManager.IMPORTANCE_HIGH).apply { lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE }
        )
        selo.Nachipa01.push.DeliveryCodeStore.init(this)
        org.osmdroid.config.Configuration.getInstance().apply { userAgentValue = "SeloNaChipa/${BuildConfig.VERSION_NAME}"; osmdroidBasePath = cacheDir; osmdroidTileCache = java.io.File(cacheDir, "tiles") }
    }
}
