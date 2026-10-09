use std::path::{Path, PathBuf};

use anyhow::{Context, Result};
use serde::{Deserialize, Serialize};

pub const CONFIG_FILE: &str = "mcrs.toml";

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(default)]
pub struct Config {
    /// Minecraft version. mcrs targets exactly one version at a time.
    pub minecraft_version: String,
    pub fabric_loader_version: String,
    pub fabric_installer_version: String,
    /// Server directory (worlds, mods, server.properties, eula.txt).
    pub run_dir: PathBuf,
    /// JDK to embed. Empty = MCRS_JAVA_HOME, JAVA_HOME, then `.tools/jdk-*`.
    pub java_home: Option<PathBuf>,
    pub max_memory: String,
    /// Extra JVM options, e.g. GC tuning.
    pub jvm_options: Vec<String>,
    /// Arguments passed to the Minecraft server, e.g. ["nogui"].
    pub server_args: Vec<String>,
    /// Seconds between TPS/MSPT log lines. 0 disables.
    pub tick_report_interval_secs: u64,
    /// Make `Math.log/exp/pow/sin/cos` use fdlibm (= StrictMath) instead of
    /// CPU-specific intrinsics. Required for Rust worldgen to match Java bit for bit;
    /// also makes results identical across x86 and ARM.
    pub deterministic_math: bool,
    /// Keep a JVM AOT cache (JDK 25+) in `<run_dir>/.mcrs/`: the first start records it,
    /// later starts load and link those classes from it. Rebuilt whenever the JVM, its
    /// options, the versions or the mods change.
    pub aot_cache: bool,
}

impl Default for Config {
    fn default() -> Self {
        Config {
            minecraft_version: "26.3".into(),
            fabric_loader_version: "0.19.5".into(),
            fabric_installer_version: "1.1.2".into(),
            run_dir: "run".into(),
            java_home: None,
            max_memory: "4G".into(),
            jvm_options: vec!["-XX:+UseZGC".into()],
            server_args: vec!["nogui".into()],
            tick_report_interval_secs: 30,
            deterministic_math: true,
            aot_cache: true,
        }
    }
}

impl Config {
    /// Loads `mcrs.toml` from `dir`, writing the defaults first if it doesn't exist.
    pub fn load_or_create(dir: &Path) -> Result<Config> {
        let path = dir.join(CONFIG_FILE);
        if !path.exists() {
            let default = Config::default();
            std::fs::write(&path, toml::to_string_pretty(&default)?)
                .with_context(|| format!("writing {}", path.display()))?;
            tracing::info!("created default {}", path.display());
            return Ok(default);
        }
        let text = std::fs::read_to_string(&path)?;
        toml::from_str(&text).with_context(|| format!("parsing {}", path.display()))
    }
}
