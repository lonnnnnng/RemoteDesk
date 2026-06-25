#[cfg(target_os = "macos")]
use std::{
    collections::BTreeSet,
    fs,
    path::{Path, PathBuf},
    process::Command,
};

fn main() {
    tauri_build::build();
    emit_macos_swift_runtime_rpaths();
}

#[cfg(target_os = "macos")]
fn emit_macos_swift_runtime_rpaths() {
    println!("cargo:rustc-link-arg=-Wl,-rpath,/usr/lib/swift");

    for path in macos_swift_runtime_rpaths() {
        println!("cargo:rustc-link-arg=-Wl,-rpath,{}", path.display());
    }
}

#[cfg(target_os = "macos")]
fn macos_swift_runtime_rpaths() -> BTreeSet<PathBuf> {
    let mut paths = BTreeSet::new();

    if let Some(usr_dir) = active_swift_usr_dir() {
        collect_swift_runtime_rpaths(&mut paths, &usr_dir);
    }

    if let Some(developer_dir) = active_developer_dir() {
        let developer_dir = PathBuf::from(developer_dir);
        if developer_dir.ends_with("CommandLineTools") {
            collect_swift_runtime_rpaths(&mut paths, &developer_dir.join("usr"));
        } else {
            collect_swift_runtime_rpaths(
                &mut paths,
                &developer_dir.join("Toolchains/XcodeDefault.xctoolchain/usr"),
            );
        }
    }

    paths
}

#[cfg(target_os = "macos")]
fn active_swift_usr_dir() -> Option<PathBuf> {
    let output = Command::new("xcrun")
        .args(["--find", "swift"])
        .output()
        .ok()?;
    if !output.status.success() {
        return None;
    }

    let swift_path = PathBuf::from(String::from_utf8_lossy(&output.stdout).trim().to_string());
    let bin_dir = swift_path.parent()?;
    let usr_dir = bin_dir.parent()?;
    Some(usr_dir.to_path_buf())
}

#[cfg(target_os = "macos")]
fn active_developer_dir() -> Option<String> {
    let output = Command::new("xcode-select").arg("-p").output().ok()?;
    if !output.status.success() {
        return None;
    }

    let developer_dir = String::from_utf8_lossy(&output.stdout).trim().to_string();
    if developer_dir.is_empty() {
        return None;
    }

    Some(developer_dir)
}

#[cfg(target_os = "macos")]
fn collect_swift_runtime_rpaths(paths: &mut BTreeSet<PathBuf>, usr_dir: &Path) {
    push_swift_runtime_rpath(paths, usr_dir.join("lib/swift/macosx"));

    let Ok(entries) = fs::read_dir(usr_dir.join("lib")) else {
        return;
    };

    for entry in entries.flatten() {
        let candidate = entry.path();
        let Some(name) = candidate.file_name().and_then(|value| value.to_str()) else {
            continue;
        };
        if !name.starts_with("swift-") {
            continue;
        }

        push_swift_runtime_rpath(paths, candidate.join("macosx"));
    }
}

#[cfg(target_os = "macos")]
fn push_swift_runtime_rpath(paths: &mut BTreeSet<PathBuf>, path: PathBuf) {
    if path.is_dir() {
        paths.insert(path);
    }
}

#[cfg(not(target_os = "macos"))]
fn emit_macos_swift_runtime_rpaths() {}
