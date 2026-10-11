# Vantage Server

A faster Minecraft 26.3 server for Fabric. Rust hosts the JVM and runs Fabric (with your Fabric
mods) inside it; hot parts of the game are replaced by exact, faster code in Rust and Java. The
default edition plays exactly like vanilla: every optimization is verified against vanilla
(same terrain block for block, same mob and redstone behavior tick for tick). Two more
editions trade some vanilla behavior for speed, like Paper and Pufferfish do.

Measured on a laptop (i7-11800H), same JDK and settings, mean tick time (lower is better):

| | Vanilla 26.3 | Vantage `vanilla` | Vantage `balanced` | Vantage `performance` |
|---|---|---|---|---|
| 10 players flying over a world | 43.8 ms | 41.9 ms | 36.8 ms | 30.3 ms |
| 1500 mobs next to a player | 45.8 ms | 32.2 ms | n/a | 33.6 ms |
| 320 redstone clocks | 44.4 ms | 25.2 ms | n/a | 8.9 ms |
| 1600 hoppers | 1.6 ms | 1.7 ms | n/a | 1.2 ms |
| Chunk generation (Chunky, chunks/s, higher is better) | 135 | 306 | same | same |

n/a: balanced only differs from `vanilla` for mobs away from players. Mobs right next to
players gain nothing more from the performance edition either, since its mob changes only act
on mobs away from players.

## Editions
One build, three editions, chosen with `edition` in `mcrs.toml`:

| `edition` | Game behavior | What changes |
|---|---|---|
| `"vanilla"` (default) | exactly vanilla | only optimizations with identical results |
| `"balanced"` | almost vanilla | + mild activation range: mobs 32-48 blocks from every player do a full tick every 4th tick |
| `"performance"` | further from vanilla, like Paper/Pufferfish defaults | see below |

The performance edition adds:
- **Stronger activation range**: mobs out of range (monsters 32 blocks, water mobs and bats 16,
  others 24) tick fully every 8th tick; items lying still with no player within 32 blocks merge
  and run physics every 8th tick (they still despawn on time). Mobs that fight, are hurt,
  ride, are leashed, breed or raid, and bosses always tick.
- **Brain throttle** (like Pufferfish's DAB, milder): beyond 16 blocks from players mobs pick
  new goals and run brain sensors less often (32 blocks: every 2nd tick, 64: every 8th); they
  keep moving every tick.
- **Spawn rate**: natural spawn attempts for monsters every 2nd tick, water mobs and bats every
  4th; mob caps unchanged.
- **Collision limit**: an entity pushes at most 8 others per tick (Paper's default).
- **Idle hoppers**: a hopper that found nothing to move waits 4 ticks (locked hoppers and items
  moving through hopper chains keep vanilla timing).
- **Redstone experiments**: Mojang's experimental redstone ("Redstone Experiments" data pack:
  new wire evaluator and update order) without the data pack. Some contraptions that rely on
  today's update order can behave differently.

World generation stays exact in every edition (seeds and structures match vanilla), and Fabric
mods work in all three. An approximate terrain mode like Pumpkin's (`-Dmcrs.approxWorldgen=1..3`:
fewer noise octaves, coarser interpolation cells; terrain roughly the same, not block for block)
exists, but it is only 1-6% faster in a real pregeneration (the exact Rust terrain is already
cheap; saving and scheduling dominated), so no edition turns it on.

Chunk generation: chunks are serialized and compressed in parallel instead of on vanilla's
single chunk IO thread (byte-identical files; `-Dmcrs.parallelSave=false` turns it off), and
Chunky gets 500 chunks in flight instead of 50 (`-Dchunky.maxWorkingCount`). Chunky on a fresh
world, chunks/s: vanilla 135 (154 with 500 in flight), Vantage 0.2.0 227 (300), now 306. Every change has its own switch (JVM option in `jvm_options`) that
overrides the edition, e.g. `-Dmcrs.redstoneExperiments=false`, `-Dmcrs.brainThrottle=false`,
`-Dmcrs.activationRange=true`; the exact optimizations can be turned off too, e.g.
`-Dmcrs.worldgen=false`, `-Dmcrs.fastEntities=false`, `-Dmcrs.fastBlocks=false`.

Benchmarks: `tools/bench/tps.sh` (TPS of all scenarios), `tools/bench/chunky.sh` (Chunky
pregeneration, with `/mcrs genstats`), `tools/bench/gen.sh` (generation),
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
