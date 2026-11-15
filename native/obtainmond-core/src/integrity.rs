use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::collections::BTreeSet;
use std::fs::File;
use std::io::{BufReader, Read, Seek, SeekFrom};
use zip::ZipArchive;

const APK_SIG_BLOCK_MAGIC: &[u8; 16] = b"APK Sig Block 42";
const APK_SIGNATURE_SCHEME_V2_BLOCK_ID: u32 = 0x7109_871a;
const APK_SIGNATURE_SCHEME_V3_BLOCK_ID: u32 = 0xf053_68c0;
const APK_SIGNATURE_SCHEME_V31_BLOCK_ID: u32 = 0x1b93_ad61;
const MAX_EOCD_SEARCH_BYTES: u64 = 22 + 65_535;
const MAX_APK_SIG_BLOCK_BYTES: u64 = 32 * 1024 * 1024;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ApkIntegrityReport {
    pub valid_apk_zip: bool,
    pub has_manifest: bool,
    pub has_signature_block: bool,
    pub has_v2_v3_signature: bool,
    pub has_v1_signature: bool,
    pub entries_count: usize,
    pub sha256_hex: String,
    pub file_size_bytes: u64,
    pub native_abis: Vec<String>,
    pub abi_compatible: bool,
    pub summary: String,
}

fn find_eocd_cd_offset(file: &mut File, file_size: u64) -> Result<Option<u64>, String> {
    if file_size < 22 {
        return Ok(None);
    }
    let window = file_size.min(MAX_EOCD_SEARCH_BYTES) as usize;
    file.seek(SeekFrom::Start(file_size - window as u64))
        .map_err(|e| format!("Seek EOCD window failed: {}", e))?;
    let mut buf = vec![0u8; window];
    file.read_exact(&mut buf)
        .map_err(|e| format!("Read EOCD window failed: {}", e))?;

    let mut pos = buf.len().saturating_sub(22);
    loop {
        if buf[pos..pos + 4] == [0x50, 0x4B, 0x05, 0x06] {
            let comment_len = u16::from_le_bytes([buf[pos + 20], buf[pos + 21]]) as usize;
            if pos + 22 + comment_len == buf.len() {
                let cd_offset = u32::from_le_bytes([
                    buf[pos + 16],
                    buf[pos + 17],
                    buf[pos + 18],
                    buf[pos + 19],
                ]) as u64;
                return Ok(Some(cd_offset));
            }
        }
        if pos == 0 {
            break;
        }
        pos -= 1;
    }
    Ok(None)
}

fn verify_apk_signing_block_v2_v3(file: &mut File, cd_offset: u64) -> Result<bool, String> {
    if cd_offset < 32 {
        return Ok(false);
    }

    file.seek(SeekFrom::Start(cd_offset - 24))
        .map_err(|e| format!("Seek APK Signing Block footer failed: {}", e))?;
    let mut footer = [0u8; 24];
    file.read_exact(&mut footer)
        .map_err(|e| format!("Read APK Signing Block footer failed: {}", e))?;

    if &footer[8..24] != APK_SIG_BLOCK_MAGIC {
        return Ok(false);
    }

    let footer_size = u64::from_le_bytes(footer[0..8].try_into().map_err(|_| "slice error")?);
    if footer_size < 24 || footer_size > MAX_APK_SIG_BLOCK_BYTES {
        return Ok(false);
    }

    let total_size = match footer_size.checked_add(8) {
        Some(v) => v,
        None => return Ok(false),
    };
    if total_size > cd_offset {
        return Ok(false);
    }
    let block_start = cd_offset - total_size;

    file.seek(SeekFrom::Start(block_start))
        .map_err(|e| format!("Seek APK Signing Block header failed: {}", e))?;
    let mut block_buf = vec![0u8; total_size as usize];
    file.read_exact(&mut block_buf)
        .map_err(|e| format!("Read APK Signing Block failed: {}", e))?;

    let header_size = u64::from_le_bytes(block_buf[0..8].try_into().map_err(|_| "slice error")?);
    if header_size != footer_size {
        return Ok(false);
    }

    let pairs_end = block_buf.len().saturating_sub(24);
    let mut cursor = 8usize;
    let mut found_v2_or_v3 = false;

    while cursor + 8 <= pairs_end {
        let pair_len = u64::from_le_bytes(
            block_buf[cursor..cursor + 8]
                .try_into()
                .map_err(|_| "slice error")?,
        );
        cursor += 8;
        if pair_len < 4 {
            return Ok(false);
        }
        let pair_len_usize = match usize::try_from(pair_len) {
            Ok(v) => v,
            Err(_) => return Ok(false),
        };
        let next = match cursor.checked_add(pair_len_usize) {
            Some(v) if v <= pairs_end => v,
            _ => return Ok(false),
        };
        let id = u32::from_le_bytes(
            block_buf[cursor..cursor + 4]
                .try_into()
                .map_err(|_| "slice error")?,
        );
        let value_len = pair_len_usize - 4;
        if value_len > 0
            && (id == APK_SIGNATURE_SCHEME_V2_BLOCK_ID
                || id == APK_SIGNATURE_SCHEME_V3_BLOCK_ID
                || id == APK_SIGNATURE_SCHEME_V31_BLOCK_ID)
        {
            found_v2_or_v3 = true;
        }
        cursor = next;
    }

    if cursor != pairs_end {
        return Ok(false);
    }

    Ok(found_v2_or_v3)
}

fn is_v1_signature_entry(name: &str) -> bool {
    if let Some(rest) = name.strip_prefix("META-INF/") {
        if rest.is_empty() || rest.contains('/') {
            return false;
        }
        let upper = rest.to_ascii_uppercase();
        return upper.ends_with(".RSA") || upper.ends_with(".DSA") || upper.ends_with(".EC");
    }
    false
}

/// Computes streaming SHA-256 and parses the APK ZIP Central Directory and
/// APK Signing Block v2/v3 (plus v1 JAR signature metadata) without trusting
/// raw tail substring scans.
pub fn inspect_apk_file(path: &str, device_abis: &[String]) -> Result<ApkIntegrityReport, String> {
    let mut file = File::open(path).map_err(|e| format!("Failed to open APK '{}': {}", path, e))?;
    let metadata = file
        .metadata()
        .map_err(|e| format!("Failed to stat APK: {}", e))?;
    let file_size = metadata.len();

    file.seek(SeekFrom::Start(0))
        .map_err(|e| format!("Seek error: {}", e))?;
    let mut hasher = Sha256::new();
    let mut buf = vec![0u8; 64 * 1024];
    loop {
        let n = file
            .read(&mut buf)
            .map_err(|e| format!("Read error during SHA-256: {}", e))?;
        if n == 0 {
            break;
        }
        hasher.update(&buf[..n]);
    }
    let sha256_hex = hex::encode(hasher.finalize());

    if file_size < 22 {
        return Err("File is too small to be a valid APK/ZIP archive".to_string());
    }

    let cd_offset = find_eocd_cd_offset(&mut file, file_size)?;
    let has_v2_v3_signature = match cd_offset {
        Some(offset) if offset <= file_size => verify_apk_signing_block_v2_v3(&mut file, offset)?,
        _ => false,
    };

    file.seek(SeekFrom::Start(0))
        .map_err(|e| format!("Seek error before ZIP parse: {}", e))?;
    let archive_res = ZipArchive::new(BufReader::new(file));

    let archive = match archive_res {
        Ok(a) => a,
        Err(err) => {
            return Ok(ApkIntegrityReport {
                valid_apk_zip: false,
                has_manifest: false,
                has_signature_block: false,
                has_v2_v3_signature: false,
                has_v1_signature: false,
                entries_count: 0,
                sha256_hex,
                file_size_bytes: file_size,
                native_abis: Vec::new(),
                abi_compatible: false,
                summary: format!("Corrupt or truncated APK ZIP archive: {}", err),
            });
        }
    };

    let entries_count = archive.len();
    let mut has_manifest = false;
    let mut has_meta_manifest = false;
    let mut has_meta_cert = false;
    let mut native_abis_set: BTreeSet<String> = BTreeSet::new();

    for idx in 0..entries_count {
        let name_cow = match archive.name_for_index(idx) {
            Some(Ok(n)) => n,
            Some(Err(err)) => {
                return Ok(ApkIntegrityReport {
                    valid_apk_zip: false,
                    has_manifest: false,
                    has_signature_block: false,
                    has_v2_v3_signature: false,
                    has_v1_signature: false,
                    entries_count: 0,
                    sha256_hex,
                    file_size_bytes: file_size,
                    native_abis: Vec::new(),
                    abi_compatible: false,
                    summary: format!("Corrupt ZIP entry name at index {}: {}", idx, err),
                });
            }
            None => continue,
        };
        let name = name_cow.as_ref();
        if name == "AndroidManifest.xml" {
            has_manifest = true;
        } else if name.eq_ignore_ascii_case("META-INF/MANIFEST.MF") {
            has_meta_manifest = true;
        } else if is_v1_signature_entry(name) {
            has_meta_cert = true;
        } else if let Some(rest) = name.strip_prefix("lib/") {
            let mut parts = rest.split('/');
            if let (Some(abi), Some(so_name), None) = (parts.next(), parts.next(), parts.next()) {
                if !abi.is_empty() && so_name.ends_with(".so") && so_name.len() > 3 {
                    native_abis_set.insert(abi.to_string());
                }
            }
        }
    }

    let has_v1_signature = has_meta_manifest && has_meta_cert;
    let has_signature_block = has_v2_v3_signature || has_v1_signature;
    let native_abis: Vec<String> = native_abis_set.into_iter().collect();

    let abi_compatible = if native_abis.is_empty() || device_abis.is_empty() {
        true
    } else {
        native_abis.iter().any(|apk_abi| {
            device_abis
                .iter()
                .any(|dev_abi| dev_abi.eq_ignore_ascii_case(apk_abi))
        })
    };

    let summary = if !has_manifest {
        "Missing AndroidManifest.xml in APK Central Directory".to_string()
    } else if !has_signature_block {
        "APK has no valid v1/v2/v3 signature block".to_string()
    } else if !abi_compatible {
        format!(
            "ABI mismatch: APK only contains {:?}, device supports {:?}",
            native_abis, device_abis
        )
    } else if native_abis.is_empty() {
        "Verified pure DEX/universal signed APK".to_string()
    } else {
        format!("Verified signed native APK ({})", native_abis.join(", "))
    };

    Ok(ApkIntegrityReport {
        valid_apk_zip: true,
        has_manifest,
        has_signature_block,
        has_v2_v3_signature,
        has_v1_signature,
        entries_count,
        sha256_hex,
        file_size_bytes: file_size,
        native_abis,
        abi_compatible,
        summary,
    })
}

pub fn quote_shell_arg(raw: &str) -> String {
    let escaped = raw.replace('\'', "'\"'\"'");
    format!("'{}'", escaped)
}

pub fn build_silent_install_command(
    apk_path: &str,
    installer_package: &str,
    allow_downgrade: bool,
    grant_runtime_permissions: bool,
    target_package_for_dexopt: &str,
) -> String {
    let mut flags = String::from("-r");
    if allow_downgrade {
        flags.push_str(" -d");
    }
    if grant_runtime_permissions {
        flags.push_str(" -g");
    }
    if !installer_package.trim().is_empty() {
        flags.push_str(" -i ");
        flags.push_str(&quote_shell_arg(installer_package.trim()));
    }

    let safe_apk = quote_shell_arg(apk_path);
    let install_cmd = format!(
        "(if [ \"$(getprop ro.build.version.sdk 2>/dev/null || echo 0)\" -ge 34 ]; then pm install --bypass-low-target-sdk-block {} {} 2>/dev/null || pm install {} {}; else pm install {} {}; fi)",
        flags, safe_apk, flags, safe_apk, flags, safe_apk
    );

    if !target_package_for_dexopt.trim().is_empty() {
        let safe_pkg = quote_shell_arg(target_package_for_dexopt.trim());
        format!(
            "{} && cmd package compile -m speed-profile -f {} >/dev/null 2>&1 || true",
            install_cmd, safe_pkg
        )
    } else {
        install_cmd
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;
    use zip::write::SimpleFileOptions;
    use zip::ZipWriter;

    fn temp_apk_path(name: &str) -> std::path::PathBuf {
        let mut p = std::env::temp_dir();
        p.push(format!(
            "obtainmond_test_{}_{}_{}.apk",
            std::process::id(),
            name,
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap_or_default()
                .as_nanos()
        ));
        p
    }

    fn build_zip_bytes(entries: &[(&str, &[u8])]) -> Vec<u8> {
        let cursor = std::io::Cursor::new(Vec::new());
        let mut writer = ZipWriter::new(cursor);
        let opts = SimpleFileOptions::default().compression_method(zip::CompressionMethod::Stored);
        for (name, data) in entries {
            writer.start_file(*name, opts).unwrap();
            writer.write_all(data).unwrap();
        }
        writer.finish().unwrap().into_inner()
    }

    fn inject_v2_sig_block(zip_bytes: &[u8]) -> Vec<u8> {
        // Locate EOCD at end (no ZIP comment)
        let eocd_pos = zip_bytes.len() - 22;
        assert_eq!(
            &zip_bytes[eocd_pos..eocd_pos + 4],
            &[0x50, 0x4B, 0x05, 0x06]
        );
        let cd_offset =
            u32::from_le_bytes(zip_bytes[eocd_pos + 16..eocd_pos + 20].try_into().unwrap())
                as usize;

        // Build one v2 pair: len(8) + id(4) + dummy payload(8)
        let payload = [0xAAu8; 8];
        let pair_len = (4 + payload.len()) as u64;
        let block_size = (8 + pair_len as usize + 8 + 16) as u64; // pairs + footer_len + magic

        let mut sig_block = Vec::new();
        sig_block.extend_from_slice(&block_size.to_le_bytes());
        sig_block.extend_from_slice(&pair_len.to_le_bytes());
        sig_block.extend_from_slice(&APK_SIGNATURE_SCHEME_V2_BLOCK_ID.to_le_bytes());
        sig_block.extend_from_slice(&payload);
        sig_block.extend_from_slice(&block_size.to_le_bytes());
        sig_block.extend_from_slice(APK_SIG_BLOCK_MAGIC);

        let mut out = Vec::with_capacity(zip_bytes.len() + sig_block.len());
        out.extend_from_slice(&zip_bytes[..cd_offset]);
        out.extend_from_slice(&sig_block);
        let new_cd_offset = (cd_offset + sig_block.len()) as u32;
        out.extend_from_slice(&zip_bytes[cd_offset..]);
        let new_eocd_pos = out.len() - 22;
        out[new_eocd_pos + 16..new_eocd_pos + 20].copy_from_slice(&new_cd_offset.to_le_bytes());
        out
    }

    #[test]
    fn valid_v2_signed_native_apk_passes_central_directory_and_sig_check() {
        let zip = build_zip_bytes(&[
            ("AndroidManifest.xml", b"manifest-bytes"),
            ("classes.dex", b"dex-bytes"),
            ("lib/arm64-v8a/libnative.so", b"elf"),
        ]);
        let apk = inject_v2_sig_block(&zip);
        let path = temp_apk_path("valid_v2");
        std::fs::write(&path, &apk).unwrap();

        let report = inspect_apk_file(path.to_str().unwrap(), &["arm64-v8a".to_string()]).unwrap();
        let _ = std::fs::remove_file(&path);

        assert!(report.valid_apk_zip);
        assert!(report.has_manifest);
        assert!(report.has_v2_v3_signature);
        assert!(report.has_signature_block);
        assert!(report.abi_compatible);
        assert_eq!(report.native_abis, vec!["arm64-v8a".to_string()]);
        assert_eq!(report.entries_count, 3);
    }

    #[test]
    fn truncated_apk_fails_valid_apk_zip_even_with_pk_header() {
        let zip = build_zip_bytes(&[("AndroidManifest.xml", b"manifest")]);
        let truncated = &zip[..zip.len() / 2];
        let path = temp_apk_path("truncated");
        std::fs::write(&path, truncated).unwrap();

        let report = inspect_apk_file(path.to_str().unwrap(), &["arm64-v8a".to_string()]).unwrap();
        let _ = std::fs::remove_file(&path);

        assert!(!report.valid_apk_zip);
        assert!(!report.has_manifest);
        assert!(!report.has_signature_block);
        assert!(!report.abi_compatible);
    }

    #[test]
    fn unsigned_zip_with_fake_tail_text_is_rejected() {
        let mut zip = build_zip_bytes(&[
            ("AndroidManifest.xml", b"manifest"),
            ("assets/decoy.txt", b"APK Sig Block 42 lib/arm64-v8a/"),
        ]);
        // Also ensure no false positive from tail strings
        zip.extend_from_slice(&[]);
        let path = temp_apk_path("unsigned_decoy");
        std::fs::write(&path, &zip).unwrap();

        let report = inspect_apk_file(path.to_str().unwrap(), &["arm64-v8a".to_string()]).unwrap();
        let _ = std::fs::remove_file(&path);

        assert!(report.valid_apk_zip);
        assert!(report.has_manifest);
        assert!(!report.has_signature_block);
        assert!(!report.has_v2_v3_signature);
        assert!(!report.has_v1_signature);
        assert!(report.native_abis.is_empty());
    }

    #[test]
    fn mismatched_native_abi_is_detected() {
        let zip = build_zip_bytes(&[
            ("AndroidManifest.xml", b"manifest"),
            ("META-INF/MANIFEST.MF", b"mf"),
            ("META-INF/CERT.RSA", b"rsa"),
            ("lib/x86_64/libonlyx86.so", b"elf"),
        ]);
        let path = temp_apk_path("abi_mismatch");
        std::fs::write(&path, &zip).unwrap();

        let report = inspect_apk_file(path.to_str().unwrap(), &["arm64-v8a".to_string()]).unwrap();
        let _ = std::fs::remove_file(&path);

        assert!(report.valid_apk_zip);
        assert!(report.has_manifest);
        assert!(report.has_v1_signature);
        assert!(report.has_signature_block);
        assert!(!report.abi_compatible);
        assert_eq!(report.native_abis, vec!["x86_64".to_string()]);
    }
}
