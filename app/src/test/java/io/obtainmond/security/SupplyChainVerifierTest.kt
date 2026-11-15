package io.obtainmond.security

import io.obtainmond.data.model.ObtainmondSettings
import io.obtainmond.data.model.TrackedApp
import io.obtainmond.data.model.VerificationPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupplyChainVerifierTest {

    private val sampleSha = "abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234"

    @Test
    fun fdroidReproducibleBuildVerifiedAndEnforceBlocksMismatch() {
        val verifier = SupplyChainVerifier { url, _ ->
            when (url) {
                "https://verification.f-droid.org/org.fdroid.app.json" -> HttpFetchResponse(
                    code = 200,
                    body = """{"lastRunVerified": true, "apkReports": ["reports/2026.json"]}"""
                )
                "https://verification.f-droid.org/reports/2026.json" -> HttpFetchResponse(
                    code = 200,
                    body = """{"org.fdroid.app_10.apk": {"verified": true, "remote": {"sha256": "$sampleSha"}}}"""
                )
                else -> HttpFetchResponse(404, "")
            }
        }
        val app = TrackedApp(
            id = "org.fdroid.app",
            name = "FDroidApp",
            author = "FDroid",
            url = "https://f-droid.org/packages/org.fdroid.app/",
            sourceType = "FDROID"
        )

        val okResult = verifier.verify(
            app = app,
            packageId = "org.fdroid.app",
            apkSha256Hex = sampleSha,
            settings = ObtainmondSettings(reproducibleBuildsPolicy = VerificationPolicy.ENFORCE)
        )
        assertTrue(okResult.passed)
        assertFalse(okResult.shouldBlock)
        assertEquals("VERIFIED", okResult.checks.single().status)

        val auditMiss = verifier.verify(
            app = app,
            packageId = "org.fdroid.app",
            apkSha256Hex = "0000000000000000000000000000000000000000000000000000000000000000",
            settings = ObtainmondSettings(reproducibleBuildsPolicy = VerificationPolicy.AUDIT)
        )
        assertFalse(auditMiss.shouldBlock)
        assertEquals("WARNING", auditMiss.checks.single().status)

        val enforceMiss = verifier.verify(
            app = app,
            packageId = "org.fdroid.app",
            apkSha256Hex = "0000000000000000000000000000000000000000000000000000000000000000",
            settings = ObtainmondSettings(reproducibleBuildsPolicy = VerificationPolicy.ENFORCE)
        )
        assertTrue(enforceMiss.shouldBlock)
        assertEquals("FAILED", enforceMiss.checks.single().status)
    }

    @Test
    fun izzyOnDroidReproducibleLogHandlesTrueAndFalse() {
        val app = TrackedApp(
            id = "org.izzy.tool",
            name = "IzzyTool",
            author = "Izzy",
            url = "https://apt.izzysoft.de/fdroid/index/apk/org.izzy.tool",
            sourceType = "IZZYONDROID"
        )
        val okVerifier = SupplyChainVerifier { url, _ ->
            if (url.contains("rbtlog")) {
                HttpFetchResponse(
                    code = 200,
                    body = """{"hashes": {"$sampleSha": "1.0.0"}}"""
                )
            } else {
                HttpFetchResponse(404, "")
            }
        }
        val ok = okVerifier.verify(
            app = app,
            packageId = "org.izzy.tool",
            apkSha256Hex = sampleSha,
            settings = ObtainmondSettings(reproducibleBuildsPolicy = VerificationPolicy.ENFORCE)
        )
        assertFalse(ok.shouldBlock)
        assertEquals("VERIFIED", ok.checks.single().status)

        val falseVerifier = SupplyChainVerifier { _, _ ->
            HttpFetchResponse(
                code = 200,
                body = """{"tags": {"v1": [{"upstream_signed_apk_sha256": "$sampleSha", "reproducible": false}]}}"""
            )
        }
        val bad = falseVerifier.verify(
            app = app,
            packageId = "org.izzy.tool",
            apkSha256Hex = sampleSha,
            settings = ObtainmondSettings(reproducibleBuildsPolicy = VerificationPolicy.ENFORCE)
        )
        assertTrue(bad.shouldBlock)
        assertEquals("FAILED", bad.checks.single().status)
    }

    @Test
    fun githubAttestationAndVirusTotalChecksBehavePerPolicy() {
        val ghApp = TrackedApp(
            id = "org.gh.app",
            name = "GhApp",
            author = "org",
            url = "https://github.com/org/app",
            sourceType = "GITHUB"
        )

        val verifier = SupplyChainVerifier { url, headers ->
            when {
                url.contains("/attestations/sha256:$sampleSha") -> {
                    assertEquals("Bearer ghp_secret", headers["Authorization"])
                    HttpFetchResponse(200, """{"attestations":[{"bundle":{}}]}""")
                }
                url.contains("/api/v3/files/$sampleSha") -> {
                    assertEquals("vt_key_1", headers["x-apikey"])
                    HttpFetchResponse(
                        200,
                        """{"data":{"attributes":{"last_analysis_stats":{"malicious":2,"suspicious":0,"harmless":60,"undetected":8}}}}"""
                    )
                }
                else -> HttpFetchResponse(404, "")
            }
        }

        val combined = verifier.verify(
            app = ghApp,
            packageId = "org.gh.app",
            apkSha256Hex = sampleSha,
            settings = ObtainmondSettings(
                githubAttestationPolicy = VerificationPolicy.ENFORCE,
                virusTotalPolicy = VerificationPolicy.AUDIT
            ),
            githubToken = "ghp_secret",
            virusTotalApiKey = "vt_key_1"
        )

        assertTrue("VirusTotal malicious > 0 must block even under AUDIT", combined.shouldBlock)
        assertEquals(2, combined.checks.size)
        assertEquals("VERIFIED", combined.checks[0].status)
        assertEquals("FAILED", combined.checks[1].status)
    }
}
