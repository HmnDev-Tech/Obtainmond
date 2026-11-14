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
    "-rust",
    "_rust",
    "-foss",
    "_foss",
    "-standard",
    "-full",
    "-lite",
    "-official",
    "-prod",
    "-normal",
    "-ndk",
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
    "-gms",
    "-play",
    "-github",
    "-gitlab",
    "_release",
    "_signed",
    "_universal",
];

fn is_hex_hash(slice: &str, min_len: usize, allow_all_digits: bool) -> bool {
    if slice.len() < min_len || slice.len() > 40 {
        return false;
    }
    if !slice.chars().all(|c| c.is_ascii_hexdigit()) {
        return false;
    }
    allow_all_digits || slice.chars().any(|c| matches!(c, 'a'..='f' | 'A'..='F'))
}

fn strip_git_hash_suffix(input: &str) -> String {
    let mut s = input.to_string();
    if let Some(last_dot) = s.rfind('.') {
        let hash_part = &s[last_dot + 1..];
        if is_hex_hash(hash_part, 6, true) {
            let prefix = &s[..last_dot];
            if let Some(r_dot) = prefix.rfind(".r") {
                let rev_part = &prefix[r_dot + 2..];
                if !rev_part.is_empty() && rev_part.chars().all(|c| c.is_ascii_digit()) {
                    return format!("{}-r{}", &prefix[..r_dot], rev_part);
                }
            }
        }
    }
    if let Some(sep_idx) = s.rfind(|c| c == '.' || c == '-') {
        let tail = &s[sep_idx + 1..];
        let (candidate, has_g) = if let Some(stripped) = tail.strip_prefix('g') {
            (stripped, true)
        } else {
            (tail, false)
        };
        if sep_idx > 0 && is_hex_hash(candidate, 7, has_g) {
            s.truncate(sep_idx);
        }
    }
    s
}

pub fn normalize_version(raw: &str) -> String {
    let trimmed = raw.trim();
    if trimmed.is_empty() {
        return String::new();
    }

    let without_paren = trimmed.split('(').next().unwrap_or(trimmed).trim();

    // Strip build metadata (+...)
    let without_build = without_paren.split('+').next().unwrap_or(without_paren).trim();

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
        let with_hash_stripped = strip_git_hash_suffix(&s);
        if with_hash_stripped != s {
            s = with_hash_stripped;
            continue;
        }
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

fn split_leading_nums(tokens: &[Token]) -> (Vec<u64>, &[Token]) {
    let mut split_idx = 0;
    let mut nums = Vec::new();
    for tok in tokens {
        match tok {
            Token::Num(n) => {
                nums.push(*n);
                split_idx += 1;
            }
            _ => break,
        }
    }
    while nums.len() > 1 && nums.last() == Some(&0) {
        nums.pop();
    }
    (nums, &tokens[split_idx..])
}

fn max_rem_num(tokens: &[Token]) -> Option<u64> {
    tokens
        .iter()
        .filter_map(|t| match t {
            Token::Num(n) => Some(*n),
            _ => None,
        })
        .max()
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

    let (nums_a, rem_a) = split_leading_nums(&tok_a);
    let (nums_b, rem_b) = split_leading_nums(&tok_b);
    if !nums_a.is_empty() && nums_a == nums_b {
        let has_pre_a = rem_a.iter().any(|t| matches!(t, Token::PreRelease(_)));
        let has_pre_b = rem_b.iter().any(|t| matches!(t, Token::PreRelease(_)));
        if !has_pre_a && !has_pre_b {
            let num_a = max_rem_num(rem_a);
            let num_b = max_rem_num(rem_b);
            if num_a == num_b {
                return Ordering::Equal;
            }
            if !rem_a.is_empty() && rem_b.is_empty() {
                return Ordering::Equal;
            }
        }
    }

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
