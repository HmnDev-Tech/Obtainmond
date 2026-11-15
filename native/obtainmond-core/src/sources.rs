use crate::apk_selector::{is_android_package_filename, ApkCandidateInput};
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

fn split_scheme_host(url: &str) -> (&str, &str, &str) {
    if let Some(idx) = url.find("://") {
        let scheme = &url[..idx];
        let rest = &url[idx + 3..];
        if let Some(slash) = rest.find('/') {
            (scheme, &rest[..slash], &rest[slash..])
        } else {
            (scheme, rest, "")
        }
    } else {
        ("https", url.split('/').next().unwrap_or(""), "")
    }
}

fn is_package_id_segment(seg: &str) -> bool {
    let mut parts = seg.split('.');
    let first = match parts.next() {
        Some(p) if !p.is_empty() && p.chars().all(|c| c.is_ascii_alphanumeric() || c == '_') => p,
        _ => return false,
    };
    if !first
        .chars()
        .next()
        .is_some_and(|c| c.is_ascii_alphabetic())
    {
        return false;
    }
    let mut count = 1usize;
    for part in parts {
        if part.is_empty() || !part.chars().all(|c| c.is_ascii_alphanumeric() || c == '_') {
            return false;
        }
        count += 1;
    }
    count >= 2
}

fn extract_query_param(url: &str, key: &str) -> Option<String> {
    let query = url.split('?').nth(1)?.split('#').next()?;
    for pair in query.split('&') {
        if let Some((k, v)) = pair.split_once('=') {
            if k.eq_ignore_ascii_case(key) && !v.is_empty() {
                return Some(v.to_string());
            }
        }
    }
    None
}

fn extract_fdroid_repo_base_and_pkg(trimmed: &str) -> (String, String) {
    let without_frag = trimmed.split('#').next().unwrap_or(trimmed);
    let frag = trimmed.split('#').nth(1).unwrap_or("").trim();
    let base_no_query = without_frag
        .split('?')
        .next()
        .unwrap_or(without_frag)
        .trim_end_matches('/');

    let query_pkg = extract_query_param(trimmed, "pkg")
        .or_else(|| extract_query_param(trimmed, "id"))
        .or_else(|| (!frag.is_empty() && is_package_id_segment(frag)).then(|| frag.to_string()));

    let base_trimmed = base_no_query
        .trim_end_matches("/index-v1.json")
        .trim_end_matches("/index-v2.json")
        .trim_end_matches("/index.json")
        .trim_end_matches('/');

    let mut pkg = query_pkg.unwrap_or_default();
    let mut repo_base = base_trimmed.to_string();

    if let Some((before_packages, after_packages)) = base_trimmed.split_once("/packages/") {
        let seg = after_packages.split('/').next().unwrap_or("");
        if pkg.is_empty() && is_package_id_segment(seg) {
            pkg = seg.to_string();
        }
        repo_base = format!("{}/repo", before_packages.trim_end_matches('/'));
    } else if let Some(last) = base_trimmed.split('/').next_back() {
        if pkg.is_empty() && is_package_id_segment(last) {
            pkg = last.to_string();
            repo_base = base_trimmed
                .trim_end_matches(last)
                .trim_end_matches('/')
                .to_string();
        }
    }

    (repo_base, pkg)
}

pub fn resolve_source_descriptor(raw_url: &str) -> SourceDescriptor {
    let trimmed = raw_url.trim().trim_end_matches('/');
    let lower = trimmed.to_ascii_lowercase();
    let (scheme, host, path) = split_scheme_host(trimmed);
    let host_lower = host.to_ascii_lowercase();

    // 1. GitHub
    if host_lower == "github.com" || host_lower.ends_with(".github.com") {
        let parts: Vec<&str> = path.split('/').filter(|s| !s.is_empty()).collect();
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
            api_url: format!(
                "https://api.github.com/repos/{}/{}/releases?per_page=100",
                owner, repo
            ),
            app_name_hint: repo.clone(),
            package_id_hint: format!(
                "com.{}.{}",
                owner.to_ascii_lowercase().replace('-', ""),
                repo.to_ascii_lowercase().replace('-', "")
            ),
            owner,
            repo,
        };
    }

    // 2. Codeberg / Forgejo / Gitea
    if host_lower == "codeberg.org"
        || host_lower == "forgejo.org"
        || host_lower == "gitea.com"
        || host_lower.starts_with("git.")
        || host_lower.starts_with("gitea.")
        || host_lower.starts_with("forgejo.")
    {
        let parts: Vec<&str> = path.split('/').filter(|s| !s.is_empty()).collect();
        let owner = parts.first().copied().unwrap_or("unknown").to_string();
        let repo = parts
            .get(1)
            .copied()
            .unwrap_or("unknown")
            .trim_end_matches(".git")
            .to_string();
        let stype = if host_lower.contains("forgejo") {
            "FORGEJO"
        } else if host_lower.contains("gitea") || host_lower.starts_with("git.") {
            "GITEA"
        } else {
            "CODEBERG"
        };
        return SourceDescriptor {
            source_type: stype.to_string(),
            canonical_url: format!("{}://{}/{}/{}", scheme, host, owner, repo),
            api_url: format!(
                "{}://{}/api/v1/repos/{}/{}/releases?limit=50",
                scheme, host, owner, repo
            ),
            app_name_hint: repo.clone(),
            package_id_hint: format!(
                "org.codeberg.{}.{}",
                owner.to_ascii_lowercase(),
                repo.to_ascii_lowercase()
            ),
            owner,
            repo,
        };
    }

    // 3. GitLab (gitlab.com and self-hosted gitlab.* instances)
    if host_lower == "gitlab.com" || host_lower.starts_with("gitlab.") {
        let clean_path = path
            .trim_start_matches('/')
            .split("/-/")
            .next()
            .unwrap_or("")
            .trim_end_matches(".git");
        let parts: Vec<&str> = clean_path.split('/').filter(|s| !s.is_empty()).collect();
        let owner = parts.first().copied().unwrap_or("unknown").to_string();
        let repo = parts.last().copied().unwrap_or("unknown").to_string();
        let encoded_project = clean_path.replace('/', "%2F");
        return SourceDescriptor {
            source_type: "GITLAB".to_string(),
            canonical_url: format!("{}://{}/{}", scheme, host, clean_path),
            api_url: format!(
                "{}://{}/api/v4/projects/{}/releases",
                scheme, host, encoded_project
            ),
            app_name_hint: repo.clone(),
            package_id_hint: format!(
                "com.gitlab.{}.{}",
                owner.to_ascii_lowercase(),
                repo.to_ascii_lowercase()
            ),
            owner,
            repo,
        };
    }

    // 4. Official F-Droid
    if host_lower == "f-droid.org" || host_lower == "www.f-droid.org" {
        let pkg = trimmed
            .split("/packages/")
            .nth(1)
            .unwrap_or("")
            .split('/')
            .next()
            .unwrap_or("")
            .split('?')
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
    if host_lower.contains("izzysoft.de") {
        let without_q = trimmed.split('?').next().unwrap_or(trimmed);
        let pkg = without_q
            .split("/apk/")
            .nth(1)
            .or_else(|| without_q.split("/packages/").nth(1))
            .unwrap_or_else(|| without_q.split('/').next_back().unwrap_or(""))
            .split('/')
            .next()
            .unwrap_or("")
            .to_string();
        let short_name = pkg.split('.').next_back().unwrap_or(&pkg).to_string();
        return SourceDescriptor {
            source_type: "IZZYONDROID".to_string(),
            canonical_url: format!("https://apt.izzysoft.de/fdroid/index/apk/{}", pkg),
            api_url: format!("https://apt.izzysoft.de/fdroid/index/apk/{}", pkg),
            owner: "IzzyOnDroid".to_string(),
            repo: pkg.clone(),
            app_name_hint: short_name,
            package_id_hint: pkg,
        };
    }

    // 6. Third-Party F-Droid Repositories
    if (lower.contains("/fdroid/repo")
        || lower.ends_with("/index-v1.json")
        || lower.ends_with("/index-v2.json")
        || host_lower.starts_with("fdroid.")
        || lower.contains("/fdroid/"))
        && !lower.ends_with(".apk")
    {
        let (repo_base, pkg) = extract_fdroid_repo_base_and_pkg(trimmed);
        let short_name = if pkg.is_empty() {
            host.split('.').next().unwrap_or("F-Droid Repo").to_string()
        } else {
            pkg.split('.').next_back().unwrap_or(&pkg).to_string()
        };
        let index_url =
            if repo_base.ends_with("/index-v1.json") || repo_base.ends_with("/index-v2.json") {
                repo_base.clone()
            } else {
                format!("{}/index-v1.json", repo_base.trim_end_matches('/'))
            };
        return SourceDescriptor {
            source_type: "FDROID_REPO".to_string(),
            canonical_url: trimmed.to_string(),
            api_url: index_url,
            owner: host.to_string(),
            repo: if pkg.is_empty() {
                host.to_string()
            } else {
                pkg.clone()
            },
            app_name_hint: short_name,
            package_id_hint: pkg,
        };
    }

    // 7. Aptoide
    if host_lower == "aptoide.com" || host_lower.ends_with(".aptoide.com") {
        let pkg_param = extract_query_param(trimmed, "package_name")
            .or_else(|| extract_query_param(trimmed, "pkg"));
        let path_pkg = path
            .split('/')
            .filter(|s| !s.is_empty())
            .find(|s| is_package_id_segment(s))
            .map(|s| s.to_string());
        let resolved_pkg = pkg_param.or(path_pkg);

        if let Some(pkg) = resolved_pkg {
            let short = pkg.split('.').next_back().unwrap_or(&pkg).to_string();
            return SourceDescriptor {
                source_type: "APTOIDE".to_string(),
                canonical_url: trimmed.to_string(),
                api_url: format!(
                    "https://ws75.aptoide.com/api/7/app/getMeta?package_name={}",
                    pkg
                ),
                owner: "Aptoide".to_string(),
                repo: pkg.clone(),
                app_name_hint: short,
                package_id_hint: pkg,
            };
        }
        let uname = host_lower.split('.').next().unwrap_or("app").to_string();
        return SourceDescriptor {
            source_type: "APTOIDE".to_string(),
            canonical_url: trimmed.to_string(),
            api_url: format!(
                "https://ws75.aptoide.com/api/7/apps/search?query={}&limit=10",
                uname
            ),
            owner: "Aptoide".to_string(),
            repo: uname.clone(),
            app_name_hint: uname,
            package_id_hint: String::new(),
        };
    }

    // 8. RuStore
    if host_lower == "rustore.ru" || host_lower.ends_with(".rustore.ru") {
        let pkg = path
            .split("/app/")
            .nth(1)
            .unwrap_or_else(|| path.split('/').next_back().unwrap_or(""))
            .split('/')
            .next()
            .unwrap_or("")
            .split('?')
            .next()
            .unwrap_or("")
            .to_string();
        let short = pkg.split('.').next_back().unwrap_or(&pkg).to_string();
        return SourceDescriptor {
            source_type: "RUSTORE".to_string(),
            canonical_url: format!("https://www.rustore.ru/catalog/app/{}", pkg),
            api_url: format!(
                "https://backapi.rustore.ru/applicationData/overallInfo/{}",
                pkg
            ),
            owner: "RuStore".to_string(),
            repo: pkg.clone(),
            app_name_hint: short,
            package_id_hint: pkg,
        };
    }

    // 9. SourceForge
    if host_lower == "sourceforge.net" || host_lower.ends_with(".sourceforge.net") {
        let proj = path
            .split("/projects/")
            .nth(1)
            .unwrap_or("")
            .split('/')
            .next()
            .unwrap_or("app")
            .to_string();
        return SourceDescriptor {
            source_type: "SOURCEFORGE".to_string(),
            canonical_url: format!("https://sourceforge.net/projects/{}", proj),
            api_url: format!("https://sourceforge.net/projects/{}/rss", proj),
            owner: "SourceForge".to_string(),
            repo: proj.clone(),
            app_name_hint: proj,
            package_id_hint: String::new(),
        };
    }

    // 10. Jenkins CI
    if lower.contains("/job/")
        || host_lower.starts_with("ci.")
        || host_lower.starts_with("jenkins.")
    {
        let clean_job = trimmed
            .trim_end_matches("/api/json")
            .trim_end_matches("/lastSuccessfulBuild")
            .trim_end_matches('/');
        let job_name = clean_job
            .split('/')
            .next_back()
            .unwrap_or("jenkins-job")
            .to_string();
        return SourceDescriptor {
            source_type: "JENKINS".to_string(),
            canonical_url: clean_job.to_string(),
            api_url: format!(
                "{}/lastSuccessfulBuild/api/json?tree=number,timestamp,url,fullDisplayName,artifacts[fileName,relativePath]",
                clean_job
            ),
            owner: host.to_string(),
            repo: job_name.clone(),
            app_name_hint: job_name,
            package_id_hint: String::new(),
        };
    }

    // 11. Direct APK/Split Bundle or Generic HTML
    let clean_path_lower = lower.split('?').next().unwrap_or(&lower);
    let is_direct_pkg = is_android_package_filename(clean_path_lower);
    let last_seg = clean_path_lower
        .split('/')
        .next_back()
        .unwrap_or("App")
        .trim_end_matches(".apk")
        .trim_end_matches(".apks")
        .trim_end_matches(".xapk")
        .trim_end_matches(".apkm")
        .to_string();
    SourceDescriptor {
        source_type: if is_direct_pkg {
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
        "GITHUB" | "CODEBERG" | "FORGEJO" | "GITEA" => {
            parse_github_or_gitea_releases(payload, include_prereleases)
        }
        "GITLAB" => parse_gitlab_releases(payload, include_prereleases),
        "FDROID" => parse_fdroid_package(payload, "https://f-droid.org/repo"),
        "FDROID_REPO" => parse_third_party_fdroid_repo(payload, fallback_url),
        "IZZYONDROID" => parse_izzyondroid(payload, fallback_url),
        "APTOIDE" => parse_aptoide(payload, fallback_url),
        "RUSTORE" => parse_rustore(payload),
        "SOURCEFORGE" => parse_sourceforge_rss(payload),
        "JENKINS" => parse_jenkins_json(payload, fallback_url),
        "DIRECT_APK" => {
            let file_name = fallback_url
                .split('?')
                .next()
                .unwrap_or(fallback_url)
                .split('/')
                .next_back()
                .unwrap_or("app-release.apk")
                .to_string();
            let ver = extract_version_from_url(&file_name);
            Ok(ParsedRelease {
                version: ver.clone(),
                raw_tag: ver,
                release_title: "Direct Package Release".to_string(),
                changelog: "Direct APK/bundle endpoint tracked by Obtainmond Rust Engine."
                    .to_string(),
                published_at: String::new(),
                is_prerelease: false,
                apk_assets: vec![ApkCandidateInput {
                    name: file_name,
                    url: fallback_url.to_string(),
                    size: 0,
                }],
            })
        }
        _ => parse_html_or_generic(payload, fallback_url),
    }
}

fn parse_github_or_gitea_releases(
    payload: &str,
    include_prereleases: bool,
) -> Result<ParsedRelease, String> {
    let parsed: Value =
        serde_json::from_str(payload).map_err(|e| format!("Invalid JSON: {}", e))?;
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
                if is_android_package_filename(&name.to_ascii_lowercase()) && !url.is_empty() {
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

    Err("No release containing Android package assets found".to_string())
}

fn parse_gitlab_releases(
    payload: &str,
    include_prereleases: bool,
) -> Result<ParsedRelease, String> {
    let parsed: Value =
        serde_json::from_str(payload).map_err(|e| format!("Invalid GitLab JSON: {}", e))?;
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
                let effective_name = if is_android_package_filename(&name.to_ascii_lowercase()) {
                    name
                } else if is_android_package_filename(
                    &url.split('?').next().unwrap_or(&url).to_ascii_lowercase(),
                ) {
                    url.split('?')
                        .next()
                        .unwrap_or(&url)
                        .split('/')
                        .next_back()
                        .unwrap_or("release.apk")
                        .to_string()
                } else {
                    continue;
                };
                if !url.is_empty() {
                    apk_assets.push(ApkCandidateInput {
                        name: effective_name,
                        url,
                        size: 0,
                    });
                }
            }
        }

        let description = rel
            .get("description")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .to_string();
        if apk_assets.is_empty() && !description.is_empty() {
            if let Ok(re) = Regex::new(r"(?i)(https?://[^\s\)\x22\x27]+\.(?:apk|apks|xapk|apkm))") {
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

    Err("No GitLab release with Android package links found".to_string())
}

fn json_i64_or_string_i64(val: Option<&Value>) -> Option<i64> {
    val.and_then(|v| {
        v.as_i64()
            .or_else(|| v.as_str().and_then(|s| s.trim().parse::<i64>().ok()))
    })
}

fn parse_fdroid_package(payload: &str, repo_base: &str) -> Result<ParsedRelease, String> {
    let parsed: Value =
        serde_json::from_str(payload).map_err(|e| format!("Invalid F-Droid JSON: {}", e))?;
    let pkg_id = parsed
        .get("packageName")
        .and_then(|v| v.as_str())
        .unwrap_or("app");
    let suggested_ver_code = json_i64_or_string_i64(parsed.get("suggestedVersionCode"));

    let packages = parsed
        .get("packages")
        .and_then(|v| v.as_array())
        .ok_or_else(|| "Missing packages array in F-Droid API response".to_string())?;

    let chosen = if let Some(code) = suggested_ver_code {
        packages
            .iter()
            .find(|p| json_i64_or_string_i64(p.get("versionCode")) == Some(code))
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
    let ver_code = json_i64_or_string_i64(chosen.get("versionCode")).unwrap_or(1);

    let apk_name = format!("{}_{}.apk", pkg_id, ver_code);
    let apk_url = format!("{}/{}", repo_base.trim_end_matches('/'), apk_name);

    Ok(ParsedRelease {
        version: normalize_version(&ver_name),
        raw_tag: ver_name.clone(),
        release_title: format!("{} {}", pkg_id, ver_name),
        changelog: format!("F-Droid package build (versionCode {})", ver_code),
        published_at: String::new(),
        is_prerelease: false,
        apk_assets: vec![ApkCandidateInput {
            name: apk_name,
            url: apk_url,
            size: 0,
        }],
    })
}

fn parse_third_party_fdroid_repo(
    payload: &str,
    fallback_url: &str,
) -> Result<ParsedRelease, String> {
    let (repo_base, pkg_hint) = extract_fdroid_repo_base_and_pkg(fallback_url);
    let parsed: Value = serde_json::from_str(payload)
        .map_err(|e| format!("Invalid F-Droid repository JSON: {}", e))?;

    // Support direct api/v1/packages/<pkg> shape too
    if parsed.get("packageName").is_some()
        && parsed.get("packages").and_then(|v| v.as_array()).is_some()
    {
        return parse_fdroid_package(payload, &repo_base);
    }

    let packages_map = parsed
        .get("packages")
        .and_then(|v| v.as_object())
        .ok_or_else(|| "Missing 'packages' object in F-Droid repository index".to_string())?;

    let target_pkg = if !pkg_hint.is_empty() && packages_map.contains_key(&pkg_hint) {
        pkg_hint
    } else if packages_map.len() == 1 {
        packages_map.keys().next().cloned().unwrap_or_default()
    } else if !pkg_hint.is_empty() {
        return Err(format!(
            "Package '{}' not found in F-Droid repository index",
            pkg_hint
        ));
    } else {
        return Err(
            "Multiple packages in F-Droid repository index; specify package ID via ?pkg=<id> or /packages/<id>"
                .to_string(),
        );
    };

    let entry = packages_map
        .get(&target_pkg)
        .ok_or_else(|| "Selected package missing in F-Droid index".to_string())?;

    // index-v1.json: packages[pkg] is an array of version objects
    if let Some(versions_arr) = entry.as_array() {
        let best = versions_arr
            .iter()
            .max_by_key(|v| json_i64_or_string_i64(v.get("versionCode")).unwrap_or(0))
            .ok_or_else(|| "Empty versions list in index-v1.json".to_string())?;
        let ver_name = best
            .get("versionName")
            .and_then(|v| v.as_str())
            .unwrap_or("1.0.0")
            .to_string();
        let ver_code = json_i64_or_string_i64(best.get("versionCode")).unwrap_or(1);
        let apk_name = best
            .get("apkName")
            .and_then(|v| v.as_str())
            .map(|s| s.trim_start_matches('/').to_string())
            .filter(|s| !s.is_empty())
            .unwrap_or_else(|| format!("{}_{}.apk", target_pkg, ver_code));
        let size = best.get("size").and_then(|v| v.as_u64()).unwrap_or(0);
        let apk_url = format!("{}/{}", repo_base.trim_end_matches('/'), apk_name);
        return Ok(ParsedRelease {
            version: normalize_version(&ver_name),
            raw_tag: ver_name.clone(),
            release_title: format!("{} {}", target_pkg, ver_name),
            changelog: format!(
                "Third-party F-Droid v1 index build (versionCode {})",
                ver_code
            ),
            published_at: String::new(),
            is_prerelease: false,
            apk_assets: vec![ApkCandidateInput {
                name: apk_name,
                url: apk_url,
                size,
            }],
        });
    }

    // index-v2.json: packages[pkg].versions is a map of sha256 -> { manifest, file }
    if let Some(versions_obj) = entry.get("versions").and_then(|v| v.as_object()) {
        let best = versions_obj
            .values()
            .max_by_key(|v| {
                json_i64_or_string_i64(v.get("manifest").and_then(|m| m.get("versionCode")))
                    .unwrap_or(0)
            })
            .ok_or_else(|| "Empty versions map in index-v2.json".to_string())?;
        let manifest = best.get("manifest");
        let file = best.get("file");
        let ver_name = manifest
            .and_then(|m| m.get("versionName"))
            .and_then(|v| v.as_str())
            .unwrap_or("1.0.0")
            .to_string();
        let ver_code =
            json_i64_or_string_i64(manifest.and_then(|m| m.get("versionCode"))).unwrap_or(1);
        let rel_path = file
            .and_then(|f| f.get("name"))
            .and_then(|v| v.as_str())
            .map(|s| s.trim_start_matches('/').to_string())
            .filter(|s| !s.is_empty())
            .unwrap_or_else(|| format!("{}_{}.apk", target_pkg, ver_code));
        let size = file
            .and_then(|f| f.get("size"))
            .and_then(|v| v.as_u64())
            .unwrap_or(0);
        let apk_name = rel_path
            .split('/')
            .next_back()
            .unwrap_or(&rel_path)
            .to_string();
        let apk_url = format!("{}/{}", repo_base.trim_end_matches('/'), rel_path);
        return Ok(ParsedRelease {
            version: normalize_version(&ver_name),
            raw_tag: ver_name.clone(),
            release_title: format!("{} {}", target_pkg, ver_name),
            changelog: format!(
                "Third-party F-Droid v2 index build (versionCode {})",
                ver_code
            ),
            published_at: String::new(),
            is_prerelease: false,
            apk_assets: vec![ApkCandidateInput {
                name: apk_name,
                url: apk_url,
                size,
            }],
        });
    }

    Err("Unrecognized F-Droid repository package format".to_string())
}

fn parse_izzyondroid(payload: &str, fallback_url: &str) -> Result<ParsedRelease, String> {
    let trimmed = payload.trim_start();
    if trimmed.starts_with('{') {
        return parse_fdroid_package(payload, "https://apt.izzysoft.de/fdroid/repo");
    }

    // Parse IzzyOnDroid HTML page (`https://apt.izzysoft.de/fdroid/index/apk/<pkg>`)
    let mut apk_assets = Vec::new();
    let re_apk = Regex::new(
        r#"(?i)href=['"]([^'"]*(?:/fdroid/repo/|\b)[^'"]+\.(?:apk|apks|xapk|apkm))['"]"#,
    )
    .map_err(|e| e.to_string())?;
    for caps in re_apk.captures_iter(payload) {
        if let Some(m) = caps.get(1) {
            let link = m.as_str();
            let full_url = if link.starts_with("http://") || link.starts_with("https://") {
                link.to_string()
            } else if link.starts_with('/') {
                format!("https://apt.izzysoft.de{}", link)
            } else {
                format!("https://apt.izzysoft.de/fdroid/index/apk/{}", link)
            };
            let name = full_url
                .split('/')
                .next_back()
                .unwrap_or("izzy-release.apk")
                .to_string();
            if !apk_assets
                .iter()
                .any(|a: &ApkCandidateInput| a.url == full_url)
            {
                apk_assets.push(ApkCandidateInput {
                    name,
                    url: full_url,
                    size: 0,
                });
            }
        }
    }

    if apk_assets.is_empty() {
        return Err("No APK links found on IzzyOnDroid page".to_string());
    }

    // Extract latest Version header: `<h4 ...>Version 2.45.2 (2026-10-20)` or `LastVersion ... 2.45.2`
    let re_ver_header = Regex::new(
        r#"(?i)(?:<h4[^>]*>\s*Version\s+|LastVersion[^>]*>[^<]*?)([0-9]+(?:\.[0-9A-Za-z._-]+)*)"#,
    )
    .ok();
    let extracted_ver = re_ver_header
        .as_ref()
        .and_then(|re| re.captures(payload))
        .and_then(|c| c.get(1))
        .map(|m| normalize_version(m.as_str()))
        .filter(|v| !v.is_empty())
        .unwrap_or_else(|| {
            let pkg_fallback = fallback_url.split('/').next_back().unwrap_or("");
            let asset_name = &apk_assets[0].name;
            if let Some(code) = asset_name
                .strip_prefix(&format!("{}_", pkg_fallback))
                .and_then(|s| s.strip_suffix(".apk"))
            {
                code.to_string()
            } else {
                extract_version_from_url(asset_name)
            }
        });

    Ok(ParsedRelease {
        version: extracted_ver.clone(),
        raw_tag: extracted_ver.clone(),
        release_title: format!("IzzyOnDroid {}", extracted_ver),
        changelog: "Verified IzzyOnDroid repository release.".to_string(),
        published_at: String::new(),
        is_prerelease: false,
        apk_assets: vec![apk_assets.remove(0)],
    })
}

fn parse_aptoide(payload: &str, fallback_url: &str) -> Result<ParsedRelease, String> {
    let parsed: Value =
        serde_json::from_str(payload).map_err(|e| format!("Invalid Aptoide JSON: {}", e))?;

    let app_obj = if let Some(data) = parsed.get("data") {
        data.clone()
    } else if let Some(list) = parsed
        .get("datalist")
        .and_then(|dl| dl.get("list"))
        .and_then(|l| l.as_array())
    {
        let slug = split_scheme_host(fallback_url)
            .1
            .split('.')
            .next()
            .unwrap_or("")
            .to_ascii_lowercase();
        list.iter()
            .find(|item| {
                item.get("uname")
                    .and_then(|v| v.as_str())
                    .is_some_and(|u| u.eq_ignore_ascii_case(&slug))
            })
            .or_else(|| list.first())
            .cloned()
            .ok_or_else(|| "App not found in Aptoide search results".to_string())?
    } else {
        return Err("Unexpected Aptoide JSON structure".to_string());
    };

    let file = app_obj
        .get("file")
        .ok_or_else(|| "Missing 'file' metadata in Aptoide response".to_string())?;
    let path = file
        .get("path")
        .or_else(|| file.get("path_alt"))
        .and_then(|v| v.as_str())
        .filter(|s| !s.is_empty())
        .ok_or_else(|| "Missing APK download path in Aptoide response".to_string())?
        .to_string();
    let ver_raw = file
        .get("vername")
        .and_then(|v| v.as_str())
        .unwrap_or("1.0.0")
        .to_string();
    let ver_clean = ver_raw.split_whitespace().next().unwrap_or(&ver_raw);
    let pkg = app_obj
        .get("package")
        .and_then(|v| v.as_str())
        .unwrap_or("app");
    let size = file.get("filesize").and_then(|v| v.as_u64()).unwrap_or(0);
    let name = path
        .split('/')
        .next_back()
        .map(|s| s.to_string())
        .filter(|s| !s.is_empty())
        .unwrap_or_else(|| format!("{}-{}.apk", pkg, ver_clean));

    Ok(ParsedRelease {
        version: normalize_version(ver_clean),
        raw_tag: ver_raw,
        release_title: app_obj
            .get("name")
            .and_then(|v| v.as_str())
            .unwrap_or(pkg)
            .to_string(),
        changelog: app_obj
            .get("media")
            .and_then(|m| m.get("news"))
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .to_string(),
        published_at: app_obj
            .get("updated")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .to_string(),
        is_prerelease: false,
        apk_assets: vec![ApkCandidateInput {
            name,
            url: path,
            size,
        }],
    })
}

fn parse_rustore(payload: &str) -> Result<ParsedRelease, String> {
    let parsed: Value =
        serde_json::from_str(payload).map_err(|e| format!("Invalid RuStore JSON: {}", e))?;
    let body = parsed
        .get("body")
        .ok_or_else(|| "Missing 'body' in RuStore overallInfo response".to_string())?;
    let app_id = body
        .get("appId")
        .and_then(|v| v.as_i64())
        .ok_or_else(|| "Missing 'appId' in RuStore response".to_string())?;
    let pkg_name = body
        .get("packageName")
        .and_then(|v| v.as_str())
        .unwrap_or("app");
    let ver_name = body
        .get("versionName")
        .and_then(|v| v.as_str())
        .unwrap_or("1.0.0")
        .to_string();
    let ver_code = json_i64_or_string_i64(body.get("versionCode")).unwrap_or(1);
    let size = body.get("fileSize").and_then(|v| v.as_u64()).unwrap_or(0);
    let app_name = body
        .get("appName")
        .and_then(|v| v.as_str())
        .unwrap_or(pkg_name)
        .to_string();
    let whats_new = body
        .get("whatsNew")
        .and_then(|v| v.as_str())
        .unwrap_or("")
        .to_string();

    Ok(ParsedRelease {
        version: normalize_version(&ver_name),
        raw_tag: ver_name.clone(),
        release_title: format!("{} {}", app_name, ver_name),
        changelog: whats_new,
        published_at: body
            .get("appVerUpdatedAt")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .to_string(),
        is_prerelease: false,
        apk_assets: vec![ApkCandidateInput {
            name: format!("{}_{}.apk", pkg_name, ver_code),
            url: format!("rustore-appid://{}/{}", app_id, pkg_name),
            size,
        }],
    })
}

fn parse_sourceforge_rss(payload: &str) -> Result<ParsedRelease, String> {
    let re_item = Regex::new(r"(?is)<item>(.*?)</item>").map_err(|e| e.to_string())?;
    let re_link = Regex::new(r"(?is)<link>(.*?)</link>").ok();
    let re_media = Regex::new(
        r#"(?is)<media:content[^>]*url=["']([^"']+)["'](?:[^>]*filesize=["'](\d+)["'])?"#,
    )
    .ok();
    let re_pub = Regex::new(r"(?is)<pubDate>(.*?)</pubDate>").ok();

    let mut apk_assets = Vec::new();
    let mut published = String::new();

    for cap in re_item.captures_iter(payload) {
        let block = cap.get(1).map(|m| m.as_str()).unwrap_or("");
        let (url, size) = if let Some(mc) = re_media.as_ref().and_then(|r| r.captures(block)) {
            let u = mc
                .get(1)
                .map(|m| m.as_str())
                .unwrap_or("")
                .trim()
                .to_string();
            let s = mc
                .get(2)
                .and_then(|m| m.as_str().parse::<u64>().ok())
                .unwrap_or(0);
            (u, s)
        } else if let Some(lc) = re_link.as_ref().and_then(|r| r.captures(block)) {
            (
                lc.get(1)
                    .map(|m| m.as_str())
                    .unwrap_or("")
                    .trim()
                    .to_string(),
                0,
            )
        } else {
            continue;
        };

        let clean_no_dl = url.trim_end_matches("/download");
        let file_name = clean_no_dl
            .split('?')
            .next()
            .unwrap_or(clean_no_dl)
            .split('/')
            .next_back()
            .unwrap_or("")
            .to_string();
        if !is_android_package_filename(&file_name.to_ascii_lowercase()) {
            continue;
        }
        if published.is_empty() {
            published = re_pub
                .as_ref()
                .and_then(|r| r.captures(block))
                .and_then(|c| c.get(1))
                .map(|m| m.as_str().trim().to_string())
                .unwrap_or_default();
        }
        if !apk_assets.iter().any(|a: &ApkCandidateInput| a.url == url) {
            apk_assets.push(ApkCandidateInput {
                name: file_name,
                url,
                size,
            });
        }
        if apk_assets.len() >= 12 {
            break;
        }
    }

    if apk_assets.is_empty() {
        return Err("No Android package entries found in SourceForge RSS feed".to_string());
    }

    let ver = extract_version_from_url(&apk_assets[0].name);
    Ok(ParsedRelease {
        version: ver.clone(),
        raw_tag: ver.clone(),
        release_title: format!("SourceForge {}", ver),
        changelog: "Parsed from SourceForge project RSS feed.".to_string(),
        published_at: published,
        is_prerelease: false,
        apk_assets,
    })
}

fn parse_jenkins_json(payload: &str, fallback_url: &str) -> Result<ParsedRelease, String> {
    let parsed: Value =
        serde_json::from_str(payload).map_err(|e| format!("Invalid Jenkins JSON: {}", e))?;
    let number = parsed
        .get("number")
        .and_then(|v| v.as_i64())
        .ok_or_else(|| "Missing build number in Jenkins response".to_string())?;
    let build_url = parsed
        .get("url")
        .and_then(|v| v.as_str())
        .map(|s| s.trim_end_matches('/').to_string())
        .filter(|s| !s.is_empty())
        .unwrap_or_else(|| format!("{}/lastSuccessfulBuild", fallback_url.trim_end_matches('/')));

    let mut apk_assets = Vec::new();
    if let Some(artifacts) = parsed.get("artifacts").and_then(|v| v.as_array()) {
        for art in artifacts {
            let file_name = art
                .get("fileName")
                .and_then(|v| v.as_str())
                .unwrap_or("")
                .to_string();
            let rel_path = art
                .get("relativePath")
                .and_then(|v| v.as_str())
                .unwrap_or(&file_name);
            if is_android_package_filename(&file_name.to_ascii_lowercase()) && !rel_path.is_empty()
            {
                let url = format!(
                    "{}/artifact/{}",
                    build_url,
                    rel_path.trim_start_matches('/')
                );
                apk_assets.push(ApkCandidateInput {
                    name: file_name,
                    url,
                    size: 0,
                });
            }
        }
    }

    if apk_assets.is_empty() {
        return Err("No Android package artifacts found in latest Jenkins build".to_string());
    }

    let inferred = extract_version_from_url(&apk_assets[0].name);
    let version = if inferred == "1.0.0" {
        format!("{}", number)
    } else {
        inferred
    };

    Ok(ParsedRelease {
        version: normalize_version(&version),
        raw_tag: format!("#{}", number),
        release_title: parsed
            .get("fullDisplayName")
            .and_then(|v| v.as_str())
            .unwrap_or(&format!("Build #{}", number))
            .to_string(),
        changelog: format!("Jenkins CI build #{}", number),
        published_at: parsed
            .get("timestamp")
            .and_then(|v| v.as_i64())
            .map(|t| t.to_string())
            .unwrap_or_default(),
        is_prerelease: false,
        apk_assets,
    })
}

fn parse_html_or_generic(payload: &str, base_url: &str) -> Result<ParsedRelease, String> {
    let re_apk = Regex::new(r#"(?i)(?:href|src)=["']([^"']+\.(?:apk|apks|xapk|apkm))["']"#)
        .map_err(|e| e.to_string())?;
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
                .split('?')
                .next()
                .unwrap_or(&full_url)
                .split('/')
                .next_back()
                .unwrap_or("release.apk")
                .to_string();
            if !apk_assets
                .iter()
                .any(|a: &ApkCandidateInput| a.url == full_url)
            {
                apk_assets.push(ApkCandidateInput {
                    name,
                    url: full_url,
                    size: 0,
                });
            }
        }
    }

    if apk_assets.is_empty() {
        return Err("No Android package download links found on page".to_string());
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
    let stripped = input
        .trim_end_matches(".apk.zip")
        .trim_end_matches(".apks")
        .trim_end_matches(".xapk")
        .trim_end_matches(".apkm")
        .trim_end_matches(".apk")
        .trim_end_matches(".zip");
    if let Ok(re) = Regex::new(r"v?(\d+\.\d+(?:\.\d+)?(?:-[0-9A-Za-z._-]+)?)") {
        if let Some(caps) = re.captures(stripped) {
            if let Some(m) = caps.get(1) {
                return normalize_version(m.as_str());
            }
        }
    }
    "1.0.0".to_string()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn izzyondroid_descriptor_has_no_space_typo_and_parses_json_and_html() {
        let desc = resolve_source_descriptor(
            "https://apt.izzysoft.de/fdroid/index/apk/me.hackerchick.catima",
        );
        assert_eq!(desc.source_type, "IZZYONDROID");
        assert!(!desc.api_url.contains(' '));
        assert_eq!(
            desc.api_url,
            "https://apt.izzysoft.de/fdroid/index/apk/me.hackerchick.catima"
        );
        assert_eq!(desc.package_id_hint, "me.hackerchick.catima");

        let json_payload = r#"{"packageName":"me.hackerchick.catima","suggestedVersionCode":"1006","packages":[{"versionCode":"1006","versionName":"2.45.2"}]}"#;
        let parsed_json =
            parse_release_payload("IZZYONDROID", json_payload, false, &desc.canonical_url).unwrap();
        assert_eq!(parsed_json.version, "2.45.2");
        assert_eq!(
            parsed_json.apk_assets[0].url,
            "https://apt.izzysoft.de/fdroid/repo/me.hackerchick.catima_1006.apk"
        );

        let html_payload = r#"<html><h4 class='toggle' toggle='pkg1006'>Version 2.45.2 (2026-10-20)</h4><a class='paddedlink' href='/fdroid/repo/me.hackerchick.catima_1006.apk'>Download</a></html>"#;
        let parsed_html =
            parse_release_payload("IZZYONDROID", html_payload, false, &desc.canonical_url).unwrap();
        assert_eq!(parsed_html.version, "2.45.2");
        assert_eq!(
            parsed_html.apk_assets[0].name,
            "me.hackerchick.catima_1006.apk"
        );
    }

    #[test]
    fn third_party_fdroid_repo_parses_v1_and_v2_indexes() {
        let desc =
            resolve_source_descriptor("https://fdroid.example.org/fdroid/repo?pkg=org.example.app");
        assert_eq!(desc.source_type, "FDROID_REPO");
        assert_eq!(
            desc.api_url,
            "https://fdroid.example.org/fdroid/repo/index-v1.json"
        );

        let v1 = r#"{"packages":{"org.example.app":[{"versionCode":10,"versionName":"1.0.0","apkName":"app-10.apk","size":1234},{"versionCode":12,"versionName":"1.2.0","apkName":"app-12.apk","size":4567}]}}"#;
        let r1 = parse_release_payload("FDROID_REPO", v1, false, &desc.canonical_url).unwrap();
        assert_eq!(r1.version, "1.2.0");
        assert_eq!(
            r1.apk_assets[0].url,
            "https://fdroid.example.org/fdroid/repo/app-12.apk"
        );

        let v2 = r#"{"packages":{"org.example.app":{"versions":{"abc":{"manifest":{"versionName":"2.0.0","versionCode":20},"file":{"name":"/org.example.app_20.apk","size":9999}}}}}}"#;
        let r2 = parse_release_payload("FDROID_REPO", v2, false, &desc.canonical_url).unwrap();
        assert_eq!(r2.version, "2.0.0");
        assert_eq!(
            r2.apk_assets[0].url,
            "https://fdroid.example.org/fdroid/repo/org.example.app_20.apk"
        );
    }

    #[test]
    fn aptoide_rustore_sourceforge_and_jenkins_parsers_work() {
        let aptoide_json = r#"{"data":{"name":"Catima","package":"me.hackerchick.catima","file":{"vername":"2.45.2","filesize":5311961,"path":"https://pool.apk.aptoide.com/appupdater/catima.apk"}}}"#;
        let apt = parse_release_payload(
            "APTOIDE",
            aptoide_json,
            false,
            "https://aptoide.com/app/me.hackerchick.catima",
        )
        .unwrap();
        assert_eq!(apt.version, "2.45.2");
        assert_eq!(
            apt.apk_assets[0].url,
            "https://pool.apk.aptoide.com/appupdater/catima.apk"
        );

        let rustore_json = r#"{"code":"OK","body":{"appId":4207807,"packageName":"ru.yandex.taxi","appName":"Yandex Go","versionName":"5.10.0","versionCode":30128766,"fileSize":159888171,"whatsNew":"Bug fixes"}}"#;
        let rs = parse_release_payload(
            "RUSTORE",
            rustore_json,
            false,
            "https://www.rustore.ru/catalog/app/ru.yandex.taxi",
        )
        .unwrap();
        assert_eq!(rs.version, "5.10.0");
        assert_eq!(
            rs.apk_assets[0].url,
            "rustore-appid://4207807/ru.yandex.taxi"
        );

        let sf_rss = r#"<?xml version="1.0"?><rss><channel><item><pubDate>Mon, 01 Jan 2026 00:00:00 GMT</pubDate><media:content url="https://sourceforge.net/projects/foo/files/Foo-3.4.1-arm64.apk/download" filesize="8000000"/></item></channel></rss>"#;
        let sf = parse_release_payload(
            "SOURCEFORGE",
            sf_rss,
            false,
            "https://sourceforge.net/projects/foo",
        )
        .unwrap();
        assert_eq!(sf.version, "3.4.1-arm64");
        assert!(sf.apk_assets[0].url.ends_with(".apk/download"));

        let jenkins_json = r#"{"number":42,"fullDisplayName":"Nightly #42","timestamp":1700000000,"url":"https://ci.example.org/job/App/42/","artifacts":[{"fileName":"app-1.8.0-release.apk","relativePath":"app/build/outputs/apk/app-1.8.0-release.apk"}]}"#;
        let jk = parse_release_payload(
            "JENKINS",
            jenkins_json,
            false,
            "https://ci.example.org/job/App",
        )
        .unwrap();
        assert_eq!(jk.version, "1.8.0");
        assert_eq!(jk.apk_assets[0].url, "https://ci.example.org/job/App/42/artifact/app/build/outputs/apk/app-1.8.0-release.apk");
    }

    #[test]
    fn github_and_gitlab_accept_split_bundles() {
        let gh = r#"[{"tag_name":"v1.5.0","prerelease":false,"draft":false,"body":"Notes","assets":[{"name":"app-release.apks","browser_download_url":"https://github.com/a/b/releases/download/v1.5.0/app-release.apks","size":4096}]}]"#;
        let res = parse_release_payload("GITHUB", gh, false, "https://github.com/a/b").unwrap();
        assert_eq!(res.version, "1.5.0");
        assert_eq!(res.apk_assets[0].name, "app-release.apks");
    }

    #[test]
    fn github_skips_many_prereleases_and_accepts_latest_single_object() {
        let desc = resolve_source_descriptor("https://github.com/Riteshp2001/mpvRx");
        assert_eq!(desc.source_type, "GITHUB");
        assert_eq!(
            desc.api_url,
            "https://api.github.com/repos/Riteshp2001/mpvRx/releases?per_page=100"
        );

        let mut releases = Vec::new();
        for idx in 0..20 {
            releases.push(format!(
                r#"{{"tag_name":"experimental-r22{}","name":"mpvRx Experimental","prerelease":true,"draft":false,"body":"exp","assets":[{{"name":"mpvRx-exp-arm64-v8a.apk","browser_download_url":"https://github.com/Riteshp2001/mpvRx/releases/download/exp/mpvRx-exp-arm64-v8a.apk","size":1000}}]}}"#,
                idx
            ));
        }
        releases.push(
            r###"{"tag_name":"v3.0.2","name":"mpvRx v3.0.2","prerelease":false,"draft":false,"body":"## Changelog\n- Fixes","published_at":"2026-10-01T00:00:00Z","assets":[{"name":"mpvRx-playstore-v3.0.2.aab","browser_download_url":"https://github.com/Riteshp2001/mpvRx/releases/download/v3.0.2/mpvRx-playstore-v3.0.2.aab","size":1000},{"name":"mpvRx-arm64-v8a-v3.0.2.apk","browser_download_url":"https://github.com/Riteshp2001/mpvRx/releases/download/v3.0.2/mpvRx-arm64-v8a-v3.0.2.apk","size":2000},{"name":"mpvRx-universal-v3.0.2.apk","browser_download_url":"https://github.com/Riteshp2001/mpvRx/releases/download/v3.0.2/mpvRx-universal-v3.0.2.apk","size":3000}]}"###.to_string(),
        );
        let payload = format!("[{}]", releases.join(","));

        let parsed = parse_release_payload(
            "GITHUB",
            &payload,
            false,
            "https://github.com/Riteshp2001/mpvRx",
        )
        .unwrap();
        assert_eq!(parsed.version, "3.0.2");
        assert_eq!(parsed.raw_tag, "v3.0.2");
        assert_eq!(parsed.changelog, "## Changelog\n- Fixes");
        assert_eq!(parsed.apk_assets.len(), 2);
        assert!(parsed.apk_assets.iter().all(|a| a.name.ends_with(".apk")));

        // Verify /releases/latest single JSON object fallback format
        let single_latest = r#"{"tag_name":"v3.0.2","name":"mpvRx v3.0.2","prerelease":false,"draft":false,"body":"Single object notes","assets":[{"name":"mpvRx-arm64-v8a-v3.0.2.apk","browser_download_url":"https://github.com/Riteshp2001/mpvRx/releases/download/v3.0.2/mpvRx-arm64-v8a-v3.0.2.apk","size":2000}]}"#;
        let single_parsed = parse_release_payload(
            "GITHUB",
            single_latest,
            false,
            "https://github.com/Riteshp2001/mpvRx",
        )
        .unwrap();
        assert_eq!(single_parsed.version, "3.0.2");
        assert_eq!(single_parsed.changelog, "Single object notes");
    }
}
