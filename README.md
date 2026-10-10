# Vantage Server

A faster Minecraft 26.3 server that plays exactly like vanilla. Rust hosts the JVM and runs
Fabric (with your Fabric mods) inside it; hot parts of the game are replaced by exact, faster
code in Rust and Java. Every optimization is verified against vanilla: same terrain block for
block, same mob and redstone behavior tick for tick.

Measured on a laptop (i7-11800H), Vantage vs vanilla 26.3 with the same JDK and settings:

| | Vanilla | Vantage |
|---|---|---|
| Chunk generation (Chunky, 35k chunks) | 144 chunks/s | 230 chunks/s (1.6x) |
| 320 redstone clocks, mean / p95 tick | 45.5 / 208 ms | 23.8 / 92 ms (1.9x) |
| 1500 mobs in a pen, mean tick | 45.7 ms | 28.1 ms (-38%) |
| 10 players flying, mean tick | 43.7 ms | 41.9 ms (36.7 ms with activation range) |

Switches (JVM options in `mcrs.toml`, `jvm_options`): every optimization can be turned off,
e.g. `-Dmcrs.worldgen=false`, `-Dmcrs.fastEntities=false`, `-Dmcrs.fastBlocks=false`.
`-Dmcrs.activationRange=true` turns on the optional activation range (not vanilla behavior:
mobs more than 32-48 blocks from every player do a full tick only every 4th tick).

Benchmarks: `tools/bench/tps.sh` (TPS of all scenarios), `tools/bench/gen.sh` (generation),
`tools/bench/tick.sh` (exactness harness). See [PLAN.md](PLAN.md) for design notes and results.

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
