package io.obtainmond

import android.app.Application
import io.obtainmond.data.repo.AppRepository
import io.obtainmond.privilege.PrivilegeManager
import io.obtainmond.worker.AutoUpdateWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ObtainmondApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        PrivilegeManager.init(this)
        AppRepository.init(this)

        val settings = AppRepository.settings.value
        AutoUpdateWorker.schedulePeriodic(this, settings.backgroundIntervalMinutes)

        appScope.launch {
            if (settings.autoWhitelistBatteryDoze) {
                PrivilegeManager.enforceZeroBatteryRestriction(this@ObtainmondApp, settings)
            }
            if (settings.zeroTouchBackgroundUpdate) {
                AutoUpdateWorker.triggerImmediateBackgroundCheck(this@ObtainmondApp)
            }
        }
    }
}
