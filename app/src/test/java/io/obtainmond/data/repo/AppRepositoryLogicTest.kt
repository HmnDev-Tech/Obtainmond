package io.obtainmond.data.repo

import io.obtainmond.data.model.FolderRule
import io.obtainmond.data.model.TrackedApp
import io.obtainmond.privilege.ShellExecOutcome
import io.obtainmond.rust.SourceDescriptorDto
import io.obtainmond.security.HttpFetchResponse
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class AppRepositoryLogicTest {

    @Test
    fun staleSuccessfulInstallMetadataDoesNotSuppressRetry() {
        val download = "https://github.com/example/mpv/releases/download/v3.1.1/mpv.apk"
        val stale = TrackedApp(
            id = "org.example.mpv",
            name = "mpv",
            author = "example",
            url = "https://github.com/example/mpv",
            installedVersion = "3.0.2",
            latestVersion = "3.1.1",
            installedReleaseTag = "3.1.1",
            installedApkUrl = download,
            matchedApkUrl = download,
            lastUpdatedEpochMs = 123,
            lastSha256 = "stale",
            pinnedSigningCertSha256 = "pin"
        )
        val reconciled = AppRepository.applyMatchedPackageState(
            stale, stale.id, "3.0.2", "arm64-v8a", false, "pin"
        )
        assertEquals("3.0.2", reconciled.installedVersion)
        assertEquals("", reconciled.installedReleaseTag)
        assertEquals("", reconciled.installedApkUrl)
        assertEquals(0L, reconciled.lastUpdatedEpochMs)
        assertEquals("", reconciled.lastSha256)
        assertEquals("pin", reconciled.pinnedSigningCertSha256)
        assertTrue(reconciled.hasUpdate)

        val verified = stale.copy(installedReleaseVersionCode = 302)
        val current = AppRepository.applyMatchedPackageState(
            verified, verified.id, "3.0.2", "arm64-v8a", false, "pin", installedVersionCode = 302
        )
        assertEquals(download, current.installedApkUrl)
        assertFalse(current.hasUpdate)

        val replaced = AppRepository.applyMatchedPackageState(
            verified, verified.id, "3.0.2", "arm64-v8a", false, "pin", installedVersionCode = 301
        )
        assertEquals("", replaced.installedApkUrl)
        assertTrue(replaced.hasUpdate)

    }

    @Test
    fun installSuccessRequiresExplicitConfirmation() {
        val confirmed = ShellExecOutcome(0, "Success", "", "Shevery")
        assertTrue(confirmed.isInstallSuccess)
        assertFalse(confirmed.copy(stdout = "", stderr = "Permission denied").isInstallSuccess)
        assertFalse(confirmed.copy(stdout = "Success: streamed 10 bytes").isInstallSuccess)
        assertFalse(confirmed.copy(stdout = "Failure [INSTALL_FAILED_INVALID_APK]").isInstallSuccess)
        assertFalse(confirmed.copy(exitCode = 1).isInstallSuccess)
        assertFalse(confirmed.copy(installPending = true).isInstallSuccess)
    }

    @Test
    fun strictPackageSyncNeverHijacksByDisplayNameAndFlagsPinMismatch() {
        val tracked = TrackedApp(
            id = "org.real.messenger",
            name = "Signal",
            author = "Signal",
            url = "https://github.com/org/real",
            installedVersion = "1.0.0",
            pinnedSigningCertSha256 = "aaaa1111"
        )

        val spoofAttempt = AppRepository.applyMatchedPackageState(
            app = tracked,
            matchedPackageId = "com.evil.clone",
            versionName = "9.9.9",
            detectedAbi = "arm64-v8a",
            isSuspended = false,
            installedCertSha256 = "deadbeef"
        )
        assertEquals("org.real.messenger", spoofAttempt.id)
        assertEquals("1.0.0", spoofAttempt.installedVersion)

        val validMatchDifferentCert = AppRepository.applyMatchedPackageState(
            app = tracked,
            matchedPackageId = "org.real.messenger",
            versionName = "2.0.0",
            detectedAbi = "arm64-v8a",
            isSuspended = false,
            installedCertSha256 = "bbbb2222"
        )
        assertEquals("2.0.0", validMatchDifferentCert.installedVersion)
        assertEquals("SIGNATURE_MISMATCH", validMatchDifferentCert.errorCategory)
        assertTrue(validMatchDifferentCert.needsAttention)
    }

    @Test
    fun githubPatPoolRotatesOn403And429AndCategorizesExhaustion() {
        val cursor = AtomicInteger(0)
        val seenAuthHeaders = mutableListOf<String>()
        val body = AppRepository.fetchEndpointWithTokenRotation(
            endpoint = "https://api.github.com/repos/a/b/releases",
            sourceType = "GITHUB",
            githubTokens = listOf("tok1", "tok2", "tok3"),
            gitlabToken = "",
            tokenCursor = cursor
        ) { _, headers ->
            val auth = headers["Authorization"].orEmpty()
            seenAuthHeaders.add(auth)
            when (auth) {
                "Bearer tok1" -> HttpFetchResponse(403, "rate limited")
                "Bearer tok2" -> HttpFetchResponse(429, "too many requests")
                else -> HttpFetchResponse(200, """[{"tag_name":"v1.2.3"}]""")
            }
        }
        assertTrue(body.contains("v1.2.3"))
        assertEquals(listOf("Bearer tok1", "Bearer tok2", "Bearer tok3"), seenAuthHeaders)

        val err = runCatching {
            AppRepository.fetchEndpointWithTokenRotation(
                endpoint = "https://api.github.com/repos/a/b/releases",
                sourceType = "GITHUB",
                githubTokens = listOf("tokA", "tokB"),
                gitlabToken = "",
                tokenCursor = AtomicInteger(0)
            ) { _, _ -> HttpFetchResponse(429, "rate limit") }
        }.exceptionOrNull()!!
        val (cat, _) = AppRepository.categorizeError(err)
        assertEquals("RATE_LIMIT", cat)
    }

    @Test
    fun concurrentRefreshRespectsBoundedParallelismAndOnDemandOnly() = runBlocking {
        val active = AtomicInteger(0)
        val peakObserved = AtomicInteger(0)
        val fetchedIds = mutableListOf<String>()

        val apps = (1..10).map { idx ->
            TrackedApp(
                id = "io.test.app$idx",
                name = "App $idx",
                author = "org",
                url = "https://github.com/org/app$idx",
                onDemandOnly = (idx == 10)
            )
        }

        val updated = AppRepository.refreshAppsWithConcurrency(
            snapshot = apps,
            maxConcurrency = 4,
            isBackgroundWorker = true,
            fetcher = { app ->
                val now = active.incrementAndGet()
                peakObserved.updateAndGet { prev -> maxOf(prev, now) }
                synchronized(fetchedIds) { fetchedIds.add(app.id) }
                delay(40)
                active.decrementAndGet()
                app.copy(latestVersion = "2.0.0")
            },
            onError = { app, e -> app.copy(errorCategory = "NETWORK", lastError = e.message.orEmpty()) }
        )

        assertTrue("Expected true parallel fan-out > 1, got ${peakObserved.get()}", peakObserved.get() > 1)
        assertTrue("Expected Semaphore bound <= 4, got ${peakObserved.get()}", peakObserved.get() <= 4)
        assertFalse("Background run must skip onDemandOnly app", "io.test.app10" in fetchedIds)
        assertEquals(9, fetchedIds.size)
        assertEquals("", updated.last().latestVersion)
    }

    @Test
    fun skipVersionAndFolderRulesWorkCorrectly() {
        val app = TrackedApp(
            id = "org.fdroid.client",
            name = "F-Droid Client",
            author = "F-Droid",
            url = "https://f-droid.org/packages/org.fdroid.client/",
            sourceType = "FDROID",
            installedVersion = "1.0.0",
            latestVersion = "1.1.0",
            skippedVersion = "1.1.0"
        )
        assertFalse("Skipped version should suppress hasUpdate", app.hasUpdate)

        val newerApp = app.copy(latestVersion = "1.2.0")
        assertTrue("Newer non-skipped version must trigger hasUpdate", newerApp.hasUpdate)

        val ruled = AppRepository.applyFolderRules(
            newerApp,
            listOf(
                FolderRule(
                    id = "rule_fdroid",
                    folderName = "FOSS",
                    sourceTypes = listOf("FDROID"),
                    keywords = listOf("f-droid.org")
                ),
                FolderRule(
                    id = "rule_ignored",
                    folderName = "Ignored",
                    sourceTypes = listOf("GITHUB")
                )
            )
        )
        assertEquals(listOf("FOSS"), ruled.folders)
    }

    @Test
    fun legacyGitHubPerPage15EndpointUpgradesAndResolvesLatestFallback() {
        val desc = SourceDescriptorDto(
            sourceType = "GITHUB",
            canonicalUrl = "https://github.com/Riteshp2001/mpvRx",
            apiUrl = "https://api.github.com/repos/Riteshp2001/mpvRx/releases?per_page=100",
            owner = "Riteshp2001",
            repo = "mpvRx"
        )

        assertEquals(
            "https://api.github.com/repos/Riteshp2001/mpvRx/releases?per_page=100",
            AppRepository.resolveEffectiveApiEndpoint(
                "https://api.github.com/repos/Riteshp2001/mpvRx/releases?per_page=15",
                desc
            )
        )
        assertEquals(
            "https://api.github.com/repos/Riteshp2001/mpvRx/releases?per_page=100",
            AppRepository.resolveEffectiveApiEndpoint(
                "https://api.github.com/repos/Riteshp2001/mpvRx/releases",
                desc
            )
        )
        assertEquals(
            "https://api.github.com/repos/Riteshp2001/mpvRx/releases/latest",
            AppRepository.resolveGitHubLatestFallbackUrl(
                desc = desc,
                usedEndpoint = desc.apiUrl,
                includePrereleases = false
            )
        )
        assertEquals(
            null,
            AppRepository.resolveGitHubLatestFallbackUrl(
                desc = desc,
                usedEndpoint = desc.apiUrl,
                includePrereleases = true
            )
        )
    }
}
