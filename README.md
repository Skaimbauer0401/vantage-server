# Vantage Server

A faster Minecraft 26.3 server for Fabric. Rust hosts the JVM and runs Fabric (with your Fabric
mods) inside it; hot parts of the game are replaced by exact, faster code in Rust and Java. The
default edition plays exactly like vanilla: every optimization is verified against vanilla
(same terrain block for block, same mob and redstone behavior tick for tick). Two more
editions trade some vanilla behavior for speed, like Paper and Pufferfish do.

Measured on a laptop (i7-11800H) with `tools/bench/full.sh`, one run each (differences under
~10% are noise). "Java vanilla" is the same Fabric server with every Vantage change off; the
second column adds the usual Fabric performance mods (Lithium, C2ME, ScalableLux, FerriteCore,
Krypton). Factors: how much faster than Java vanilla. Vantage columns without bundled mods.

| Scenario | Java vanilla | Java vanilla + performance mods | Vantage Vanilla | Vantage Balanced | Vantage Performance |
|---|---|---|---|---|---|
| Chunky pregeneration, chunks/s (higher is better) | 143.8 | 245.2 (1.71x) | 312.7 (2.17x) | 316.4 (2.20x) | 303.2 (2.11x) |
| 10 players flying over a world, ms/tick | 44.8 | 41.3 (1.08x) | 42.3 (1.06x) | 36.4 (1.23x) | 31.1 (1.44x) |
| 1500 mobs next to a player, ms/tick | 59.5 | 31.6 (1.88x) | 33.9 (1.75x) | 31.6 (1.89x) | 33.2 (1.79x) |
| 1500 mobs with no player nearby (e.g. a farm), ms/tick | 44.6 | 27.5 (1.62x) | 28.1 (1.58x) | 23.7 (1.88x) | 15.2 (2.94x) |
| 320 redstone clocks, ms/tick | 48.3 | 29.0 (1.67x) | 25.6 (1.88x) | 24.4 (1.98x) | 9.1 (5.28x) |
| 1600 hoppers, ms/tick | 2.0 | 0.7 (2.68x) | 1.7 (1.21x) | 1.9 (1.08x) | 1.5 (1.31x) |
| trading hall, 1000 boxed villagers, ms/tick | 40.3 | 20.9 (1.92x) | 43.1 (0.93x) | 28.1 (1.43x) | 14.0 (2.88x) |
| crop farm, fast random ticks, ms/tick | 1.2 | 1.3 (0.98x) | 1.3 (0.96x) | 1.5 (0.84x) | 1.3 (0.92x) |

With the mods bundled in the downloads (Lithium in balanced and performance), performance
measures 8.4 ms in the trading hall and 0.87 ms with the hoppers. Mobs right next to players
gain nothing more from Balanced and Performance: their mob changes only act on mobs away from
players. (Balanced redstone was re-measured; another app disturbed its first run.)

## Editions
Three separate builds (release downloads), one per edition; the edition is built in
(`MCRS_EDITION=balanced cargo build --release`) and can still be overridden with `edition` in
`mcrs.toml`:

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

- **ServerCore-like tuning**: dynamic simulation and view distance (at a mean tick above 40 ms
  simulation and then view distance drop by one every 30 s, down to 4 and 6; below 30 ms they
  come back), villager lobotomy (villagers boxed in, as in trading halls, or riding run their
  brain every 20th tick; trading and restocking still work), breeding cap (no breeding with 32
  of a kind within 64 blocks), larger merge radius for items (1.5 blocks) and experience orbs
  (2 blocks).
- **Approximate terrain, like Pumpkin**: fewer noise octaves and coarser interpolation cells.
  Biomes and structures follow the seed exactly (biomes are sampled with an exact copy of the
  noises) and the terrain is roughly the same (surface height equal in ~40% of columns, 1.7
  blocks off on average, top block equal in ~92%), but not block for block
  (`-Dmcrs.approxWorldgen=0` turns it off, 1-3 choose the level).
- **Region files compressed with libdeflate**: same chunk data, files not byte-identical.

Fabric mods work in all three editions; in vanilla and balanced, world generation is exact.

### Chunk generation
Terrain (noise, aquifers, surface rules), biomes (climate sampling and vanilla's R-tree) and
carving are computed in Rust, verified against vanilla: biomes on 23.6M quarts (identical except
exact ties, where vanilla's own answer depends on the thread's previous search), terrain with
carving on 11.5k chunks block for block. Chunks are serialized and compressed in parallel
instead of on vanilla's single chunk IO thread (byte-identical files;
`-Dmcrs.parallelSave=false` turns it off), and Chunky gets 500 chunks in flight instead of 50
(`-Dchunky.maxWorkingCount`).

### Network
Packet compression uses libdeflate through Rust (like Krypton's native compression): about 3.5x
the speed of `java.util.zip.Deflater` at the same level and ratio, ordinary zlib for clients
(`-Dmcrs.nativeCompression=false` turns it off). `/mcrs compresstest` round-trips samples
through Java's inflater.

### Other performance mods
Tested to start and pregenerate together with Lithium, FerriteCore, Krypton, ScalableLux,
ServerCore, C2ME and Moonrise. Where a mod replaces a whole area, Vantage leaves it to that
mod: the light engine to Moonrise, ScalableLux or Starlight; chunk IO and the chunk pipeline to
Moonrise or C2ME. Measured on top of Vantage: Lithium makes boxed villagers about 1.5x and
hoppers about 2x faster (little elsewhere, Vantage covers the same areas), FerriteCore uses
18% less heap, ScalableLux pregenerates about 4% faster. In our tick-exactness harness
Vantage Vanilla with FerriteCore stays identical to vanilla, but Lithium does not (already
on its own: two entities fewer right at the start of the mob scenario). So the release
downloads bundle FerriteCore in all editions and Lithium and ScalableLux (its light engine
fixes vanilla lighting bugs, so light can differ slightly) only in balanced and performance.

Every change has its own switch (JVM option in `jvm_options`) that overrides the edition, e.g.
`-Dmcrs.redstoneExperiments=false`, `-Dmcrs.brainThrottle=false`, `-Dmcrs.villagerLobotomy=false`,
`-Dmcrs.activationRange=true`; the exact optimizations can be turned off too, e.g.
`-Dmcrs.worldgen=false`, `-Dmcrs.biomes=false`, `-Dmcrs.carvers=false`, `-Dmcrs.fastEntities=false`.

Benchmarks: `tools/bench/full.sh` (all scenarios, all editions, Java vanilla and Java vanilla with
the usual performance mods), `tools/bench/chunky.sh` (Chunky pregeneration, `EXTRA_MODS` for
other mods; `/mcrs genstats`), `tools/bench/gen.sh` (generation),
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
