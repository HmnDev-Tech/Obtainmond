package io.obtainmond.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.obtainmond.data.repo.AppRepository
import io.obtainmond.privilege.PrivilegeManager

class BootAndPackageReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            PrivilegeManager.init(context)
            AppRepository.init(context)
            val settings = AppRepository.settings.value
            AutoUpdateWorker.schedulePeriodic(context, settings.backgroundIntervalMinutes)
            if (settings.zeroTouchBackgroundUpdate) {
                AutoUpdateWorker.triggerImmediateBackgroundCheck(context)
            }
        }
    }
}
