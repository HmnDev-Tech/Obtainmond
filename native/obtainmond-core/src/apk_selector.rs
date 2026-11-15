use regex::Regex;
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ApkCandidateInput {
    pub name: String,
    pub url: String,
    #[serde(default)]
    pub size: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ApkSelectionRequest {
    pub candidates: Vec<ApkCandidateInput>,
    pub device_abis: Vec<String>,
    #[serde(default)]
    pub installed_abi: String,
    #[serde(default)]
    pub previous_apk_name: String,
    #[serde(default)]
    pub filter_regex: String,
    #[serde(default)]
    pub exclude_debug: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ScoredCandidate {
    pub name: String,
    pub url: String,
    pub detected_abi: String,
    pub score: i32,
    pub reason: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ApkSelectionResponse {
    pub selected_name: String,
    pub selected_url: String,
    pub detected_abi: String,
    pub score: i32,
    pub confidence: String,
    pub reason: String,
    pub ranked_candidates: Vec<ScoredCandidate>,
}

pub fn is_android_package_filename(name_lower: &str) -> bool {
    if name_lower.ends_with(".apk")
        || name_lower.ends_with(".apks")
        || name_lower.ends_with(".xapk")
        || name_lower.ends_with(".apkm")
        || name_lower.ends_with(".apk.zip")
    {
        return true;
    }
    if name_lower.ends_with(".zip") {
        let excluded = [
            "source", "src", "checksum", "sha256", "sha512", "symbols", "mapping", "proguard",
        ];
        if excluded.iter().any(|bad| name_lower.contains(bad)) {
            return false;
        }
        return name_lower.contains("apk")
            || name_lower.contains("bundle")
            || name_lower.contains("split")
            || name_lower.contains("android");
    }
    false
}

pub fn is_split_bundle_filename(name_lower: &str) -> bool {
    name_lower.ends_with(".apks")
        || name_lower.ends_with(".xapk")
        || name_lower.ends_with(".apkm")
        || name_lower.ends_with(".apk.zip")
        || (name_lower.ends_with(".zip") && is_android_package_filename(name_lower))
}

fn detect_abi_in_filename(name_lower: &str) -> &'static str {
    // Check specific architectures from most specific to least specific
    if name_lower.contains("arm64-v8a")
        || name_lower.contains("arm64_v8a")
        || name_lower.contains("aarch64")
        || name_lower.contains("arm64")
    {
        return "arm64-v8a";
    }
    if name_lower.contains("armeabi-v7a")
        || name_lower.contains("armeabi_v7a")
        || name_lower.contains("armv7a")
        || name_lower.contains("armv7")
        || name_lower.contains("arm32")
        || name_lower.contains("armeabi")
    {
        return "armeabi-v7a";
    }
    if name_lower.contains("x86_64")
        || name_lower.contains("x86-64")
        || name_lower.contains("amd64")
    {
        return "x86_64";
    }
    if name_lower.contains("x86") || name_lower.contains("i686") || name_lower.contains("ia32") {
        return "x86";
    }
    if name_lower.contains("universal")
        || name_lower.contains("all")
        || name_lower.contains("noarch")
        || name_lower.contains("fat")
    {
        return "universal";
    }
    "universal"
}

fn extract_flavor_tokens(name_lower: &str) -> Vec<&'static str> {
    let known_flavors = [
        "foss", "fdroid", "gms", "play", "full", "lite", "mini", "pro", "normal", "standard",
        "modern", "legacy", "spoofed", "root", "shizuku", "release",
    ];
    let mut found = Vec::new();
    for f in known_flavors {
        if name_lower.contains(f) {
            found.push(f);
        }
    }
    found
}

/// Deterministically selects the single best APK for silent background updates.
/// Even when a GitHub/GitLab release contains 10+ APK variants (arm64, armv7, x86_64,
/// universal, debug, wear, tv), this engine scores every candidate against the
/// currently installed package's ABI and flavor so Obtainmond can auto-update in
/// the background without ever nagging the user.
pub fn select_best_apk(req: &ApkSelectionRequest) -> ApkSelectionResponse {
    let user_re = if !req.filter_regex.trim().is_empty() {
        Regex::new(req.filter_regex.trim()).ok()
    } else {
        None
    };

    let prev_lower = req.previous_apk_name.to_ascii_lowercase();
    let prev_flavors = extract_flavor_tokens(&prev_lower);
    let installed_abi_norm = req.installed_abi.trim().to_ascii_lowercase();
    let primary_device_abi = req
        .device_abis
        .first()
        .map(|s| s.to_ascii_lowercase())
        .unwrap_or_else(|| "arm64-v8a".to_string());

    let mut scored: Vec<ScoredCandidate> = Vec::new();

    for cand in &req.candidates {
        let name_lower = cand.name.to_ascii_lowercase();

        // Accept single APKs and split bundles (.apks, .xapk, .apkm, bundle .zip); reject signatures/checksums/sources
        if !is_android_package_filename(&name_lower) {
            continue;
        }

        // Apply optional user regex filter
        if let Some(ref re) = user_re {
            if !re.is_match(&cand.name) {
                continue;
            }
        }

        let mut score: i32 = 100;
        let mut reasons: Vec<String> = Vec::new();

        // Penalize TV / Wear / Auto / Stub variants unless previously installed
        for form_factor in ["wear", "watch", "androidtv", "-tv", "_tv", "automotive"] {
            if name_lower.contains(form_factor) && !prev_lower.contains(form_factor) {
                score -= 2500;
                reasons.push(format!("penalized form-factor '{}'", form_factor));
            }
        }

        // Penalize debug builds unless requested or previously debug
        if name_lower.contains("debug") && !prev_lower.contains("debug") {
            if req.exclude_debug {
                score -= 1500;
                reasons.push("penalized debug variant".to_string());
            } else {
                score -= 400;
            }
        }

        let detected_abi = detect_abi_in_filename(&name_lower);

        // 1. Score against currently installed app's primary ABI (highest priority for in-place updates)
        if !installed_abi_norm.is_empty() && detected_abi == installed_abi_norm {
            score += 1200;
            reasons.push(format!("exact match with installed ABI ({})", detected_abi));
        }

        // 2. Score against device supported ABIs
        if detected_abi == primary_device_abi {
            score += 900;
            reasons.push(format!(
                "matches device primary ABI ({})",
                primary_device_abi
            ));
        } else if let Some(pos) = req
            .device_abis
            .iter()
            .position(|a| a.eq_ignore_ascii_case(detected_abi))
        {
            let abi_bonus = (600_i32 - (pos as i32 * 150)).max(200);
            score += abi_bonus;
            reasons.push(format!(
                "matches device secondary ABI #{} ({})",
                pos + 1,
                detected_abi
            ));
        } else if detected_abi == "universal" {
            score += 650;
            reasons.push("universal / multi-arch APK".to_string());
        } else {
            // Incompatible foreign ABI (e.g. x86_64 on arm64-only device)
            score -= 10_000;
            reasons.push(format!("incompatible foreign ABI ({})", detected_abi));
        }

        // 3. Flavor continuity with previously installed APK filename
        let cand_flavors = extract_flavor_tokens(&name_lower);
        for pf in &prev_flavors {
            if cand_flavors.contains(pf) {
                score += 250;
                reasons.push(format!("preserves flavor '{}'", pf));
            }
        }

        // 4. Prefer release/signed builds and single APK slightly over split archive when equivalent
        if name_lower.contains("release") || name_lower.contains("signed") {
            score += 80;
        }
        if !is_split_bundle_filename(&name_lower) {
            score += 60;
        }

        scored.push(ScoredCandidate {
            name: cand.name.clone(),
            url: cand.url.clone(),
            detected_abi: detected_abi.to_string(),
            score,
            reason: if reasons.is_empty() {
                "standard APK candidate".to_string()
            } else {
                reasons.join(", ")
            },
        });
    }

    scored.sort_by(|a, b| b.score.cmp(&a.score));

    if let Some(best) = scored.first().cloned() {
        let confidence = if best.score >= 1500 {
            "EXACT_INSTALLED_MATCH"
        } else if best.score >= 900 {
            "PRIMARY_ABI_MATCH"
        } else if best.score >= 600 {
            "UNIVERSAL_MATCH"
        } else if best.score > 0 {
            "FALLBACK_MATCH"
        } else {
            "INCOMPATIBLE_ABI"
        };

        ApkSelectionResponse {
            selected_name: best.name,
            selected_url: best.url,
            detected_abi: best.detected_abi,
            score: best.score,
            confidence: confidence.to_string(),
            reason: best.reason,
            ranked_candidates: scored,
        }
    } else {
        ApkSelectionResponse {
            selected_name: String::new(),
            selected_url: String::new(),
            detected_abi: String::new(),
            score: -1,
            confidence: "NONE".to_string(),
            reason: "No valid Android package asset found in release".to_string(),
            ranked_candidates: Vec::new(),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn prefers_installed_abi_over_universal_and_foreign() {
        let req = ApkSelectionRequest {
            candidates: vec![
                ApkCandidateInput {
                    name: "app-x86_64-release.apk".to_string(),
                    url: "https://ex.com/x86_64.apk".to_string(),
                    size: 10,
                },
                ApkCandidateInput {
                    name: "app-universal-release.apk".to_string(),
                    url: "https://ex.com/univ.apk".to_string(),
                    size: 10,
                },
                ApkCandidateInput {
                    name: "app-arm64-v8a-release.apk".to_string(),
                    url: "https://ex.com/arm64.apk".to_string(),
                    size: 10,
                },
            ],
            device_abis: vec!["arm64-v8a".to_string(), "armeabi-v7a".to_string()],
            installed_abi: "arm64-v8a".to_string(),
            previous_apk_name: String::new(),
            filter_regex: String::new(),
            exclude_debug: true,
        };
        let res = select_best_apk(&req);
        assert_eq!(res.selected_name, "app-arm64-v8a-release.apk");
        assert_eq!(res.detected_abi, "arm64-v8a");
        assert_eq!(res.confidence, "EXACT_INSTALLED_MATCH");
    }

    #[test]
    fn accepts_split_bundles_and_rejects_source_zips_and_checksums() {
        let req = ApkSelectionRequest {
            candidates: vec![
                ApkCandidateInput {
                    name: "app-v1.0-source.zip".to_string(),
                    url: "https://ex.com/src.zip".to_string(),
                    size: 10,
                },
                ApkCandidateInput {
                    name: "app-release.apks.sha256".to_string(),
                    url: "https://ex.com/sum".to_string(),
                    size: 10,
                },
                ApkCandidateInput {
                    name: "app-arm64-release.xapk".to_string(),
                    url: "https://ex.com/app.xapk".to_string(),
                    size: 10,
                },
            ],
            device_abis: vec!["arm64-v8a".to_string()],
            installed_abi: String::new(),
            previous_apk_name: String::new(),
            filter_regex: String::new(),
            exclude_debug: true,
        };
        let res = select_best_apk(&req);
        assert_eq!(res.selected_name, "app-arm64-release.xapk");
        assert_eq!(res.ranked_candidates.len(), 1);
    }
}
