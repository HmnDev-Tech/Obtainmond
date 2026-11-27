package io.obtainmond.security

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.android.apksig.ApkVerifier
import java.io.File
import java.security.MessageDigest
import java.security.cert.Certificate

data class ApkSignatureVerificationResult(
    val isVerified: Boolean,
    val signerCertSha256s: List<String> = emptyList(),
    val verifiedSchemes: List<String> = emptyList(),
    val primaryFingerprint: String = "",
    val error: String? = null,
    val authorizedPastSigners: Set<String> = emptySet()
)

object ApkSignatureVerifier {

    fun verifyApkFile(apkFile: File): ApkSignatureVerificationResult {
        if (!apkFile.exists() || !apkFile.isFile) {
            return ApkSignatureVerificationResult(
                isVerified = false,
                error = "APK file does not exist: ${apkFile.absolutePath}"
            )
        }
        return try {
            val result = ApkVerifier.Builder(apkFile)
                .setMinCheckedPlatformVersion(26)
                .build()
                .verify()

            val certs = result.signerCertificates.orEmpty()
                .map { sha256Hex(it) }
                .distinct()
                .sorted()

            val schemes = buildList {
                if (result.isVerifiedUsingV31Scheme) add("v3.1")
                if (result.isVerifiedUsingV3Scheme) add("v3")
                if (result.isVerifiedUsingV2Scheme) add("v2")
                if (result.isVerifiedUsingV1Scheme) add("v1")
            }

            if (!result.isVerified || certs.isEmpty()) {
                val issue = result.allErrors.firstOrNull()?.toString()
                    ?: "No verified cryptographic signer certificates"
                ApkSignatureVerificationResult(
                    isVerified = false,
                    signerCertSha256s = certs,
                    verifiedSchemes = schemes,
                    error = issue
                )
            } else {
                val lineage = result.signingCertificateLineage
                val pastSigners = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && certs.size == 1 &&
                    lineage?.certificatesInLineage?.lastOrNull()?.let(::sha256Hex) == certs.single()
                ) {
                    val verifiedLineage = requireNotNull(lineage)
                    verifiedLineage.certificatesInLineage.dropLast(1)
                        .filter { verifiedLineage.getSignerCapabilities(it).hasInstalledData() }
                        .map(::sha256Hex).toSet()
                } else {
                    emptySet()
                }
                ApkSignatureVerificationResult(
                    isVerified = true,
                    signerCertSha256s = certs,
                    verifiedSchemes = schemes,
                    primaryFingerprint = certs.first(),
                    authorizedPastSigners = pastSigners
                )
            }
        } catch (t: Throwable) {
            ApkSignatureVerificationResult(
                isVerified = false,
                error = t.message ?: t.javaClass.simpleName
            )
        }
    }

    fun getInstalledPackageCertSha256s(context: Context, packageName: String): List<String> {
        if (packageName.isBlank()) return emptyList()
        val pm = context.packageManager
        val pkgInfo = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(
                    packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
            }
        }.getOrNull() ?: return emptyList()

        return extractCertSha256sFromPackageInfo(pkgInfo)
    }

    fun extractCertSha256sFromPackageInfo(pkgInfo: PackageInfo): List<String> {
        val rawSignatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && pkgInfo.signingInfo != null) {
            pkgInfo.signingInfo?.apkContentsSigners?.toList().orEmpty()
        } else {
            @Suppress("DEPRECATION")
            pkgInfo.signatures?.toList().orEmpty()
        }
        return rawSignatures
            .map { sig -> sha256Hex(sig.toByteArray()) }
            .distinct()
            .sorted()
    }

    fun validateUpdateCertificates(
        verification: ApkSignatureVerificationResult,
        installedCertSha256s: List<String>,
        pinnedCertSha256: String
    ): String? {
        if (!verification.isVerified || verification.signerCertSha256s.isEmpty()) {
            return "APK cryptographic signature verification failed: ${verification.error ?: "unsigned or tampered"}"
        }

        val apkSet = verification.signerCertSha256s.map { it.lowercase() }.toSet()

        if (installedCertSha256s.isNotEmpty()) {
            val installedSet = installedCertSha256s.map { it.lowercase() }.toSet()
            val rotated = installedSet.size == 1 && apkSet.size == 1 &&
                installedSet.single() in verification.authorizedPastSigners
            if (apkSet != installedSet && !rotated) {
                return "Signing certificate mismatch with installed app (installed=${installedSet.first().take(12)}..., downloaded=${apkSet.first().take(12)}...)"
            }
        }

        val normalizedPin = pinnedCertSha256.trim().lowercase()
        if (normalizedPin.isNotEmpty() && normalizedPin !in apkSet && normalizedPin !in verification.authorizedPastSigners) {
            return "Signing certificate mismatch with pinned fingerprint (pinned=${normalizedPin.take(12)}..., downloaded=${apkSet.first().take(12)}...)"
        }

        return null
    }

    fun sha256Hex(cert: Certificate): String = sha256Hex(cert.encoded)

    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { b -> "%02x".format(b) }
    }
}
