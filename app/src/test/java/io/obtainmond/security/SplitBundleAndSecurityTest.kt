package io.obtainmond.security

import io.obtainmond.data.model.ObtainmondSettings
import io.obtainmond.data.repo.AppRepository
import io.obtainmond.data.repo.InMemorySecretStore
import io.obtainmond.data.repo.StoredSecrets
import io.obtainmond.data.repo.parseTokenPoolInput
import io.obtainmond.privilege.PrivilegeManager
import io.obtainmond.rust.ApkCandidateDto
import io.obtainmond.rust.ObtainmondNative
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SplitBundleAndSecurityTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun writeZip(file: File, entries: List<Pair<String, ByteArray>>) {
        ZipOutputStream(FileOutputStream(file)).use { zos ->
            for ((name, bytes) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
    }

    @Test
    fun splitBundleExtractorSelectsMatchingAbiAndObbs() {
        val xapk = File(tempFolder.root, "sample.xapk")
        writeZip(
            xapk,
            listOf(
                "base.apk" to "base".toByteArray(),
                "config.arm64_v8a.apk" to "arm64".toByteArray(),
                "config.armeabi_v7a.apk" to "arm32".toByteArray(),
                "config.en.apk" to "lang".toByteArray(),
                "Android/obb/com.game.test/main.1.com.game.test.obb" to "obbdata".toByteArray()
            )
        )
        val staging = File(tempFolder.root, "staging")
        val extracted = SplitBundleExtractor.extractBundle(
            bundleFile = xapk,
            stagingDir = staging,
            deviceAbis = listOf("arm64-v8a", "armeabi-v7a")
        )

        val names = extracted.apkFiles.map { it.name }
        assertTrue(names.any { it.contains("base.apk") })
        assertTrue(names.any { it.contains("arm64_v8a") })
        assertTrue(names.any { it.contains("config.en.apk") })
        assertFalse("Non-primary ABI split should be filtered out", names.any { it.contains("armeabi_v7a") })
        assertEquals(1, extracted.obbEntries.size)
        assertEquals("Android/obb/com.game.test/main.1.com.game.test.obb", extracted.obbEntries.first().targetRelativePath)
    }

    @Test(expected = IllegalArgumentException::class)
    fun splitBundleExtractorBlocksZipSlipTraversal() {
        val evil = File(tempFolder.root, "evil.apks")
        writeZip(evil, listOf("../../evil.apk" to "bad".toByteArray()))
        SplitBundleExtractor.extractBundle(
            bundleFile = evil,
            stagingDir = File(tempFolder.root, "staging_evil"),
            deviceAbis = listOf("arm64-v8a")
        )
    }

    @Test
    fun failClosedIntegrityRejectsUnsignedOrCorruptApks() {
        val unsignedApk = File(tempFolder.root, "unsigned.apk")
        writeZip(
            unsignedApk,
            listOf(
                "AndroidManifest.xml" to ByteArray(32) { 0x01 },
                "classes.dex" to ByteArray(64) { 0x02 }
            )
        )
        val unsignedReport = ObtainmondNative.inspectApk(
            apkPath = unsignedApk.absolutePath,
            deviceAbis = listOf("arm64-v8a")
        )
        assertTrue(unsignedReport.validApkZip)
        assertTrue(unsignedReport.hasManifest)
        assertFalse("Unsigned APK must never pass signature inspection", unsignedReport.hasSignatureBlock)

        val corruptApk = File(tempFolder.root, "corrupt.apk")
        corruptApk.writeText("<html>not an apk</html>")
        val corruptReport = ObtainmondNative.inspectApk(
            apkPath = corruptApk.absolutePath,
            deviceAbis = listOf("arm64-v8a")
        )
        assertFalse(corruptReport.validApkZip)
        assertFalse(corruptReport.hasSignatureBlock)

        val sigResult = ApkSignatureVerifier.verifyApkFile(unsignedApk)
        assertFalse(sigResult.isVerified)
        val certError = ApkSignatureVerifier.validateUpdateCertificates(
            verification = sigResult,
            installedCertSha256s = emptyList(),
            pinnedCertSha256 = ""
        )
        assertNotNull("Missing signature must return blocking cert error", certError)
    }

    @Test
    fun verifiedRotationAcceptsAnAuthorizedPastSigner() {
        val result = ApkSignatureVerificationResult(
            isVerified = true,
            signerCertSha256s = listOf("new"),
            authorizedPastSigners = setOf("old")
        )
        assertNull(ApkSignatureVerifier.validateUpdateCertificates(result, listOf("old"), "old"))
        assertNotNull(ApkSignatureVerifier.validateUpdateCertificates(result, listOf("other"), "old"))
        assertNotNull(ApkSignatureVerifier.validateUpdateCertificates(result, listOf("old"), "other"))
    }

    @Test
    fun validateUpdateCertificatesChecksPinAndInstalledCerts() {
        val validSig = ApkSignatureVerificationResult(
            isVerified = true,
            signerCertSha256s = listOf("aabbccdd"),
            verifiedSchemes = listOf("v2", "v3"),
            primaryFingerprint = "aabbccdd"
        )
        assertNull(
            ApkSignatureVerifier.validateUpdateCertificates(
                verification = validSig,
                installedCertSha256s = listOf("AABBCCDD"),
                pinnedCertSha256 = "aabbccdd"
            )
        )
        assertNotNull(
            ApkSignatureVerifier.validateUpdateCertificates(
                verification = validSig,
                installedCertSha256s = listOf("11223344"),
                pinnedCertSha256 = ""
            )
        )
        assertNotNull(
            ApkSignatureVerifier.validateUpdateCertificates(
                verification = validSig,
                installedCertSha256s = emptyList(),
                pinnedCertSha256 = "99887766"
            )
        )
    }

    @Test
    fun inMemorySecretStoreAndTokenPoolParsingWork() {
        val parsed = parseTokenPoolInput("ghp_one, ghp_two\nghp_one\n  ghp_three  ")
        assertEquals(listOf("ghp_one", "ghp_two", "ghp_three"), parsed)

        val store = InMemorySecretStore()
        store.save(StoredSecrets(githubTokens = parsed, gitlabToken = "glpat_abc", virusTotalApiKey = "vt_xyz"))
        val loaded = store.load()
        assertEquals("ghp_one", loaded.primaryGitHubToken())
        assertEquals(3, loaded.githubTokens.size)
        assertEquals("glpat_abc", loaded.gitlabToken)
        assertEquals("vt_xyz", loaded.virusTotalApiKey)
    }

    @Test
    fun customHttpsMirrorOnlyAndNeverDefaultsToHardcodedProxy() {
        val rawUrl = "https://github.com/org/app/releases/download/v1.0/app.apk"
        val defaultSettings = ObtainmondSettings(labGitHubMirrorAcceleration = true)
        assertEquals("", defaultSettings.githubMirrorUrl)
        val pin = "a".repeat(64)
        assertEquals(rawUrl to false, AppRepository.resolveEffectiveDownloadUrl(rawUrl, defaultSettings, pin, false))

        val insecureMirror = ObtainmondSettings(
            labGitHubMirrorAcceleration = true,
            githubMirrorUrl = "http://insecure.mirror.local/"
        )
        assertEquals(rawUrl to false, AppRepository.resolveEffectiveDownloadUrl(rawUrl, insecureMirror, pin, false))

        val validMirror = ObtainmondSettings(
            labGitHubMirrorAcceleration = true,
            githubMirrorUrl = "https://mirror.example.org/"
        )
        assertEquals(
            "https://mirror.example.org/$rawUrl" to true,
            AppRepository.resolveEffectiveDownloadUrl(rawUrl, validMirror, pin, false)
        )
        assertEquals(rawUrl to false, AppRepository.resolveEffectiveDownloadUrl(rawUrl, validMirror, "", false))
        val bundleUrl = rawUrl.removeSuffix(".apk") + ".apks"
        assertEquals(bundleUrl to false, AppRepository.resolveEffectiveDownloadUrl(bundleUrl, validMirror, pin, true))
    }

    @Test
    fun sourceResolverAndSelectorCoverNewSourcesAndBundles() {
        val izzy = ObtainmondNative.resolveSource("https://apt.izzysoft.de/fdroid/index/apk/com.example.izzy")
        assertEquals("IZZYONDROID", izzy.sourceType)
        assertEquals("https://apt.izzysoft.de/fdroid/index/apk/com.example.izzy", izzy.canonicalUrl)
        assertFalse(izzy.canonicalUrl.contains(" /"))

        assertEquals("APTOIDE", ObtainmondNative.resolveSource("https://catima.en.aptoide.com/app").sourceType)
        assertEquals("RUSTORE", ObtainmondNative.resolveSource("https://www.rustore.ru/catalog/app/ru.vk.store").sourceType)
        assertEquals("SOURCEFORGE", ObtainmondNative.resolveSource("https://sourceforge.net/projects/keepass/").sourceType)
        assertEquals("JENKINS", ObtainmondNative.resolveSource("https://ci.example.org/job/ObtainApp/").sourceType)

        val chosen = ObtainmondNative.selectBestApkForDevice(
            listOf(ApkCandidateDto(name = "game-bundle.xapk", url = "https://example.org/game.xapk"))
        )
        assertEquals("game-bundle.xapk", chosen.selectedName)
    }

    @Test
    fun multiSplitRollbackVaultOrdersBaseApkFirstAndSkipsCorruptFiles() {
        val vaultDir = File(tempFolder.root, "vault_com_example")
        vaultDir.mkdirs()
        File(vaultDir, "split_1_arm64.apk").writeBytes(ByteArray(64) { 0x50 })
        File(vaultDir, "base.apk").writeBytes(ByteArray(64) { 0x50 })
        File(vaultDir, "truncated.apk").writeBytes(ByteArray(5))

        val resolved = PrivilegeManager.resolveBackupApkFiles(vaultDir)
        assertEquals(listOf("base.apk", "split_1_arm64.apk"), resolved.map { it.name })
    }
}
