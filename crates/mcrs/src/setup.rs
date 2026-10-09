//! Prepares the run directory: JDK, Fabric server launcher, bridge mod.

use std::fs;
use std::io;
use std::path::{Path, PathBuf};

use anyhow::{bail, Context, Result};

use crate::config::Config;

/// Minimum Java version Minecraft 26.x requires.
const REQUIRED_JAVA: u32 = 25;

pub const FABRIC_LAUNCHER_JAR: &str = "fabric-server-launch.jar";

pub fn find_java_home(config: &Config, project_dir: &Path) -> Result<PathBuf> {
    let mut candidates: Vec<PathBuf> = Vec::new();
    candidates.extend(config.java_home.clone());
    candidates.extend(std::env::var_os("MCRS_JAVA_HOME").map(PathBuf::from));
    candidates.extend(std::env::var_os("JAVA_HOME").map(PathBuf::from));
    if let Ok(entries) = fs::read_dir(project_dir.join(".tools")) {
        let mut jdks: Vec<PathBuf> = entries
            .flatten()
            .map(|e| e.path())
            .filter(|p| p.file_name().is_some_and(|n| n.to_string_lossy().starts_with("jdk")))
            .collect();
        jdks.sort();
        candidates.extend(jdks.into_iter().rev());
    }

    for home in &candidates {
        match mcrs_jvm::java_major_version(home) {
            Ok(v) if v >= REQUIRED_JAVA => return Ok(home.clone()),
            Ok(v) => tracing::debug!("skipping {} (Java {v})", home.display()),
            Err(e) => tracing::debug!("skipping {}: {e:#}", home.display()),
        }
    }
    bail!(
        "no Java {REQUIRED_JAVA}+ JDK found. Set java_home in mcrs.toml or MCRS_JAVA_HOME, \
         or unpack a JDK into .tools/ (checked: {candidates:?})"
    )
}

fn download(url: &str, dest: &Path) -> Result<()> {
    tracing::info!("downloading {url}");
    let response = ureq::get(url).call().with_context(|| format!("GET {url}"))?;
    let tmp = dest.with_extension("part");
    let mut file = fs::File::create(&tmp)?;
    io::copy(&mut response.into_reader(), &mut file)?;
    drop(file);
    fs::rename(&tmp, dest)?;
    Ok(())
}

/// Downloads Fabric's server launcher, which fetches vanilla `server.jar` and the
/// loader libraries itself on first start. The file name encodes the versions so
/// changing them in mcrs.toml triggers a fresh download.
pub fn ensure_fabric_launcher(config: &Config, run_dir: &Path) -> Result<()> {
    let jar = run_dir.join(FABRIC_LAUNCHER_JAR);
    let stamp = run_dir.join(".mcrs-fabric-version");
    let wanted = format!(
        "{}/{}/{}",
        config.minecraft_version, config.fabric_loader_version, config.fabric_installer_version
    );
    if jar.exists() && fs::read_to_string(&stamp).is_ok_and(|s| s == wanted) {
        return Ok(());
    }
    let url = format!("https://meta.fabricmc.net/v2/versions/loader/{wanted}/server/jar");
    download(&url, &jar)?;
    fs::write(stamp, wanted)?;
    Ok(())
}

/// Copies the freshest `bridge-mod/build/libs/mcrs-bridge-*.jar` into `run/mods`.
pub fn install_bridge_mod(project_dir: &Path, run_dir: &Path) -> Result<()> {
    let libs = project_dir.join("bridge-mod/build/libs");
    let built = fs::read_dir(&libs)
        .ok()
        .into_iter()
        .flatten()
        .flatten()
        .map(|e| e.path())
        .filter(|p| {
            let name = p.file_name().unwrap().to_string_lossy();
            name.starts_with("mcrs-bridge-") && name.ends_with(".jar") && !name.contains("-sources")
        })
        .max_by_key(|p| p.metadata().and_then(|m| m.modified()).ok());
    let Some(built) = built else {
        bail!(
            "bridge mod not built. Run `./gradlew build` in bridge-mod/ (looked in {})",
            libs.display()
        );
    };

    let mods = run_dir.join("mods");
    fs::create_dir_all(&mods)?;
    for entry in fs::read_dir(&mods)?.flatten() {
        if entry.file_name().to_string_lossy().starts_with("mcrs-bridge-") {
            fs::remove_file(entry.path())?;
        }
    }
    fs::copy(&built, mods.join(built.file_name().unwrap()))?;
    tracing::info!("installed {}", built.file_name().unwrap().to_string_lossy());
    Ok(())
}

pub fn warn_if_eula_missing(run_dir: &Path) {
    let accepted = fs::read_to_string(run_dir.join("eula.txt"))
        .is_ok_and(|s| s.lines().any(|l| l.trim() == "eula=true"));
    if !accepted {
        tracing::warn!(
            "Minecraft EULA not accepted yet. Read https://aka.ms/MinecraftEULA and set \
             eula=true in {}",
            run_dir.join("eula.txt").display()
        );
    }
}
