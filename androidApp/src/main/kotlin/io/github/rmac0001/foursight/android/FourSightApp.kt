package io.github.rmac0001.foursight.android

import android.app.Application
import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Holds the one [AndroidAppServices] and schedules the background sync. */
class FourSightApp : Application() {
    val services by lazy { AndroidAppServices(this) }

    override fun onCreate() {
        super.onCreate()
        // Twice a day, on any network: picks up the PC's daily collection and the hourly day-7 runs.
        // Doze may delay it, which is fine for daily numbers (spec: Architecture, "Daily collection").
        val request =
            PeriodicWorkRequestBuilder<SyncWorker>(12, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("drive-sync", ExistingPeriodicWorkPolicy.KEEP, request)
    }
}

/** Merges the other devices' Drive files into this phone's database, without any screens. */
class SyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val services = (applicationContext as FourSightApp).services
        return if (services.backgroundSync()) Result.success() else Result.retry()
    }
}
