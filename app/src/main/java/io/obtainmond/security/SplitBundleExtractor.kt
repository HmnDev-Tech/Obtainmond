package io.obtainmond.security

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

data class ObbStageEntry(
    val sourceFile: File,
    val targetRelativePath: String
)

data class ExtractedSplitBundle(
    val apkFiles: List<File>,
    val obbEntries: List<ObbStageEntry>,
    val stagingDir: File
)

object SplitBundleExtractor {
    private const val MAX_ENTRIES = 512
    private const val MAX_UNCOMPRESSED_BYTES = 3L * 1024L * 1024L * 1024L // 3 GiB bomb guard

    private val KNOWN_ABI_MARKERS = mapOf(
        "arm64_v8a" to "arm64-v8a",
        "arm64-v8a" to "arm64-v8a",
        "armeabi_v7a" to "armeabi-v7a",
        "armeabi-v7a" to "armeabi-v7a",
        "x86_64" to "x86_64",
        "x86" to "x86"
    )

    fun isSplitBundleFile(file: File): Boolean {
        val lower = file.name.lowercase()
        return lower.endsWith(".apks") ||
            lower.endsWith(".xapk") ||
            lower.endsWith(".apkm") ||
            lower.endsWith(".apk.zip") ||
            lower.endsWith(".zip")
    }

    fun extractBundle(
        bundleFile: File,
        stagingDir: File,
        deviceAbis: List<String>
    ): ExtractedSplitBundle {
        if (stagingDir.exists()) stagingDir.deleteRecursively()
        stagingDir.mkdirs()

        val canonicalRoot = stagingDir.canonicalFile
        val extractedApks = mutableListOf<File>()
        val extractedObbs = mutableListOf<ObbStageEntry>()

        var entryCount = 0
        var totalBytes = 0L

        ZipInputStream(bundleFile.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                entryCount++
                require(entryCount <= MAX_ENTRIES) { "Split bundle exceeds max entry limit ($MAX_ENTRIES)" }

                if (!entry.isDirectory) {
                    val rawName = entry.name.replace('\\', '/')
                    require(!rawName.startsWith("/") && !rawName.split('/').contains("..")) {
                        "Unsafe zip path in bundle: $rawName"
                    }
                    val lower = rawName.lowercase()
                    val isApk = lower.endsWith(".apk")
                    val isObb = lower.endsWith(".obb")

                    if (isApk || isObb) {
                        val safeFlatName = "${entryCount}_" + rawName.substringAfterLast('/')
                            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
                        val outFile = File(canonicalRoot, safeFlatName).canonicalFile
                        require(outFile.path.startsWith(canonicalRoot.path + File.separator)) {
                            "Zip-slip escape rejected: $rawName"
                        }

                        FileOutputStream(outFile).use { fos ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val read = zis.read(buf)
                                if (read <= 0) break
                                totalBytes += read
                                require(totalBytes <= MAX_UNCOMPRESSED_BYTES) {
                                    "Split bundle exceeds maximum safe uncompressed size"
                                }
                                fos.write(buf, 0, read)
                            }
                        }

                        if (isApk) {
                            extractedApks.add(outFile)
                        } else {
                            val normalizedObbRel = if (lower.contains("android/obb/")) {
                                rawName.substring(lower.indexOf("android/obb/"))
                            } else {
                                "Android/obb/${rawName.substringAfterLast('/')}"
                            }
                            extractedObbs.add(ObbStageEntry(outFile, normalizedObbRel))
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        val filteredApks = filterApksForDeviceAbis(extractedApks, deviceAbis)
        require(filteredApks.isNotEmpty()) {
            "No compatible .apk entries found inside split bundle ${bundleFile.name}"
        }

        // Order base/primary APK first cleanly
        val sortedApks = filteredApks.sortedBy { apk ->
            val lower = apk.name.lowercase()
            when {
                lower.contains("base.apk") -> 0
                !lower.contains("split_config") && !lower.contains("config.") -> 1
                else -> 2
            }
        }

        return ExtractedSplitBundle(
            apkFiles = sortedApks,
            obbEntries = extractedObbs,
            stagingDir = canonicalRoot
        )
    }

    fun filterApksForDeviceAbis(apks: List<File>, deviceAbis: List<String>): List<File> {
        if (deviceAbis.isEmpty()) return apks
        val allowed = deviceAbis.map { it.lowercase() }.toSet()

        val kept = mutableListOf<File>()
        val archSpecific = mutableListOf<Pair<File, String>>()

        for (apk in apks) {
            val lower = apk.name.lowercase()
            val detectedArch = KNOWN_ABI_MARKERS.entries
                .sortedByDescending { it.key.length }
                .firstOrNull { (marker, _) ->
                    lower.contains(".$marker.") ||
                        lower.contains("_$marker.") ||
                        lower.contains("-$marker.") ||
                        lower.contains("config.$marker")
                }?.value

            if (detectedArch == null) {
                kept.add(apk)
            } else {
                archSpecific.add(apk to detectedArch)
            }
        }

        // Pick best matching device ABI only (or all compatible ABIs, preferring primary device ABI)
        val preferredArch = deviceAbis.firstOrNull { devAbi ->
            archSpecific.any { it.second.equals(devAbi, ignoreCase = true) }
        }
        for ((apk, arch) in archSpecific) {
            if (preferredArch != null) {
                if (arch.equals(preferredArch, ignoreCase = true)) kept.add(apk)
            } else if (arch.lowercase() in allowed) {
                kept.add(apk)
            }
        }

        return kept.ifEmpty { apks }
    }
}
