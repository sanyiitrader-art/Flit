package app.flit

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Process entry point. Initialises the small singletons and the notification channels.
 * Nothing heavy happens here: the database opens lazily on its own thread.
 */
class FlitApp : Application() {

    override fun onCreate() {
        super.onCreate()
        app = this
        Prefs.init(this)
        Db.init(this)
        createChannels()
    }

    companion object {
        const val CH_REQUESTS = "flit_requests"
        const val CH_PROGRESS = "flit_progress"
        const val CH_RESULTS = "flit_results"

        lateinit var app: FlitApp
            private set

        /** Main-thread handler shared by the whole app. */
        val main: Handler = Handler(Looper.getMainLooper())

        /** General background pool (thumbnails, directory listing). Created on first use. */
        val io: ExecutorService by lazy { Executors.newFixedThreadPool(2) }

        private var localized: Context? = null
        private var localizedKey = ""

        /**
         * A context that follows the in-app language and theme choice.
         * Used by services and notifications, which have no Activity context.
         */
        fun ctx(): Context {
            val key = Prefs.lang + "/" + Prefs.theme
            var c = localized
            if (c == null || localizedKey != key) {
                c = Ui.wrap(app)
                localized = c
                localizedKey = key
            }
            return c
        }

        fun str(id: Int, vararg args: Any): String = ctx().getString(id, *args)

        /** Call after the language or theme preference changed. */
        fun invalidateLocale() {
            localized = null
            createChannels()
        }

        fun createChannels() {
            val c = ctx()
            val nm = app.getSystemService(NotificationManager::class.java) ?: return

            val requests = NotificationChannel(
                CH_REQUESTS, c.getString(R.string.ch_requests), NotificationManager.IMPORTANCE_HIGH
            )
            val progress = NotificationChannel(
                CH_PROGRESS, c.getString(R.string.ch_progress), NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            val results = NotificationChannel(
                CH_RESULTS, c.getString(R.string.ch_results), NotificationManager.IMPORTANCE_DEFAULT
            )
            nm.createNotificationChannels(listOf(requests, progress, results))
        }
    }
}