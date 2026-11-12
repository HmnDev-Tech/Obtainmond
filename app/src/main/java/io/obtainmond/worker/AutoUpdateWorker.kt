package io.obtainmond.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.obtainmond.data.repo.AppRepository
import io.obtainmond.privilege.PrivilegeManager
import java.util.concurrent.TimeUnit

class AutoUpdateWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        PrivilegeManager.init(applicationContext)
        AppRepository.init(applicationContext)

        val settings = AppRepository.settings.value
        if (settings.autoWhitelistBatteryDoze) {
            PrivilegeManager.enforceZeroBatteryRestriction(applicationContext, settings)
        }

        if (!settings.zeroTouchBackgroundUpdate) {
            return Result.success()
        }

        return runCatching {
            AppRepository.refreshAllAndAutoUpdate(
                autoInstallUpdates = true,
                isBackgroundWorker = true
            )
            Result.success()
        }.getOrElse {
            Result.retry()
        }
    }

    companion object {
        private const val PERIODIC_WORK_NAME = "obtainmond_zero_touch_periodic_updater"
        private const val IMMEDIATE_WORK_NAME = "obtainmond_zero_touch_immediate_updater"

        fun schedulePeriodic(context: Context, intervalMinutes: Int = 30) {
            val coercedMinutes = intervalMinutes.coerceAtLeast(15).toLong()
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<AutoUpdateWorker>(
                coercedMinutes,
                TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        fun triggerImmediateBackgroundCheck(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<AutoUpdateWorker>()
                .setConstraints(constraints)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                IMMEDIATE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }
}
