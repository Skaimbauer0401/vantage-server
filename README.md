# mcrs

A Rust-accelerated Fabric server for Minecraft 26.3. See [PLAN.md](PLAN.md) for the full roadmap.

**Status: Phase 0.** Rust hosts the JVM, Fabric + mods run inside it, and the bridge mod
talks to Rust through Panama FFM. No subsystems are moved to Rust yet.

## Requirements
- Rust (pinned in `rust-toolchain.toml`; rustup installs it automatically)
- JDK 25+: set `java_home` in `mcrs.toml`, `MCRS_JAVA_HOME`, `JAVA_HOME`, or unpack one into `.tools/`

## Build & run
```sh
cd bridge-mod && ./gradlew build && cd ..   # build the bridge mod
cargo run --release                          # creates mcrs.toml and run/ on first start
```
Accept the EULA in `run/eula.txt`, then start again. Put Fabric mods in `run/mods/`.

## Layout
| Path | What |
|---|---|
| `crates/mcrs` | Launcher binary: config, downloads, boots the JVM |
| `crates/mcrs-jvm` | Embeds HotSpot (`jvm.dll` loaded at runtime) and the native bridge table |
| `crates/mcrs-chunk` | Anvil region files + chunk decompression (zlib, gzip, LZ4) |
| `bridge-mod` | Fabric mod: binds the bridge via FFM, Mixins that hand work to Rust |

## Benchmarks
In the server console (or as an op):
```
mcrs bench chunks <radius> [x z]                   generate (no center) or load (center) chunks
mcrs bench entities <type> <count> [seconds]       e.g. minecraft:villager 300
```
Profile with JFR: `MCRS_JVM_OPTIONS="-XX:StartFlightRecording=filename=bench.jfr,settings=profile" cargo run --release`.
