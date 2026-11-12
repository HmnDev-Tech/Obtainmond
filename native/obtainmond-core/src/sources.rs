use crate::apk_selector::ApkCandidateInput;
use crate::version::normalize_version;
use regex::Regex;
use serde::{Deserialize, Serialize};
use serde_json::Value;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SourceDescriptor {
    pub source_type: String,
    pub canonical_url: String,
    pub api_url: String,
    pub owner: String,
    pub repo: String,
    pub app_name_hint: String,
    pub package_id_hint: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ParsedRelease {
    pub version: String,
    pub raw_tag: String,
    pub release_title: String,
    pub changelog: String,
    pub published_at: String,
    pub is_prerelease: bool,
    pub apk_assets: Vec<ApkCandidateInput>,
}

pub fn resolve_source_descriptor(raw_url: &str) -> SourceDescriptor {
    let trimmed = raw_url.trim().trim_end_matches('/');
    let lower = trimmed.to_ascii_lowercase();

    // 1. GitHub
    if lower.contains("github.com/") {
        let parts: Vec<&str> = trimmed
            .split("github.com/")
            .nth(1)
            .unwrap_or("")
            .split('/')
            .filter(|s| !s.is_empty())
            .collect();
        let owner = parts.first().copied().unwrap_or("unknown").to_string();
        let repo = parts
            .get(1)
            .copied()
            .unwrap_or("unknown")
            .trim_end_matches(".git")
            .to_string();
        return SourceDescriptor {
            source_type: "GITHUB".to_string(),
            canonical_url: format!("https://github.com/{}/{}", owner, repo),
            api_url: format!("https://api.github.com/repos/{}/{}/releases?per_page=15", owner, repo),
            app_name_hint: repo.clone(),
            package_id_hint: format!("com.{}.{}", owner.to_ascii_lowercase().replace('-', ""), repo.to_ascii_lowercase().replace('-', "")),
            owner,
            repo,
        };
    }

    // 2. Codeberg / Forgejo
    if lower.contains("codeberg.org/") {
        let parts: Vec<&str> = trimmed
            .split("codeberg.org/")
            .nth(1)
            .unwrap_or("")
            .split('/')
            .filter(|s| !s.is_empty())
            .collect();
        let owner = parts.first().copied().unwrap_or("unknown").to_string();
        let repo = parts
            .get(1)
            .copied()
            .unwrap_or("unknown")
            .trim_end_matches(".git")
            .to_string();
        return SourceDescriptor {
            source_type: "CODEBERG".to_string(),
            canonical_url: format!("https://codeberg.org/{}/{}", owner, repo),
            api_url: format!("https://codeberg.org/api/v1/repos/{}/{}/releases?limit=15", owner, repo),
            app_name_hint: repo.clone(),
            package_id_hint: format!("org.codeberg.{}.{}", owner.to_ascii_lowercase(), repo.to_ascii_lowercase()),
            owner,
            repo,
        };
    }

    // 3. GitLab
    if lower.contains("gitlab.com/") {
        let path = trimmed.split("gitlab.com/").nth(1).unwrap_or("");
        let clean_path = path.split("/-/").next().unwrap_or(path).trim_end_matches(".git");
        let parts: Vec<&str> = clean_path.split('/').filter(|s| !s.is_empty()).collect();
        let owner = parts.first().copied().unwrap_or("unknown").to_string();
        let repo = parts.last().copied().unwrap_or("unknown").to_string();
        let encoded_project = clean_path.replace('/', "%2F");
        return SourceDescriptor {
            source_type: "GITLAB".to_string(),
            canonical_url: format!("https://gitlab.com/{}", clean_path),
            api_url: format!("https://gitlab.com/api/v4/projects/{}/releases", encoded_project),
            app_name_hint: repo.clone(),
            package_id_hint: format!("com.gitlab.{}.{}", owner.to_ascii_lowercase(), repo.to_ascii_lowercase()),
            owner,
            repo,
        };
    }

    // 4. F-Droid
    if lower.contains("f-droid.org/") {
        let pkg = trimmed
            .split("/packages/")
            .nth(1)
            .unwrap_or("")
            .split('/')
            .next()
            .unwrap_or("")
            .to_string();
        let short_name = pkg.split('.').next_back().unwrap_or(&pkg).to_string();
        return SourceDescriptor {
            source_type: "FDROID".to_string(),
            canonical_url: format!("https://f-droid.org/packages/{}", pkg),
            api_url: format!("https://f-droid.org/api/v1/packages/{}", pkg),
            owner: "F-Droid".to_string(),
            repo: pkg.clone(),
            app_name_hint: short_name,
            package_id_hint: pkg,
        };
    }

    // 5. IzzyOnDroid
    if lower.contains("izzysoft.de/") {
        let pkg = trimmed
            .split("/apk/")
            .nth(1)
            .unwrap_or_else(|| trimmed.split('/').next_back().unwrap_or(""))
            .split('/')
            .next()
            .unwrap_or("")
            .to_string();
        let short_name = pkg.split('.').next_back().unwrap_or(&pkg).to_string();
        return SourceDescriptor {
            source_type: "IZZYONDROID".to_string(),
            canonical_url: format!("https://apt.izzysoft.de/fdroid/index/apk/{}", pkg),
            api_url: format!("https://apt.izzysoft.de/fdroid/ index/apk/{}", pkg).replace(" ", ""),
            owner: "IzzyOnDroid".to_string(),
            repo: pkg.clone(),
            app_name_hint: short_name,
            package_id_hint: pkg,
        };
    }

    // 6. Direct APK or Generic HTML
    let is_direct_apk = lower.ends_with(".apk");
    let last_seg = trimmed
        .split('/')
        .next_back()
        .unwrap_or("App")
        .trim_end_matches(".apk")
        .to_string();
    SourceDescriptor {
        source_type: if is_direct_apk {
            "DIRECT_APK".to_string()
        } else {
            "HTML".to_string()
        },
        canonical_url: trimmed.to_string(),
        api_url: trimmed.to_string(),
        owner: "Direct".to_string(),
        repo: last_seg.clone(),
        app_name_hint: last_seg,
        package_id_hint: String::new(),
    }
}

pub fn parse_release_payload(
    source_type: &str,
    payload: &str,
    include_prereleases: bool,
    fallback_url: &str,
) -> Result<ParsedRelease, String> {
    match source_type.to_ascii_uppercase().as_str() {
        "GITHUB" | "CODEBERG" | "GITEA" => parse_github_or_gitea_releases(payload, include_prereleases),
        "GITLAB" => parse_gitlab_releases(payload, include_prereleases),
        "FDROID" => parse_fdroid_package(payload),
        "DIRECT_APK" => Ok(ParsedRelease {
            version: extract_version_from_url(fallback_url),
            raw_tag: extract_version_from_url(fallback_url),
            release_title: "Direct APK Release".to_string(),
            changelog: "Direct APK endpoint tracked by Obtainmond Rust Engine.".to_string(),
            published_at: String::new(),
            is_prerelease: false,
            apk_assets: vec![ApkCandidateInput {
                name: fallback_url
                    .split('/')
                    .next_back()
                    .unwrap_or("app-release.apk")
                    .to_string(),
                url: fallback_url.to_string(),
                size: 0,
            }],
        }),
        _ => parse_html_or_generic(payload, fallback_url),
    }
}

fn parse_github_or_gitea_releases(payload: &str, include_prereleases: bool) -> Result<ParsedRelease, String> {
    let parsed: Value = serde_json::from_str(payload).map_err(|e| format!("Invalid JSON: {}", e))?;
    let releases = if let Some(arr) = parsed.as_array() {
        arr.clone()
    } else if parsed.is_object() {
        vec![parsed]
    } else {
        return Err("Unexpected release JSON format".to_string());
    };

    for rel in releases {
        let draft = rel.get("draft").and_then(|v| v.as_bool()).unwrap_or(false);
        if draft {
            continue;
        }
        let prerelease = rel
            .get("prerelease")
            .and_then(|v| v.as_bool())
            .unwrap_or(false);
        if prerelease && !include_prereleases {
            continue;
        }

        let mut apk_assets = Vec::new();
        if let Some(assets) = rel.get("assets").and_then(|v| v.as_array()) {
            for asset in assets {
                let name = asset
                    .get("name")
                    .and_then(|v| v.as_str())
                    .unwrap_or("")
                    .to_string();
                let url = asset
                    .get("browser_download_url")
                    .and_then(|v| v.as_str())
                    .unwrap_or("")
                    .to_string();
                let size = asset.get("size").and_then(|v| v.as_u64()).unwrap_or(0);
                if name.to_ascii_lowercase().ends_with(".apk") && !url.is_empty() {
                    apk_assets.push(ApkCandidateInput { name, url, size });
                }
            }
        }

        if apk_assets.is_empty() {
            continue;
        }

        let raw_tag = rel
            .get("tag_name")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .to_string();
        let release_title = rel
            .get("name")
            .and_then(|v| v.as_str())
            .filter(|s| !s.is_empty())
            .unwrap_or(&raw_tag)
            .to_string();
        let version_source = if !raw_tag.is_empty() {
            &raw_tag
        } else {
            &release_title
        };

        return Ok(ParsedRelease {
            version: normalize_version(version_source),
            raw_tag,
            release_title,
            changelog: rel
                .get("body")
                .and_then(|v| v.as_str())
                .unwrap_or("")
                .to_string(),
            published_at: rel
                .get("published_at")
                .and_then(|v| v.as_str())
                .unwrap_or("")
                .to_string(),
            is_prerelease: prerelease,
            apk_assets,
        });
    }

    Err("No release containing .apk assets found".to_string())
}

fn parse_gitlab_releases(payload: &str, include_prereleases: bool) -> Result<ParsedRelease, String> {
    let parsed: Value = serde_json::from_str(payload).map_err(|e| format!("Invalid GitLab JSON: {}", e))?;
    let releases = parsed
        .as_array()
        .ok_or_else(|| "Expected JSON array of GitLab releases".to_string())?;

    for rel in releases {
        let upcoming = rel
            .get("upcoming_release")
            .and_then(|v| v.as_bool())
            .unwrap_or(false);
        if upcoming && !include_prereleases {
            continue;
        }

        let mut apk_assets = Vec::new();
        if let Some(links) = rel
            .get("assets")
            .and_then(|a| a.get("links"))
            .and_then(|l| l.as_array())
        {
            for link in links {
                let url = link
                    .get("direct_asset_url")
                    .or_else(|| link.get("url"))
                    .and_then(|v| v.as_str())
                    .unwrap_or("")
                    .to_string();
                let name = link
                    .get("name")
                    .and_then(|v| v.as_str())
                    .unwrap_or("")
                    .to_string();
                let effective_name = if name.to_ascii_lowercase().ends_with(".apk") {
                    name
                } else if url.to_ascii_lowercase().ends_with(".apk") {
                    url.split('/').next_back().unwrap_or("release.apk").to_string()
                } else {
                    continue;
                };
                apk_assets.push(ApkCandidateInput {
                    name: effective_name,
                    url,
                    size: 0,
                });
            }
        }

        // Also scan markdown description for uploaded .apk links if assets.links was empty
        let description = rel
            .get("description")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .to_string();
        if apk_assets.is_empty() && !description.is_empty() {
            if let Ok(re) = Regex::new(r"(https?://[^\s\)\x22\x27]+\.apk)") {
                for caps in re.captures_iter(&description) {
                    if let Some(m) = caps.get(1) {
                        let url = m.as_str().to_string();
                        let name = url.split('/').next_back().unwrap_or("app.apk").to_string();
                        apk_assets.push(ApkCandidateInput { name, url, size: 0 });
                    }
                }
            }
        }

        if apk_assets.is_empty() {
            continue;
        }

        let raw_tag = rel
            .get("tag_name")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .to_string();
        let release_title = rel
            .get("name")
            .and_then(|v| v.as_str())
            .unwrap_or(&raw_tag)
            .to_string();

        return Ok(ParsedRelease {
            version: normalize_version(&raw_tag),
            raw_tag,
            release_title,
            changelog: description,
            published_at: rel
                .get("released_at")
                .and_then(|v| v.as_str())
                .unwrap_or("")
                .to_string(),
            is_prerelease: upcoming,
            apk_assets,
        });
    }

    Err("No GitLab release with .apk links found".to_string())
}

fn parse_fdroid_package(payload: &str) -> Result<ParsedRelease, String> {
    let parsed: Value = serde_json::from_str(payload).map_err(|e| format!("Invalid F-Droid JSON: {}", e))?;
    let pkg_id = parsed
        .get("packageName")
        .and_then(|v| v.as_str())
        .unwrap_or("app");
    let suggested_ver_code = parsed.get("suggestedVersionCode").and_then(|v| v.as_i64());

    let packages = parsed
        .get("packages")
        .and_then(|v| v.as_array())
        .ok_or_else(|| "Missing packages array in F-Droid API response".to_string())?;

    let chosen = if let Some(code) = suggested_ver_code {
        packages
            .iter()
            .find(|p| p.get("versionCode").and_then(|v| v.as_i64()) == Some(code))
            .or_else(|| packages.first())
    } else {
        packages.first()
    }
    .ok_or_else(|| "No package versions available on F-Droid".to_string())?;

    let ver_name = chosen
        .get("versionName")
        .and_then(|v| v.as_str())
        .unwrap_or("1.0.0")
        .to_string();
    let ver_code = chosen
        .get("versionCode")
        .and_then(|v| v.as_i64())
        .unwrap_or(1);

    let apk_name = format!("{}_{}.apk", pkg_id, ver_code);
    let apk_url = format!("https://f-droid.org/repo/{}", apk_name);

    Ok(ParsedRelease {
        version: normalize_version(&ver_name),
        raw_tag: ver_name.clone(),
        release_title: format!("{} {}", pkg_id, ver_name),
        changelog: format!("F-Droid official build (versionCode {})", ver_code),
        published_at: String::new(),
        is_prerelease: false,
        apk_assets: vec![ApkCandidateInput {
            name: apk_name,
            url: apk_url,
            size: 0,
        }],
    })
}

fn parse_html_or_generic(payload: &str, base_url: &str) -> Result<ParsedRelease, String> {
    let re_apk = Regex::new(r#"(?i)(?:href|src)=["']([^"']+\.apk)["']"#).map_err(|e| e.to_string())?;
    let mut apk_assets = Vec::new();

    let origin = if let Some(idx) = base_url.find("://") {
        let rest = &base_url[idx + 3..];
        let host = rest.split('/').next().unwrap_or("");
        format!("{}://{}", &base_url[..idx], host)
    } else {
        String::new()
    };

    for caps in re_apk.captures_iter(payload) {
        if let Some(m) = caps.get(1) {
            let raw_link = m.as_str();
            let full_url = if raw_link.starts_with("http://") || raw_link.starts_with("https://") {
                raw_link.to_string()
            } else if raw_link.starts_with('/') && !origin.is_empty() {
                format!("{}{}", origin, raw_link)
            } else {
                format!("{}/{}", base_url.trim_end_matches('/'), raw_link)
            };
            let name = full_url
                .split('/')
                .next_back()
                .unwrap_or("release.apk")
                .to_string();
            if !apk_assets.iter().any(|a: &ApkCandidateInput| a.url == full_url) {
                apk_assets.push(ApkCandidateInput {
                    name,
                    url: full_url,
                    size: 0,
                });
            }
        }
    }

    if apk_assets.is_empty() {
        return Err("No .apk download links found on page".to_string());
    }

    let first_name = &apk_assets[0].name;
    let ver = extract_version_from_url(first_name);

    Ok(ParsedRelease {
        version: ver.clone(),
        raw_tag: ver.clone(),
        release_title: format!("Release {}", ver),
        changelog: "Extracted directly by Obtainmond Rust HTML scraper.".to_string(),
        published_at: String::new(),
        is_prerelease: false,
        apk_assets,
    })
}

fn extract_version_from_url(input: &str) -> String {
    if let Ok(re) = Regex::new(r"v?(\d+\.\d+(?:\.\d+)?(?:-[0-9A-Za-z._-]+)?)") {
        if let Some(caps) = re.captures(input) {
            if let Some(m) = caps.get(1) {
                return normalize_version(m.as_str());
            }
        }
    }
    "1.0.0".to_string()
}
