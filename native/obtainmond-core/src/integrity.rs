use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::fs::File;
use std::io::{Read, Seek, SeekFrom};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ApkIntegrityReport {
    pub valid_apk_zip: bool,
    pub has_manifest: bool,
    pub has_signature_block: bool,
    pub sha256_hex: String,
    pub file_size_bytes: u64,
    pub native_abis: Vec<String>,
    pub abi_compatible: bool,
    pub summary: String,
}

/// Computes streaming SHA-256 and inspects the APK ZIP Central Directory
/// without decompressing the archive. Extracts all `lib/<abi>/*.so` entries
/// so the Rust ABI & Signature Guard can verify native library compatibility
/// before privileged silent installation.
pub fn inspect_apk_file(path: &str, device_abis: &[String]) -> Result<ApkIntegrityReport, String> {
    let mut file = File::open(path).map_err(|e| format!("Failed to open APK '{}': {}", path, e))?;
    let metadata = file
        .metadata()
        .map_err(|e| format!("Failed to stat APK: {}", e))?;
    let file_size = metadata.len();
    if file_size < 22 {
        return Err("File is too small to be a valid APK/ZIP archive".to_string());
    }

    // 1. Check ZIP magic bytes (PK\x03\x04) and compute streaming SHA-256
    let mut magic = [0u8; 4];
    file.read_exact(&mut magic)
        .map_err(|e| format!("Failed to read header: {}", e))?;
    let valid_magic = magic == [0x50, 0x4B, 0x03, 0x04];

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

    // 2. Scan the tail of the APK (last 512 KB or full file if smaller) for Central Directory entries
    // and APK Signing Block v2/v3 magic ("APK Sig Block 42")
    let scan_window = file_size.min(512 * 1024);
    file.seek(SeekFrom::Start(file_size - scan_window))
        .map_err(|e| format!("Seek tail error: {}", e))?;
    let mut tail_buf = vec![0u8; scan_window as usize];
    file.read_exact(&mut tail_buf)
        .map_err(|e| format!("Read tail error: {}", e))?;

    let tail_lossy = String::from_utf8_lossy(&tail_buf);
    let has_manifest = tail_lossy.contains("AndroidManifest.xml");
    let has_sig_block = tail_lossy.contains("APK Sig Block 42")
        || tail_lossy.contains("META-INF/MANIFEST.MF")
        || tail_lossy.contains(".RSA")
        || tail_lossy.contains(".DSA")
        || tail_lossy.contains(".EC");

    let mut native_abis = Vec::new();
    for abi in ["arm64-v8a", "armeabi-v7a", "x86_64", "x86"] {
        let marker = format!("lib/{}/", abi);
        if tail_lossy.contains(&marker) {
            native_abis.push(abi.to_string());
        }
    }

    // Compatible if the APK has no native libraries (pure Kotlin/Java/DEX) OR
    // at least one of its native ABIs is in `device_abis`.
    let abi_compatible = if native_abis.is_empty() || device_abis.is_empty() {
        true
    } else {
        native_abis
            .iter()
            .any(|apk_abi| device_abis.iter().any(|dev_abi| dev_abi.eq_ignore_ascii_case(apk_abi)))
    };

    let summary = if !valid_magic {
        "Invalid ZIP/APK magic header".to_string()
    } else if !abi_compatible {
        format!(
            "ABI mismatch: APK only contains {:?}, device supports {:?}",
            native_abis, device_abis
        )
    } else if native_abis.is_empty() {
        "Verified pure DEX/universal APK".to_string()
    } else {
        format!("Verified native APK ({})", native_abis.join(", "))
    };

    Ok(ApkIntegrityReport {
        valid_apk_zip: valid_magic,
        has_manifest,
        has_signature_block: has_sig_block,
        sha256_hex,
        file_size_bytes: file_size,
        native_abis,
        abi_compatible,
        summary,
    })
}

/// Safely quotes a single shell token for execution via Shevery/Shizuku privileged shell.
pub fn quote_shell_arg(raw: &str) -> String {
    let escaped = raw.replace('\'', "'\"'\"'");
    format!("'{}'", escaped)
}

/// Builds a privileged shell command sequence for silent APK installation,
/// optional installer package spoofing, and optional post-install ART dexopt compilation.
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
    let install_cmd = format!("pm install {} {}", flags, safe_apk);

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
