package io.obtainmond.privilege

import com.hamondev.shevery.api.SheveryProvider

/**
 * Safe subclass of [SheveryProvider] that avoids calling [SheveryPrivilege.connectFast]
 * synchronously inside [ContentProvider.onCreate].
 *
 * In Shevery-API v14.6.4, [SheveryProvider.onCreate] calls `connectFast(ctx)`, which
 * queries `content://${applicationId}.shevery` before Android's `ActivityThread` has
 * finished registering the provider, causing an infinite re-entrant `Loading provider`
 * loop on startup when Shevery Manager is not installed.
 *
 * Instead, [PrivilegeManager.init] triggers `connectFast` asynchronously on `Dispatchers.IO`
 * after provider registration completes.
 */
class ObtainmondSheveryProvider : SheveryProvider() {
    override fun onCreate(): Boolean = true
}
