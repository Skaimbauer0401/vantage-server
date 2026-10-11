mod config;
mod setup;

use std::path::Path;
use std::time::Duration;

use anyhow::{Context, Result};
use mcrs_jvm::{bridge, Jvm};
use tracing_subscriber::EnvFilter;

use config::Config;

/// Main class of Fabric's server launcher jar.
const FABRIC_SERVER_LAUNCHER: &str = "net.fabricmc.installer.ServerLauncher";

/// HotSpot's default main-thread stack is 8 MB on 64-bit; worldgen recursion needs it.
const JVM_THREAD_STACK: usize = 8 * 1024 * 1024;

fn main() -> Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(EnvFilter::try_from_default_env().unwrap_or_else(|_| "info".into()))
        .init();

    let project_dir = std::env::current_dir()?;
    let config = Config::load_or_create(&project_dir)?;
    // MCRS_RUN_DIR: a separate server directory, e.g. for reproducible benchmarks.
    let run_dir = match std::env::var_os("MCRS_RUN_DIR") {
        Some(dir) => std::path::PathBuf::from(dir),
        None => project_dir.join(&config.run_dir),
    };
    std::fs::create_dir_all(&run_dir)?;

    let java_home = setup::find_java_home(&config, &project_dir)?;
    tracing::info!(
        "mcrs {} | {} edition | Minecraft {} | Fabric Loader {} | JDK {}",
        env!("CARGO_PKG_VERSION"),
        config.edition.name(),
        config.minecraft_version,
        config.fabric_loader_version,
        java_home.display()
    );
    setup::ensure_fabric_launcher(&config, &run_dir)?;
    setup::install_bridge_mod(&project_dir, &run_dir)?;
    setup::warn_if_eula_missing(&run_dir);

    if config.tick_report_interval_secs > 0 {
        bridge::spawn_tick_reporter(Duration::from_secs(config.tick_report_interval_secs));
    }

    // The server resolves world/config paths relative to the working directory.
    std::env::set_current_dir(&run_dir)?;

    let jvm_thread = std::thread::Builder::new()
        .name("main".into())
        .stack_size(JVM_THREAD_STACK)
        .spawn(move || run_server(&config, &java_home))?;
    jvm_thread.join().expect("JVM thread panicked")
}

/// JVM options for the AOT cache: load it if it matches this setup, else record a new one
/// (the JVM writes it when the server exits).
fn aot_options(config: &Config, java_home: &Path, options: &[String]) -> Result<Vec<String>> {
    use std::hash::{Hash, Hasher};

    let dir = std::env::current_dir()?.join(".mcrs");
    std::fs::create_dir_all(&dir)?;
    let cache = dir.join("server.aot");
    let stamp = dir.join("server.aot.fingerprint");

    // Everything the cache depends on: a stale cache is ignored by the JVM, so rebuild it.
    let mut h = std::collections::hash_map::DefaultHasher::new();
    java_home.hash(&mut h);
    (&config.minecraft_version, &config.fabric_loader_version, &config.fabric_installer_version).hash(&mut h);
    for o in options.iter().filter(|o| !o.starts_with("-Dmcrs.bridge=")) {
        o.hash(&mut h);
    }
    let mut files = vec![std::path::PathBuf::from(setup::FABRIC_LAUNCHER_JAR)];
    if let Ok(entries) = std::fs::read_dir("mods") {
        files.extend(entries.flatten().map(|e| e.path()));
    }
    files.sort();
    for f in &files {
        if let Ok(meta) = std::fs::metadata(f) {
            f.hash(&mut h);
            meta.len().hash(&mut h);
            meta.modified().ok().hash(&mut h);
        }
    }
    let fingerprint = format!("{:016x}", h.finish());

    let path = cache.to_string_lossy().into_owned();
    if cache.exists() && std::fs::read_to_string(&stamp).ok().as_deref() == Some(fingerprint.as_str()) {
        return Ok(vec![format!("-XX:AOTCache={path}")]);
    }
    let _ = std::fs::remove_file(&cache);
    std::fs::write(&stamp, &fingerprint)?;
    tracing::info!("recording a JVM AOT cache this run; later starts will be faster");
    Ok(vec![format!("-XX:AOTCacheOutput={path}")])
}

fn run_server(config: &Config, java_home: &Path) -> Result<()> {
    let mut options = vec![
        format!("-Djava.class.path={}", setup::FABRIC_LAUNCHER_JAR),
        format!("-Xmx{}", config.max_memory),
        format!("-Dmcrs.bridge={}", bridge::table_address()),
        // Lets the bridge mod use Panama FFM downcalls without warnings.
        "--enable-native-access=ALL-UNNAMED".into(),
    ];
    if config.deterministic_math {
        options.push("-XX:+UnlockDiagnosticVMOptions".into());
        options.push("-XX:-UseLibmIntrinsic".into());
    }
    // Before jvm_options so that they can override single switches.
    options.push(format!("-Dmcrs.edition={}", config.edition.name()));
    options.extend(config.jvm_options.iter().cloned());
    // One-off extras (profiling etc.) without editing mcrs.toml.
    if let Ok(extra) = std::env::var("MCRS_JVM_OPTIONS") {
        options.extend(extra.split_whitespace().map(String::from));
    }
    // MCRS_AOT_CACHE=0: off for this run (benchmarks that vary JVM options).
    if config.aot_cache && std::env::var("MCRS_AOT_CACHE").as_deref() != Ok("0") {
        match aot_options(config, java_home, &options) {
            Ok(aot) => options.extend(aot),
            Err(e) => tracing::warn!("AOT cache disabled: {e:#}"),
        }
    }

    let jvm = Jvm::create(java_home, &options).context("starting JVM")?;
    jvm.call_main(FABRIC_SERVER_LAUNCHER, &config.server_args)?;
    // main() returns once the server thread is running; wait for it to stop.
    jvm.wait_for_exit()?;
    tracing::info!("server stopped");
    Ok(())
}
