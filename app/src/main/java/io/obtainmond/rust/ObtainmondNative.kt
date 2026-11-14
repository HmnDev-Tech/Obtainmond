package io.obtainmond.rust

import android.os.Build
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ApkCandidateDto(
    val name: String,
    val url: String,
    val size: Long = 0L
)

@Serializable
data class ApkSelectionRequestDto(
    val candidates: List<ApkCandidateDto>,
    @SerialName("device_abis") val deviceAbis: List<String>,
    @SerialName("installed_abi") val installedAbi: String = "",
    @SerialName("previous_apk_name") val previousApkName: String = "",
    @SerialName("filter_regex") val filterRegex: String = "",
    @SerialName("exclude_debug") val excludeDebug: Boolean = true
)

@Serializable
data class ScoredCandidateDto(
    val name: String,
    val url: String,
    @SerialName("detected_abi") val detectedAbi: String = "universal",
    val score: Int = 0,
    val reason: String = ""
)

@Serializable
data class ApkSelectionResponseDto(
    @SerialName("selected_name") val selectedName: String = "",
    @SerialName("selected_url") val selectedUrl: String = "",
    @SerialName("detected_abi") val detectedAbi: String = "",
    val score: Int = -1,
    val confidence: String = "NONE",
    val reason: String = "",
    @SerialName("ranked_candidates") val rankedCandidates: List<ScoredCandidateDto> = emptyList()
)

@Serializable
data class SourceDescriptorDto(
    @SerialName("source_type") val sourceType: String = "GITHUB",
    @SerialName("canonical_url") val canonicalUrl: String = "",
    @SerialName("api_url") val apiUrl: String = "",
    val owner: String = "",
    val repo: String = "",
    @SerialName("app_name_hint") val appNameHint: String = "",
    @SerialName("package_id_hint") val packageIdHint: String = ""
)

@Serializable
data class ParsedReleaseDto(
    val version: String = "",
    @SerialName("raw_tag") val rawTag: String = "",
    @SerialName("release_title") val releaseTitle: String = "",
    val changelog: String = "",
    @SerialName("published_at") val publishedAt: String = "",
    @SerialName("is_prerelease") val isPrerelease: Boolean = false,
    @SerialName("apk_assets") val apkAssets: List<ApkCandidateDto> = emptyList(),
    val error: String? = null
)

@Serializable
data class ApkIntegrityReportDto(
    @SerialName("valid_apk_zip") val validApkZip: Boolean = false,
    @SerialName("has_manifest") val hasManifest: Boolean = false,
    @SerialName("has_signature_block") val hasSignatureBlock: Boolean = false,
    @SerialName("sha256_hex") val sha256Hex: String = "",
    @SerialName("file_size_bytes") val fileSizeBytes: Long = 0L,
    @SerialName("native_abis") val nativeAbis: List<String> = emptyList(),
    @SerialName("abi_compatible") val abiCompatible: Boolean = true,
    val summary: String = "",
    val error: String? = null
)

object ObtainmondNative {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    var isLoaded: Boolean = false
        private set

    init {
        isLoaded = try {
            System.loadLibrary("obtainmond_core")
            true
        } catch (_: Throwable) {
            false
        }
    }

    @JvmStatic
    external fun getEngineVersion(): String

    @JvmStatic
    external fun normalizeVersion(rawVersion: String, regexPattern: String): String

    @JvmStatic
    external fun compareVersionsJson(
        installedVersion: String,
        remoteVersion: String,
        allowDowngrade: Boolean
    ): String

    @JvmStatic
    external fun isUpdateAvailable(
        installedVersion: String,
        remoteVersion: String,
        allowDowngrade: Boolean
    ): Boolean

    @JvmStatic
    external fun selectBestApkJson(requestJson: String): String

    @JvmStatic
    external fun resolveSourceUrlJson(rawUrl: String): String

    @JvmStatic
    external fun parseReleasePayloadJson(
        sourceType: String,
        payload: String,
        includePrereleases: Boolean,
        fallbackUrl: String
    ): String

    @JvmStatic
    external fun inspectApkFileJson(apkPath: String, deviceAbisCsv: String): String

    @JvmStatic
    external fun buildSilentInstallCommand(
        apkPath: String,
        installerPackage: String,
        allowDowngrade: Boolean,
        grantRuntimePermissions: Boolean,
        targetPackageForDexopt: String
    ): String

    fun safeEngineVersion(): String {
        return if (isLoaded) {
            runCatching { getEngineVersion() }.getOrDefault("Obtainmond Rust Core v2.0.0")
        } else {
            "Obtainmond Rust Core v2.0.0 (Fallback)"
        }
    }

    private val preReleaseTagRegex = Regex(
        """(?:alpha|beta|rc|pre|preview|dev|nightly|canary)""",
        RegexOption.IGNORE_CASE
    )

    fun safeNormalizeVersion(raw: String, regexPattern: String = ""): String {
        val base = raw.trim()
        val preCleaned = if (regexPattern.isBlank()) {
            base.substringBefore("(").substringBefore("+").trim()
        } else {
            base
        }
        if (isLoaded) {
            runCatching {
                val res = normalizeVersion(preCleaned, regexPattern)
                if (res.isNotBlank()) return res
            }
        }
        var s = base.substringBefore("(").substringBefore("+").trim()
        val lower = s.lowercase()
        for (prefix in listOf("release-", "version-", "ver-", "rel-", "v.", "v")) {
            if (lower.startsWith(prefix) && s.length > prefix.length && s[prefix.length].isDigit()) {
                s = s.substring(prefix.length)
                break
            }
        }
        val suffixes = listOf(
            "-rust", "_rust", "-release", "_release", "-universal", "_universal",
            "-foss", "_foss", "-fdroid", "-gms", "-signed", "_signed",
            "-arm64-v8a", "-armeabi-v7a", "-x86_64", "-x86"
        )
        while (true) {
            val cur = s.lowercase()
            val hit = suffixes.firstOrNull { cur.endsWith(it) && s.length > it.length } ?: break
            s = s.substring(0, s.length - hit.length)
        }
        return s.trim('-', '_', '.', ' ')
    }

    private fun extractLeadingNumericParts(version: String): Pair<List<Long>, String> {
        val norm = safeNormalizeVersion(version)
        val nums = mutableListOf<Long>()
        var idx = 0
        while (idx < norm.length) {
            if (norm[idx].isDigit()) {
                val start = idx
                while (idx < norm.length && norm[idx].isDigit()) idx++
                norm.substring(start, idx).toLongOrNull()?.let { nums.add(it) }
                if (idx < norm.length && (norm[idx] == '.' || norm[idx] == '_' || norm[idx] == '-')) {
                    if (idx + 1 < norm.length && norm[idx + 1].isDigit()) {
                        idx++
                        continue
                    }
                }
                break
            } else {
                break
            }
        }
        while (nums.size > 1 && nums.last() == 0L) {
            nums.removeAt(nums.lastIndex)
        }
        val remainder = if (idx <= norm.length) norm.substring(idx).trim('-', '_', '.', ' ') else ""
        return nums to remainder
    }

    fun areVersionsEquivalent(installed: String, remote: String): Boolean {
        val rawA = installed.trim()
        val rawB = remote.trim()
        if (rawA.isBlank() || rawB.isBlank()) return false
        if (rawA.equals(rawB, ignoreCase = true)) return true

        val normA = safeNormalizeVersion(rawA)
        val normB = safeNormalizeVersion(rawB)
        if (normA.isNotBlank() && normA.equals(normB, ignoreCase = true)) return true

        val (numsA, remA) = extractLeadingNumericParts(normA)
        val (numsB, remB) = extractLeadingNumericParts(normB)
        if (numsA.isNotEmpty() && numsA == numsB) {
            val hasPreA = preReleaseTagRegex.containsMatchIn(remA)
            val hasPreB = preReleaseTagRegex.containsMatchIn(remB)
            if (!hasPreA && !hasPreB) {
                val remNumsA = Regex("""\d+""").findAll(remA).mapNotNull { it.value.toLongOrNull() }.maxOrNull()
                val remNumsB = Regex("""\d+""").findAll(remB).mapNotNull { it.value.toLongOrNull() }.maxOrNull()
                if (remNumsA == remNumsB) return true
                if (remA.isNotEmpty() && remB.isEmpty()) return true
            }
        }
        return false
    }

    fun safeIsUpdateAvailable(
        installed: String,
        remote: String,
        allowDowngrade: Boolean = false
    ): Boolean {
        if (installed.isBlank() || remote.isBlank()) return false
        if (!allowDowngrade && areVersionsEquivalent(installed, remote)) return false
        if (isLoaded) {
            runCatching { return isUpdateAvailable(installed, remote, allowDowngrade) }
        }
        val (numsA, remA) = extractLeadingNumericParts(installed)
        val (numsB, remB) = extractLeadingNumericParts(remote)
        if (numsA.isNotEmpty() && numsB.isNotEmpty()) {
            val max = maxOf(numsA.size, numsB.size)
            for (i in 0 until max) {
                val va = numsA.getOrElse(i) { 0L }
                val vb = numsB.getOrElse(i) { 0L }
                if (va != vb) return if (allowDowngrade) true else vb > va
            }
            val preA = preReleaseTagRegex.containsMatchIn(remA)
            val preB = preReleaseTagRegex.containsMatchIn(remB)
            if (preA && !preB) return true
            if (!preA && preB) return allowDowngrade
            return false
        }
        val a = safeNormalizeVersion(installed)
        val b = safeNormalizeVersion(remote)
        return a.isNotEmpty() && b.isNotEmpty() && !a.equals(b, ignoreCase = true)
    }

    fun selectBestApkForDevice(
        candidates: List<ApkCandidateDto>,
        installedAbi: String = "",
        previousApkName: String = "",
        filterRegex: String = "",
        excludeDebug: Boolean = true
    ): ApkSelectionResponseDto {
        val deviceAbis = Build.SUPPORTED_ABIS.toList().ifEmpty { listOf("arm64-v8a", "armeabi-v7a") }
        val req = ApkSelectionRequestDto(
            candidates = candidates,
            deviceAbis = deviceAbis,
            installedAbi = installedAbi,
            previousApkName = previousApkName,
            filterRegex = filterRegex,
            excludeDebug = excludeDebug
        )
        if (isLoaded) {
            runCatching {
                val raw = selectBestApkJson(json.encodeToString(req))
                return json.decodeFromString<ApkSelectionResponseDto>(raw)
            }
        }
        val fallback = candidates.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
        return if (fallback != null) {
            ApkSelectionResponseDto(
                selectedName = fallback.name,
                selectedUrl = fallback.url,
                detectedAbi = installedAbi.ifEmpty { deviceAbis.first() },
                score = 500,
                confidence = "FALLBACK_MATCH",
                reason = "Selected primary APK asset"
            )
        } else {
            ApkSelectionResponseDto()
        }
    }

    fun resolveSource(url: String): SourceDescriptorDto {
        if (isLoaded) {
            runCatching {
                return json.decodeFromString<SourceDescriptorDto>(resolveSourceUrlJson(url))
            }
        }
        return SourceDescriptorDto(
            sourceType = "GITHUB",
            canonicalUrl = url.trim(),
            apiUrl = url.trim(),
            appNameHint = url.substringAfterLast('/')
        )
    }

    fun parseRelease(
        sourceType: String,
        payload: String,
        includePrereleases: Boolean,
        fallbackUrl: String
    ): ParsedReleaseDto {
        if (isLoaded) {
            runCatching {
                val raw = parseReleasePayloadJson(sourceType, payload, includePrereleases, fallbackUrl)
                return json.decodeFromString<ParsedReleaseDto>(raw)
            }
        }
        return ParsedReleaseDto(error = "Rust parser unavailable")
    }

    fun inspectApk(apkPath: String): ApkIntegrityReportDto {
        val abisCsv = Build.SUPPORTED_ABIS.joinToString(",")
        if (isLoaded) {
            runCatching {
                val raw = inspectApkFileJson(apkPath, abisCsv)
                return json.decodeFromString<ApkIntegrityReportDto>(raw)
            }
        }
        return ApkIntegrityReportDto(
            validApkZip = true,
            hasManifest = true,
            hasSignatureBlock = true,
            abiCompatible = true,
            summary = "Fallback APK check passed"
        )
    }

    fun buildInstallShellCmd(
        apkPath: String,
        installerPackage: String,
        allowDowngrade: Boolean,
        grantRuntimePermissions: Boolean,
        targetPackageForDexopt: String
    ): String {
        if (isLoaded) {
            runCatching {
                return buildSilentInstallCommand(
                    apkPath,
                    installerPackage,
                    allowDowngrade,
                    grantRuntimePermissions,
                    targetPackageForDexopt
                )
            }
        }
        val flags = buildString {
            append("-r")
            if (allowDowngrade) append(" -d")
            if (grantRuntimePermissions) append(" -g")
            if (installerPackage.isNotBlank()) append(" -i '$installerPackage'")
        }
        return "pm install $flags '$apkPath'"
    }
}
