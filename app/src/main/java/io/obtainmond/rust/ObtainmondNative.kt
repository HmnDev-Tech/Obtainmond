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
    @SerialName("has_v2_v3_signature") val hasV2V3Signature: Boolean = false,
    @SerialName("has_v1_signature") val hasV1Signature: Boolean = false,
    @SerialName("entries_count") val entriesCount: Int = 0,
    @SerialName("sha256_hex") val sha256Hex: String = "",
    @SerialName("file_size_bytes") val fileSizeBytes: Long = 0L,
    @SerialName("native_abis") val nativeAbis: List<String> = emptyList(),
    @SerialName("abi_compatible") val abiCompatible: Boolean = false,
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
            runCatching { getEngineVersion() }.getOrDefault("Obtainmond Rust Core v2.5.0")
        } else {
            "Obtainmond Rust Core v2.5.0 (Fallback)"
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

    private fun isAndroidPackageOrBundle(name: String): Boolean {
        val clean = name.lowercase().substringBefore('?').substringBefore('#')
        return clean.endsWith(".apk") ||
            clean.endsWith(".apks") ||
            clean.endsWith(".xapk") ||
            clean.endsWith(".apkm") ||
            clean.endsWith(".apk.zip")
    }

    fun selectBestApkForDevice(
        candidates: List<ApkCandidateDto>,
        installedAbi: String = "",
        previousApkName: String = "",
        filterRegex: String = "",
        excludeDebug: Boolean = true
    ): ApkSelectionResponseDto {
        val deviceAbis = defaultDeviceAbis().ifEmpty { listOf("arm64-v8a", "armeabi-v7a") }
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
        val fallback = candidates.firstOrNull { it.name.lowercase().substringBefore('?').endsWith(".apk") }
            ?: candidates.firstOrNull { isAndroidPackageOrBundle(it.name) }
        return if (fallback != null) {
            ApkSelectionResponseDto(
                selectedName = fallback.name,
                selectedUrl = fallback.url,
                detectedAbi = installedAbi.ifEmpty { deviceAbis.first() },
                score = 500,
                confidence = "FALLBACK_MATCH",
                reason = "Selected primary package asset"
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
        return resolveSourceFallback(url)
    }

    private fun resolveSourceFallback(rawUrl: String): SourceDescriptorDto {
        val trimmed = rawUrl.trim().trimEnd('/')
        val lower = trimmed.lowercase()
        val afterProto = lower.substringAfter("://", lower)
        val host = afterProto.substringBefore('/')
        val path = afterProto.substringAfter('/', "")

        if (isAndroidPackageOrBundle(lower)) {
            return SourceDescriptorDto(
                sourceType = "DIRECT_APK",
                canonicalUrl = trimmed,
                apiUrl = trimmed,
                appNameHint = trimmed.substringAfterLast('/').substringBefore('?')
            )
        }

        if (host == "github.com" || host == "www.github.com") {
            val parts = trimmed.substringAfter("://").substringAfter('/').split('/').filter { it.isNotEmpty() }
            if (parts.size >= 2) {
                val owner = parts[0]
                val repo = parts[1].removeSuffix(".git")
                return SourceDescriptorDto(
                    sourceType = "GITHUB",
                    owner = owner,
                    repo = repo,
                    canonicalUrl = "https://github.com/$owner/$repo",
                    apiUrl = "https://api.github.com/repos/$owner/$repo/releases",
                    appNameHint = repo
                )
            }
        }

        if (host.contains("gitlab") || path.contains("/-/releases")) {
            val noProto = trimmed.substringAfter("://")
            val scheme = trimmed.substringBefore("://", "https")
            val rawHost = noProto.substringBefore('/')
            val project = noProto.substringAfter('/', "").substringBefore("/-/").trimEnd('/').removeSuffix(".git")
            if (project.isNotEmpty()) {
                val encoded = java.net.URLEncoder.encode(project, "UTF-8")
                return SourceDescriptorDto(
                    sourceType = "GITLAB",
                    repo = project,
                    canonicalUrl = "$scheme://$rawHost/$project",
                    apiUrl = "$scheme://$rawHost/api/v4/projects/$encoded/releases",
                    appNameHint = project.substringAfterLast('/')
                )
            }
        }

        if (host == "codeberg.org" || host.contains("forgejo") || host.contains("gitea")) {
            val noProto = trimmed.substringAfter("://")
            val scheme = trimmed.substringBefore("://", "https")
            val rawHost = noProto.substringBefore('/')
            val parts = noProto.substringAfter('/', "").split('/').filter { it.isNotEmpty() }
            if (parts.size >= 2) {
                val owner = parts[0]
                val repo = parts[1].removeSuffix(".git")
                return SourceDescriptorDto(
                    sourceType = "CODEBERG",
                    owner = owner,
                    repo = repo,
                    canonicalUrl = "$scheme://$rawHost/$owner/$repo",
                    apiUrl = "$scheme://$rawHost/api/v1/repos/$owner/$repo/releases",
                    appNameHint = repo
                )
            }
        }

        if (host == "f-droid.org" || host == "www.f-droid.org") {
            val pkg = path.substringAfter("packages/", "").trim('/').substringBefore('/').ifEmpty {
                trimmed.substringAfterLast('/').substringBefore('?')
            }
            return SourceDescriptorDto(
                sourceType = "FDROID",
                packageIdHint = pkg,
                canonicalUrl = "https://f-droid.org/packages/$pkg/",
                apiUrl = "https://f-droid.org/api/v1/packages/$pkg",
                appNameHint = pkg
            )
        }

        if (lower.endsWith("index-v1.json") || lower.endsWith("index-v2.json") ||
            (path.contains("fdroid/repo") && trimmed.contains('#'))
        ) {
            val baseRepo = trimmed.substringBefore('#').trimEnd('/')
            val pkg = trimmed.substringAfter('#', "")
            val indexUrl = if (baseRepo.endsWith(".json", ignoreCase = true)) baseRepo else "$baseRepo/index-v2.json"
            return SourceDescriptorDto(
                sourceType = "FDROID_REPO",
                packageIdHint = pkg,
                canonicalUrl = trimmed,
                apiUrl = indexUrl,
                appNameHint = pkg.ifEmpty { baseRepo.substringAfterLast('/') }
            )
        }

        if (host == "apt.izzysoft.de") {
            val pkg = path.substringAfter("apk/", "").trim('/').substringBefore('/')
            return SourceDescriptorDto(
                sourceType = "IZZYONDROID",
                packageIdHint = pkg,
                canonicalUrl = "https://apt.izzysoft.de/fdroid/index/apk/$pkg",
                apiUrl = "https://apt.izzysoft.de/fdroid/api/v1/packages/$pkg",
                appNameHint = pkg
            )
        }

        if (host.endsWith("aptoide.com")) {
            val isPkg = path.startsWith("app/")
            val key = if (isPkg) path.removePrefix("app/").substringBefore('/') else host.substringBefore('.')
            val apiUrl = if (isPkg) {
                "https://ws75.aptoide.com/api/7/app/getMeta?package_name=$key"
            } else {
                "https://ws75.aptoide.com/api/7/apps/search?query=$key&limit=10"
            }
            return SourceDescriptorDto(
                sourceType = "APTOIDE",
                packageIdHint = if (isPkg) key else "",
                canonicalUrl = trimmed,
                apiUrl = apiUrl,
                appNameHint = key
            )
        }

        if (host.endsWith("rustore.ru")) {
            val pkg = path.substringAfter("app/", "").trim('/').substringBefore('/')
            return SourceDescriptorDto(
                sourceType = "RUSTORE",
                packageIdHint = pkg,
                canonicalUrl = "https://www.rustore.ru/catalog/app/$pkg",
                apiUrl = "https://backapi.rustore.ru/applicationData/overallInfo/$pkg",
                appNameHint = pkg
            )
        }

        if (host == "sourceforge.net" || host == "www.sourceforge.net") {
            val project = path.substringAfter("projects/", "").substringBefore('/')
            return SourceDescriptorDto(
                sourceType = "SOURCEFORGE",
                repo = project,
                canonicalUrl = "https://sourceforge.net/projects/$project/",
                apiUrl = "https://sourceforge.net/projects/$project/rss?path=/",
                appNameHint = project
            )
        }

        if (path.contains("job/") || lower.contains("jenkins")) {
            val jobBase = trimmed.substringBefore("/lastSuccessfulBuild").trimEnd('/')
            return SourceDescriptorDto(
                sourceType = "JENKINS",
                canonicalUrl = jobBase,
                apiUrl = "$jobBase/lastSuccessfulBuild/api/json",
                appNameHint = jobBase.substringAfterLast('/')
            )
        }

        return SourceDescriptorDto(
            sourceType = "HTML",
            canonicalUrl = trimmed,
            apiUrl = trimmed,
            appNameHint = trimmed.substringAfterLast('/').ifEmpty { host }
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

    private fun defaultDeviceAbis(): List<String> {
        return runCatching { Build.SUPPORTED_ABIS?.toList().orEmpty() }.getOrDefault(emptyList())
    }

    fun inspectApk(
        apkPath: String,
        deviceAbis: List<String> = defaultDeviceAbis()
    ): ApkIntegrityReportDto {
        val abisCsv = deviceAbis.joinToString(",")
        if (isLoaded) {
            runCatching {
                val raw = inspectApkFileJson(apkPath, abisCsv)
                return json.decodeFromString<ApkIntegrityReportDto>(raw)
            }
        }
        return inspectApkFallbackStrict(apkPath, deviceAbis)
    }

    private fun inspectApkFallbackStrict(
        apkPath: String,
        deviceAbis: List<String>
    ): ApkIntegrityReportDto {
        val file = java.io.File(apkPath)
        if (!file.exists() || !file.isFile || file.length() < 22L) {
            return ApkIntegrityReportDto(
                validApkZip = false,
                abiCompatible = false,
                summary = "APK file missing or too small",
                error = "File missing or truncated"
            )
        }

        val sha256Hex = runCatching {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(64 * 1024)
            file.inputStream().use { input ->
                while (true) {
                    val read = input.read(buf)
                    if (read <= 0) break
                    md.update(buf, 0, read)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        }.getOrDefault("")

        val hasV2V3 = runCatching { hasApkSigningBlockV2V3(file) }.getOrDefault(false)

        return try {
            java.util.zip.ZipFile(file).use { zip ->
                var hasManifest = false
                var hasMetaMf = false
                var hasMetaCert = false
                var entriesCount = 0
                val abis = sortedSetOf<String>()

                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    entriesCount++
                    val name = entries.nextElement().name
                    when {
                        name == "AndroidManifest.xml" -> hasManifest = true
                        name.equals("META-INF/MANIFEST.MF", ignoreCase = true) -> hasMetaMf = true
                        name.startsWith("META-INF/") && !name.substringAfter("META-INF/").contains('/') -> {
                            val upper = name.uppercase()
                            if (upper.endsWith(".RSA") || upper.endsWith(".DSA") || upper.endsWith(".EC")) {
                                hasMetaCert = true
                            }
                        }
                        name.startsWith("lib/") -> {
                            val parts = name.split('/')
                            if (parts.size == 3 && parts[1].isNotEmpty() && parts[2].endsWith(".so") && parts[2].length > 3) {
                                abis.add(parts[1])
                            }
                        }
                    }
                }

                val hasV1 = hasMetaMf && hasMetaCert
                val hasSig = hasV2V3 || hasV1
                val abiList = abis.toList()
                val abiOk = abiList.isEmpty() || deviceAbis.isEmpty() ||
                    abiList.any { a -> deviceAbis.any { d -> d.equals(a, ignoreCase = true) } }

                ApkIntegrityReportDto(
                    validApkZip = true,
                    hasManifest = hasManifest,
                    hasSignatureBlock = hasSig,
                    hasV2V3Signature = hasV2V3,
                    hasV1Signature = hasV1,
                    entriesCount = entriesCount,
                    sha256Hex = sha256Hex,
                    fileSizeBytes = file.length(),
                    nativeAbis = abiList,
                    abiCompatible = abiOk,
                    summary = when {
                        !hasManifest -> "Missing AndroidManifest.xml in APK"
                        !hasSig -> "Missing v1/v2/v3 APK signature block"
                        !abiOk -> "ABI mismatch: $abiList vs $deviceAbis"
                        abiList.isEmpty() -> "Verified pure DEX/universal signed APK"
                        else -> "Verified signed native APK (${abiList.joinToString(", ")})"
                    }
                )
            }
        } catch (t: Throwable) {
            ApkIntegrityReportDto(
                validApkZip = false,
                sha256Hex = sha256Hex,
                fileSizeBytes = file.length(),
                abiCompatible = false,
                summary = "Corrupt or truncated APK ZIP archive: ${t.message ?: t.javaClass.simpleName}",
                error = t.message
            )
        }
    }

    private fun hasApkSigningBlockV2V3(file: java.io.File): Boolean {
        val len = file.length()
        if (len < 22L) return false
        java.io.RandomAccessFile(file, "r").use { raf ->
            val maxSearch = minOf(len, 22L + 65_535L).toInt()
            val tail = ByteArray(maxSearch)
            raf.seek(len - maxSearch)
            raf.readFully(tail)

            var cdOffset = -1L
            for (i in (tail.size - 22) downTo 0) {
                if (tail[i] == 0x50.toByte() && tail[i + 1] == 0x4B.toByte() &&
                    tail[i + 2] == 0x05.toByte() && tail[i + 3] == 0x06.toByte()
                ) {
                    val commentLen = ((tail[i + 21].toInt() and 0xFF) shl 8) or (tail[i + 20].toInt() and 0xFF)
                    if (i + 22 + commentLen == tail.size) {
                        cdOffset = java.nio.ByteBuffer.wrap(tail, i + 16, 4)
                            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                            .int.toLong() and 0xFFFF_FFFFL
                        break
                    }
                }
            }
            if (cdOffset < 32L || cdOffset > len) return false

            val footer = ByteArray(24)
            raf.seek(cdOffset - 24L)
            raf.readFully(footer)
            if (String(footer, 8, 16, Charsets.US_ASCII) != "APK Sig Block 42") return false

            val footerSize = java.nio.ByteBuffer.wrap(footer, 0, 8)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN).long
            if (footerSize < 24L || footerSize > 32L * 1024L * 1024L) return false
            val totalSize = footerSize + 8L
            if (totalSize > cdOffset) return false

            val block = ByteArray(totalSize.toInt())
            raf.seek(cdOffset - totalSize)
            raf.readFully(block)
            val bb = java.nio.ByteBuffer.wrap(block).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            if (bb.getLong(0) != footerSize) return false

            var cursor = 8
            val pairsEnd = block.size - 24
            while (cursor + 8 <= pairsEnd) {
                val pairLen = bb.getLong(cursor)
                cursor += 8
                if (pairLen < 4 || pairLen > pairsEnd - cursor) return false
                val id = bb.getInt(cursor)
                if (pairLen > 4 && (id == 0x7109871a || id == -0x0fac9740 || id == 0x1b93ad61)) {
                    return true
                }
                cursor += pairLen.toInt()
            }
            return false
        }
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
