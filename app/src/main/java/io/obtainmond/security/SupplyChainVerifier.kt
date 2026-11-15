package io.obtainmond.security

import io.obtainmond.data.model.ObtainmondSettings
import io.obtainmond.data.model.TrackedApp
import io.obtainmond.data.model.VerificationPolicy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

data class HttpFetchResponse(
    val code: Int,
    val body: String
)

data class SupplyChainCheckResult(
    val checkName: String,
    val status: String, // VERIFIED, WARNING, FAILED, SKIPPED
    val summary: String,
    val shouldBlock: Boolean = false
)

data class SupplyChainAggregateResult(
    val passed: Boolean,
    val shouldBlock: Boolean,
    val badgeSummary: String,
    val detailedReport: String,
    val checks: List<SupplyChainCheckResult>
)

class SupplyChainVerifier(
    private val fetchUrl: (url: String, headers: Map<String, String>) -> HttpFetchResponse
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun verify(
        app: TrackedApp,
        packageId: String,
        apkSha256Hex: String,
        settings: ObtainmondSettings,
        githubToken: String = "",
        virusTotalApiKey: String = ""
    ): SupplyChainAggregateResult {
        val checks = mutableListOf<SupplyChainCheckResult>()

        // 1. F-Droid / IzzyOnDroid Reproducible Builds verification
        if (settings.reproducibleBuildsPolicy != VerificationPolicy.OFF) {
            when (app.sourceType.uppercase()) {
                "FDROID" -> checks.add(
                    checkFdroidReproducibleBuilds(packageId, apkSha256Hex, settings.reproducibleBuildsPolicy)
                )
                "IZZYONDROID" -> checks.add(
                    checkIzzyOnDroidReproducibleBuilds(packageId, apkSha256Hex, settings.reproducibleBuildsPolicy)
                )
            }
        }

        // 2. GitHub Release Attestations
        if (settings.githubAttestationPolicy != VerificationPolicy.OFF &&
            app.sourceType.equals("GITHUB", ignoreCase = true)
        ) {
            checks.add(
                checkGitHubAttestation(app, apkSha256Hex, githubToken, settings.githubAttestationPolicy)
            )
        }

        // 3. Optional VirusTotal API v3 hash check
        if (settings.virusTotalPolicy != VerificationPolicy.OFF && virusTotalApiKey.isNotBlank()) {
            checks.add(
                checkVirusTotalHash(apkSha256Hex, virusTotalApiKey, settings.virusTotalPolicy)
            )
        }

        if (checks.isEmpty()) {
            return SupplyChainAggregateResult(
                passed = true,
                shouldBlock = false,
                badgeSummary = "",
                detailedReport = "",
                checks = emptyList()
            )
        }

        val shouldBlock = checks.any { it.shouldBlock }
        val anyFailed = checks.any { it.status == "FAILED" }
        val badgeSummary = checks.joinToString(" • ") { "${it.checkName}: ${it.status}" }
        val detailedReport = checks.joinToString("\n") { "[${it.checkName}] ${it.status} — ${it.summary}" }

        return SupplyChainAggregateResult(
            passed = !anyFailed && !shouldBlock,
            shouldBlock = shouldBlock,
            badgeSummary = badgeSummary,
            detailedReport = detailedReport,
            checks = checks
        )
    }

    private fun checkFdroidReproducibleBuilds(
        packageId: String,
        apkSha256Hex: String,
        policy: VerificationPolicy
    ): SupplyChainCheckResult {
        val pkgUrl = "https://verification.f-droid.org/$packageId.json"
        val resp = runCatching { fetchUrl(pkgUrl, emptyMap()) }.getOrNull()
        if (resp == null || resp.code !in 200..299 || resp.body.isBlank()) {
            return SupplyChainCheckResult(
                checkName = "F-Droid RB",
                status = if (policy == VerificationPolicy.ENFORCE) "FAILED" else "WARNING",
                summary = "No verification record found at verification.f-droid.org",
                shouldBlock = policy == VerificationPolicy.ENFORCE
            )
        }

        val root = runCatching { json.parseToJsonElement(resp.body).jsonObject }.getOrNull()
        val apkReports = root?.get("apkReports")?.jsonArray.orEmpty()
            .mapNotNull { it.jsonPrimitive.content.takeIf { s -> s.isNotBlank() } }
        var matchedShaVerified: Boolean? = null
        for (reportPath in apkReports.take(5)) {
            val reportUrl = "https://verification.f-droid.org/${reportPath.trimStart('/')}"
            val r = runCatching { fetchUrl(reportUrl, emptyMap()) }.getOrNull() ?: continue
            if (r.code !in 200..299) continue
            val obj = runCatching { json.parseToJsonElement(r.body).jsonObject }.getOrNull() ?: continue
            for ((_, v) in obj) {
                val entry = runCatching { v.jsonObject }.getOrNull() ?: continue
                val remoteSha = entry["remote"]?.jsonObject?.get("sha256")?.jsonPrimitive?.content.orEmpty()
                if (remoteSha.equals(apkSha256Hex, ignoreCase = true)) {
                    matchedShaVerified = entry["verified"]?.jsonPrimitive?.booleanOrNull == true
                    break
                }
            }
            if (matchedShaVerified != null) break
        }

        return when (matchedShaVerified) {
            true -> SupplyChainCheckResult(
                checkName = "F-Droid RB",
                status = "VERIFIED",
                summary = "Reproducible build verified for SHA-256 ${apkSha256Hex.take(12)}..."
            )
            false -> SupplyChainCheckResult(
                checkName = "F-Droid RB",
                status = "FAILED",
                summary = "Reproducible build verification reported FALSE for this APK",
                shouldBlock = policy == VerificationPolicy.ENFORCE
            )
            null -> {
                val lastRunOk = root?.get("lastRunVerified")?.jsonPrimitive?.booleanOrNull == true
                SupplyChainCheckResult(
                    checkName = "F-Droid RB",
                    status = if (policy == VerificationPolicy.ENFORCE) "FAILED" else "WARNING",
                    summary = if (lastRunOk) {
                        "Hash ${apkSha256Hex.take(12)}... not matched in recent RB reports"
                    } else {
                        "Latest F-Droid RB run did not verify this hash"
                    },
                    shouldBlock = policy == VerificationPolicy.ENFORCE
                )
            }
        }
    }

    private fun checkIzzyOnDroidReproducibleBuilds(
        packageId: String,
        apkSha256Hex: String,
        policy: VerificationPolicy
    ): SupplyChainCheckResult {
        val url = "https://codeberg.org/IzzyOnDroid/rbtlog/raw/branch/izzy/log/logs/$packageId.json"
        val resp = runCatching { fetchUrl(url, emptyMap()) }.getOrNull()
        if (resp == null || resp.code !in 200..299 || resp.body.isBlank()) {
            return SupplyChainCheckResult(
                checkName = "IzzyOnDroid RB",
                status = if (policy == VerificationPolicy.ENFORCE) "FAILED" else "WARNING",
                summary = "No rbtlog reproducibility log found for $packageId",
                shouldBlock = policy == VerificationPolicy.ENFORCE
            )
        }

        val root = runCatching { json.parseToJsonElement(resp.body).jsonObject }.getOrNull()
        val hashesObj = root?.get("hashes")?.jsonObject
        val inHashes = hashesObj?.keys?.any { it.equals(apkSha256Hex, ignoreCase = true) } == true
        var tagReproducible: Boolean? = null
        val tagsObj = root?.get("tags")?.jsonObject
        if (tagsObj != null) {
            for ((_, arrEl) in tagsObj) {
                val arr = runCatching { arrEl.jsonArray }.getOrNull() ?: continue
                for (itemEl in arr) {
                    val obj = runCatching { itemEl.jsonObject }.getOrNull() ?: continue
                    val sha = obj["upstream_signed_apk_sha256"]?.jsonPrimitive?.content.orEmpty()
                    if (sha.equals(apkSha256Hex, ignoreCase = true)) {
                        tagReproducible = obj["reproducible"]?.jsonPrimitive?.booleanOrNull
                        break
                    }
                }
            }
        }

        return when {
            tagReproducible == true || inHashes -> SupplyChainCheckResult(
                checkName = "IzzyOnDroid RB",
                status = "VERIFIED",
                summary = "Reproducible build confirmed in IzzyOnDroid rbtlog"
            )
            tagReproducible == false -> SupplyChainCheckResult(
                checkName = "IzzyOnDroid RB",
                status = "FAILED",
                summary = "IzzyOnDroid rbtlog marked hash as NOT reproducible",
                shouldBlock = policy == VerificationPolicy.ENFORCE
            )
            else -> SupplyChainCheckResult(
                checkName = "IzzyOnDroid RB",
                status = if (policy == VerificationPolicy.ENFORCE) "FAILED" else "WARNING",
                summary = "Hash ${apkSha256Hex.take(12)}... absent from IzzyOnDroid rbtlog",
                shouldBlock = policy == VerificationPolicy.ENFORCE
            )
        }
    }

    private fun checkGitHubAttestation(
        app: TrackedApp,
        apkSha256Hex: String,
        githubToken: String,
        policy: VerificationPolicy
    ): SupplyChainCheckResult {
        val ownerRepo = extractGitHubOwnerRepo(app.url) ?: return SupplyChainCheckResult(
            checkName = "GitHub Attestation",
            status = if (policy == VerificationPolicy.ENFORCE) "FAILED" else "WARNING",
            summary = "Unable to derive owner/repo from URL",
            shouldBlock = policy == VerificationPolicy.ENFORCE
        )
        val url = "https://api.github.com/repos/${ownerRepo.first}/${ownerRepo.second}/attestations/sha256:${apkSha256Hex.lowercase()}"
        val headers = buildMap {
            put("Accept", "application/vnd.github+json")
            if (githubToken.isNotBlank()) {
                put("Authorization", "Bearer ${githubToken.trim()}")
            }
        }
        val resp = runCatching { fetchUrl(url, headers) }.getOrNull()
        val count = if (resp != null && resp.code in 200..299) {
            runCatching {
                json.parseToJsonElement(resp.body).jsonObject["attestations"]?.jsonArray?.size ?: 0
            }.getOrDefault(0)
        } else {
            0
        }

        return if (count > 0) {
            SupplyChainCheckResult(
                checkName = "GitHub Attestation",
                status = "VERIFIED",
                summary = "Found $count Sigstore build provenance attestation(s)"
            )
        } else {
            SupplyChainCheckResult(
                checkName = "GitHub Attestation",
                status = if (policy == VerificationPolicy.ENFORCE) "FAILED" else "WARNING",
                summary = "No provenance attestation found for sha256:${apkSha256Hex.take(12)}...",
                shouldBlock = policy == VerificationPolicy.ENFORCE
            )
        }
    }

    private fun checkVirusTotalHash(
        apkSha256Hex: String,
        apiKey: String,
        policy: VerificationPolicy
    ): SupplyChainCheckResult {
        val url = "https://www.virustotal.com/api/v3/files/${apkSha256Hex.lowercase()}"
        val resp = runCatching { fetchUrl(url, mapOf("x-apikey" to apiKey.trim())) }.getOrNull()
        if (resp == null) {
            return SupplyChainCheckResult(
                checkName = "VirusTotal",
                status = if (policy == VerificationPolicy.ENFORCE) "FAILED" else "WARNING",
                summary = "VirusTotal API lookup unreachable",
                shouldBlock = policy == VerificationPolicy.ENFORCE
            )
        }
        if (resp.code == 404) {
            return SupplyChainCheckResult(
                checkName = "VirusTotal",
                status = "WARNING",
                summary = "Hash not yet indexed on VirusTotal",
                shouldBlock = false
            )
        }
        if (resp.code !in 200..299) {
            return SupplyChainCheckResult(
                checkName = "VirusTotal",
                status = if (policy == VerificationPolicy.ENFORCE) "FAILED" else "WARNING",
                summary = "VirusTotal returned HTTP ${resp.code}",
                shouldBlock = policy == VerificationPolicy.ENFORCE
            )
        }

        val stats = runCatching {
            json.parseToJsonElement(resp.body).jsonObject["data"]?.jsonObject
                ?.get("attributes")?.jsonObject
                ?.get("last_analysis_stats")?.jsonObject
        }.getOrNull()

        val malicious = stats?.get("malicious")?.jsonPrimitive?.intOrNull ?: 0
        val suspicious = stats?.get("suspicious")?.jsonPrimitive?.intOrNull ?: 0
        val harmless = stats?.get("harmless")?.jsonPrimitive?.intOrNull ?: 0
        val undetected = stats?.get("undetected")?.jsonPrimitive?.intOrNull ?: 0

        return if (malicious > 0) {
            SupplyChainCheckResult(
                checkName = "VirusTotal",
                status = "FAILED",
                summary = "FLAGGED by $malicious engine(s) ($suspicious suspicious, $harmless+$undetected clean)",
                shouldBlock = true // Always block malicious hits when VirusTotal lookup is active
            )
        } else if (suspicious > 0) {
            SupplyChainCheckResult(
                checkName = "VirusTotal",
                status = "WARNING",
                summary = "$suspicious engine(s) flagged suspicious ($malicious malicious)",
                shouldBlock = policy == VerificationPolicy.ENFORCE
            )
        } else {
            SupplyChainCheckResult(
                checkName = "VirusTotal",
                status = "VERIFIED",
                summary = "0 malicious detections ($undetected undetected)"
            )
        }
    }

    companion object {
        fun extractGitHubOwnerRepo(url: String): Pair<String, String>? {
            val clean = url.trim().trimEnd('/')
            val idx = clean.lowercase().indexOf("github.com/")
            if (idx < 0) return null
            val parts = clean.substring(idx + "github.com/".length)
                .split('/')
                .filter { it.isNotBlank() }
            if (parts.size < 2) return null
            return parts[0] to parts[1].removeSuffix(".git")
        }

        fun withOkHttp(httpClient: OkHttpClient): SupplyChainVerifier {
            return SupplyChainVerifier { url, headers ->
                val rb = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Obtainmond/2.0.1 (Android; SupplyChainGuard)")
                for ((k, v) in headers) {
                    rb.header(k, v)
                }
                httpClient.newCall(rb.build()).execute().use { resp ->
                    HttpFetchResponse(resp.code, resp.body.string())
                }
            }
        }
    }
}
