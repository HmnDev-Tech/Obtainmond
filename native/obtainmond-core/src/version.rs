use regex::Regex;
use serde::{Deserialize, Serialize};
use std::cmp::Ordering;

#[derive(Debug, Clone, PartialEq, Eq)]
enum Token {
    Num(u64),
    PreRelease(u8), // 1 = alpha/dev/nightly, 2 = beta/pre, 3 = rc
    Text(String),
}

#[derive(Debug, Serialize, Deserialize)]
pub struct VersionComparisonResult {
    pub installed_normalized: String,
    pub remote_normalized: String,
    pub is_update_available: bool,
    pub is_downgrade: bool,
    pub ordering: i32,
}

const COSMETIC_SUFFIXES: &[&str] = &[
    "-release",
    "-debug",
    "-signed",
    "-unsigned",
    "-universal",
    "-arm64-v8a",
    "-armeabi-v7a",
    "-x86_64",
    "-x86",
    "-aarch64",
    "-armv7",
    "-fdroid",
    "-foss",
    "-gms",
    "-play",
    "-github",
    "-gitlab",
    "_release",
    "_signed",
    "_universal",
];

pub fn normalize_version(raw: &str) -> String {
    let trimmed = raw.trim();
    if trimmed.is_empty() {
        return String::new();
    }

    // Strip build metadata (+...)
    let without_build = trimmed.split('+').next().unwrap_or(trimmed).trim();

    // Strip common leading prefixes: v, V, release-, ver-, version-
    let mut s = without_build.to_string();
    let lower = s.to_ascii_lowercase();
    for prefix in &["release-", "version-", "ver-", "rel-", "v.", "v"] {
        if lower.starts_with(prefix) && s.len() > prefix.len() {
            let rest = &s[prefix.len()..];
            if rest
                .chars()
                .next()
                .map(|c| c.is_ascii_digit())
                .unwrap_or(false)
            {
                s = rest.to_string();
                break;
            }
        }
    }

    // Strip cosmetic suffixes repeatedly
    loop {
        let cur_lower = s.to_ascii_lowercase();
        let mut stripped = false;
        for suffix in COSMETIC_SUFFIXES {
            if cur_lower.ends_with(suffix) && s.len() > suffix.len() {
                s.truncate(s.len() - suffix.len());
                stripped = true;
                break;
            }
        }
        if !stripped {
            break;
        }
    }

    s.trim_matches(|c: char| c == '-' || c == '_' || c == '.' || c.is_whitespace())
        .to_string()
}

pub fn extract_version_with_regex(input: &str, pattern: &str) -> String {
    if pattern.trim().is_empty() {
        return normalize_version(input);
    }
    if let Ok(re) = Regex::new(pattern) {
        if let Some(caps) = re.captures(input) {
            if let Some(m) = caps.get(1).or_else(|| caps.get(0)) {
                return normalize_version(m.as_str());
            }
        }
    }
    normalize_version(input)
}

fn tokenize(ver: &str) -> Vec<Token> {
    let norm = normalize_version(ver);
    let mut tokens = Vec::new();
    let mut current_num = String::new();
    let mut current_alpha = String::new();

    let flush_num = |num: &mut String, out: &mut Vec<Token>| {
        if !num.is_empty() {
            if let Ok(v) = num.parse::<u64>() {
                out.push(Token::Num(v));
            }
            num.clear();
        }
    };

    let flush_alpha = |alpha: &mut String, out: &mut Vec<Token>| {
        if !alpha.is_empty() {
            let lower = alpha.to_ascii_lowercase();
            let tok = match lower.as_str() {
                "dev" | "nightly" | "canary" | "alpha" | "a" => Token::PreRelease(1),
                "beta" | "b" | "pre" | "preview" => Token::PreRelease(2),
                "rc" | "cr" => Token::PreRelease(3),
                _ => Token::Text(lower),
            };
            out.push(tok);
            alpha.clear();
        }
    };

    for ch in norm.chars() {
        if ch.is_ascii_digit() {
            flush_alpha(&mut current_alpha, &mut tokens);
            current_num.push(ch);
        } else if ch.is_ascii_alphabetic() {
            flush_num(&mut current_num, &mut tokens);
            current_alpha.push(ch);
        } else {
            flush_num(&mut current_num, &mut tokens);
            flush_alpha(&mut current_alpha, &mut tokens);
        }
    }
    flush_num(&mut current_num, &mut tokens);
    flush_alpha(&mut current_alpha, &mut tokens);

    // Strip trailing zero numeric components for clean comparison (e.g. 1.2.0 == 1.2)
    // unless followed by a PreRelease token.
    tokens
}

pub fn compare_versions(installed: &str, remote: &str) -> Ordering {
    let norm_a = normalize_version(installed);
    let norm_b = normalize_version(remote);
    if norm_a.eq_ignore_ascii_case(&norm_b) {
        return Ordering::Equal;
    }
    if norm_a.is_empty() && !norm_b.is_empty() {
        return Ordering::Less;
    }
    if !norm_a.is_empty() && norm_b.is_empty() {
        return Ordering::Greater;
    }

    let tok_a = tokenize(&norm_a);
    let tok_b = tokenize(&norm_b);
    let max_len = tok_a.len().max(tok_b.len());

    for i in 0..max_len {
        let a = tok_a.get(i);
        let b = tok_b.get(i);
        match (a, b) {
            (Some(Token::Num(na)), Some(Token::Num(nb))) => {
                if na != nb {
                    return na.cmp(nb);
                }
            }
            (Some(Token::Num(na)), None) => {
                if *na != 0 {
                    return Ordering::Greater;
                }
            }
            (None, Some(Token::Num(nb))) => {
                if *nb != 0 {
                    return Ordering::Less;
                }
            }
            // A stable version (None) is GREATER than a pre-release suffix at the same position (1.0 > 1.0-beta1)
            (None, Some(Token::PreRelease(_))) => return Ordering::Greater,
            (Some(Token::PreRelease(_)), None) => return Ordering::Less,
            (Some(Token::PreRelease(pa)), Some(Token::PreRelease(pb))) => {
                if pa != pb {
                    return pa.cmp(pb);
                }
            }
            (Some(Token::Num(_)), Some(Token::PreRelease(_))) => return Ordering::Greater,
            (Some(Token::PreRelease(_)), Some(Token::Num(_))) => return Ordering::Less,
            (Some(Token::Text(ta)), Some(Token::Text(tb))) => {
                if ta != tb {
                    return ta.cmp(tb);
                }
            }
            (Some(_), None) => return Ordering::Greater,
            (None, Some(_)) => return Ordering::Less,
            (Some(Token::Text(_)), Some(_)) => return Ordering::Less,
            (Some(_), Some(Token::Text(_))) => return Ordering::Greater,
            (None, None) => break,
        }
    }

    Ordering::Equal
}

pub fn evaluate_update(installed: &str, remote: &str, allow_downgrade: bool) -> VersionComparisonResult {
    let inst_norm = normalize_version(installed);
    let rem_norm = normalize_version(remote);
    let ord = compare_versions(&inst_norm, &rem_norm);
    let ordering_int = match ord {
        Ordering::Less => -1,
        Ordering::Equal => 0,
        Ordering::Greater => 1,
    };
    let is_update = if inst_norm.is_empty() && !rem_norm.is_empty() {
        true
    } else if ord == Ordering::Less {
        true
    } else {
        allow_downgrade && ord == Ordering::Greater
    };

    VersionComparisonResult {
        installed_normalized: inst_norm,
        remote_normalized: rem_norm,
        is_update_available: is_update,
        is_downgrade: ord == Ordering::Greater,
        ordering: ordering_int,
    }
}
