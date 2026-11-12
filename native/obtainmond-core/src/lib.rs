pub mod apk_selector;
pub mod integrity;
pub mod sources;
pub mod version;

use apk_selector::{select_best_apk, ApkSelectionRequest};
use integrity::{build_silent_install_command, inspect_apk_file};
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use sources::{parse_release_payload, resolve_source_descriptor};
use version::{evaluate_update, extract_version_with_regex, normalize_version};

fn jstring_to_rust(env: &mut JNIEnv, input: &JString) -> String {
    env.get_string(input)
        .map(|s| s.into())
        .unwrap_or_default()
}

fn rust_to_jstring<'a>(env: &mut JNIEnv<'a>, output: &str) -> jstring {
    env.new_string(output)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_io_obtainmond_rust_ObtainmondNative_getEngineVersion<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jstring {
    rust_to_jstring(
        &mut env,
        "Obtainmond Rust Core v2.0.0 (Zero-Touch ABI Selector + Release Engine + SHA256 Guard)",
    )
}

#[no_mangle]
pub extern "system" fn Java_io_obtainmond_rust_ObtainmondNative_normalizeVersion<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    raw_version: JString<'local>,
    regex_pattern: JString<'local>,
) -> jstring {
    let raw = jstring_to_rust(&mut env, &raw_version);
    let pat = jstring_to_rust(&mut env, &regex_pattern);
    let out = extract_version_with_regex(&raw, &pat);
    rust_to_jstring(&mut env, &out)
}

#[no_mangle]
pub extern "system" fn Java_io_obtainmond_rust_ObtainmondNative_compareVersionsJson<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    installed_version: JString<'local>,
    remote_version: JString<'local>,
    allow_downgrade: jboolean,
) -> jstring {
    let inst = jstring_to_rust(&mut env, &installed_version);
    let rem = jstring_to_rust(&mut env, &remote_version);
    let res = evaluate_update(&inst, &rem, allow_downgrade == JNI_TRUE);
    let json = serde_json::to_string(&res).unwrap_or_else(|_| "{}".to_string());
    rust_to_jstring(&mut env, &json)
}

#[no_mangle]
pub extern "system" fn Java_io_obtainmond_rust_ObtainmondNative_isUpdateAvailable<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    installed_version: JString<'local>,
    remote_version: JString<'local>,
    allow_downgrade: jboolean,
) -> jboolean {
    let inst = jstring_to_rust(&mut env, &installed_version);
    let rem = jstring_to_rust(&mut env, &remote_version);
    let norm_i = normalize_version(&inst);
    let norm_r = normalize_version(&rem);
    let res = evaluate_update(&norm_i, &norm_r, allow_downgrade == JNI_TRUE);
    if res.is_update_available {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[no_mangle]
pub extern "system" fn Java_io_obtainmond_rust_ObtainmondNative_selectBestApkJson<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    request_json: JString<'local>,
) -> jstring {
    let req_str = jstring_to_rust(&mut env, &request_json);
    let response = match serde_json::from_str::<ApkSelectionRequest>(&req_str) {
        Ok(req) => select_best_apk(&req),
        Err(e) => apk_selector::ApkSelectionResponse {
            selected_name: String::new(),
            selected_url: String::new(),
            detected_abi: String::new(),
            score: -1,
            confidence: "ERROR".to_string(),
            reason: format!("Invalid selection request JSON: {}", e),
            ranked_candidates: Vec::new(),
        },
    };
    let out = serde_json::to_string(&response).unwrap_or_else(|_| "{}".to_string());
    rust_to_jstring(&mut env, &out)
}

#[no_mangle]
pub extern "system" fn Java_io_obtainmond_rust_ObtainmondNative_resolveSourceUrlJson<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    raw_url: JString<'local>,
) -> jstring {
    let url = jstring_to_rust(&mut env, &raw_url);
    let desc = resolve_source_descriptor(&url);
    let out = serde_json::to_string(&desc).unwrap_or_else(|_| "{}".to_string());
    rust_to_jstring(&mut env, &out)
}

#[no_mangle]
pub extern "system" fn Java_io_obtainmond_rust_ObtainmondNative_parseReleasePayloadJson<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    source_type: JString<'local>,
    payload: JString<'local>,
    include_prereleases: jboolean,
    fallback_url: JString<'local>,
) -> jstring {
    let st = jstring_to_rust(&mut env, &source_type);
    let body = jstring_to_rust(&mut env, &payload);
    let fb = jstring_to_rust(&mut env, &fallback_url);
    match parse_release_payload(&st, &body, include_prereleases == JNI_TRUE, &fb) {
        Ok(parsed) => {
            let json = serde_json::to_string(&parsed).unwrap_or_else(|_| "{}".to_string());
            rust_to_jstring(&mut env, &json)
        }
        Err(err) => {
            let err_json = serde_json::json!({ "error": err }).to_string();
            rust_to_jstring(&mut env, &err_json)
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_io_obtainmond_rust_ObtainmondNative_inspectApkFileJson<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    apk_path: JString<'local>,
    device_abis_csv: JString<'local>,
) -> jstring {
    let path = jstring_to_rust(&mut env, &apk_path);
    let abis_str = jstring_to_rust(&mut env, &device_abis_csv);
    let device_abis: Vec<String> = abis_str
        .split(',')
        .map(|s| s.trim().to_string())
        .filter(|s| !s.is_empty())
        .collect();

    match inspect_apk_file(&path, &device_abis) {
        Ok(report) => {
            let json = serde_json::to_string(&report).unwrap_or_else(|_| "{}".to_string());
            rust_to_jstring(&mut env, &json)
        }
        Err(err) => {
            let err_json = serde_json::json!({ "error": err, "valid_apk_zip": false }).to_string();
            rust_to_jstring(&mut env, &err_json)
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_io_obtainmond_rust_ObtainmondNative_buildSilentInstallCommand<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    apk_path: JString<'local>,
    installer_package: JString<'local>,
    allow_downgrade: jboolean,
    grant_runtime_permissions: jboolean,
    target_package_for_dexopt: JString<'local>,
) -> jstring {
    let path = jstring_to_rust(&mut env, &apk_path);
    let installer = jstring_to_rust(&mut env, &installer_package);
    let dexopt_pkg = jstring_to_rust(&mut env, &target_package_for_dexopt);
    let cmd = build_silent_install_command(
        &path,
        &installer,
        allow_downgrade == JNI_TRUE,
        grant_runtime_permissions == JNI_TRUE,
        &dexopt_pkg,
    );
    rust_to_jstring(&mut env, &cmd)
}
