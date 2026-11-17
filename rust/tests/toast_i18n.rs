//! Guard for the Java↔core toast contract.
//!
//! The Rust core emits plain English toast strings; `L.toast()` in the Android
//! layer translates them at runtime. A new `toast("...")` literal in the core is
//! silently English until somebody adds a matching `put(...)` to `L.java`. This
//! test runs in CI (`cargo test`) and fails on exactly that drift.

const L_JAVA: &str = include_str!(concat!(
    env!("CARGO_MANIFEST_DIR"),
    "/../android/app/src/main/java/dev/drift/launcher/L.java"
));
const LAUNCHER_RS: &str = include_str!(concat!(env!("CARGO_MANIFEST_DIR"), "/src/launcher.rs"));
const GESTURES_RS: &str = include_str!(concat!(env!("CARGO_MANIFEST_DIR"), "/src/gestures.rs"));

/// Every string between `toast("` and the next `"` in a source file.
fn core_literals(src: &str) -> Vec<&str> {
    let mut out = Vec::new();
    let mut rest = src;
    while let Some(i) = rest.find("toast(\"") {
        rest = &rest[i + "toast(\"".len()..];
        if let Some(end) = rest.find('"') {
            out.push(&rest[..end]);
            rest = &rest[end + 1..];
        } else {
            break;
        }
    }
    out
}

/// Every English key registered in the `L.java` string map.
fn translated_keys(src: &str) -> Vec<&str> {
    let mut out = Vec::new();
    let mut rest = src;
    while let Some(i) = rest.find("put(\"") {
        rest = &rest[i + "put(\"".len()..];
        if let Some(end) = rest.find('"') {
            out.push(&rest[..end]);
            rest = &rest[end + 1..];
        } else {
            break;
        }
    }
    out
}

#[test]
fn every_core_toast_has_a_russian_translation() {
    let keys = translated_keys(L_JAVA);
    for src in [LAUNCHER_RS, GESTURES_RS] {
        for literal in core_literals(src) {
            assert!(
                keys.contains(&literal),
                "core toast {:?} has no entry in L.java — add put({:?}, \"…\") there",
                literal,
                literal
            );
        }
    }
}

#[test]
fn dynamic_missing_app_toast_is_handled() {
    // gestures.rs builds "{label} is not installed. Select it and tap remove to clear the card"
    // at runtime; L.toast() recognises the suffix and wraps the label in quotes.
    let tail = " is not installed. Select it and tap remove to clear the card";
    assert!(GESTURES_RS.contains(tail), "dynamic toast tail changed in the core");
    assert!(L_JAVA.contains(tail), "L.toast() no longer recognises the dynamic toast tail");
}
