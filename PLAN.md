# Project Plan: Rust-Accelerated Fabric Server

**Goal:** A Minecraft server that behaves 100% like vanilla, runs Fabric mods, and is much faster
at **chunk loading** and **entity processing** through a multithreaded Rust core.

---

## 1. Core Principles

1. **Vanilla parity is checked by tests, not assumed.** Every Rust subsystem must produce
   bit-identical results to vanilla. Differential tests (section 7) prove it.
2. **Rust owns the hot paths; the JVM owns compatibility.** Anything a mod touches falls back to Java
   automatically.
3. **Parallelize data, not code order.** Vanilla behavior depends on execution order. Parallelism
   is only allowed where the order provably doesn't matter, or where vanilla's order can be rebuilt.
4. **Pin one Minecraft version.** Pick the latest stable release at project start. Port to new
   versions only after 1.0.
5. **No Mojang code in the repo.** Study the jars with Mojang's official mappings and rewrite the logic.
   Download `server.jar` from Mojang at runtime, like Fabric does.

---

## 2. Architecture

```
┌────────────────────────────────────────────────────────────────┐
│ mcrs (Rust binary, owns the process)                           │
│                                                                │
│  ┌──────────────┐ ┌──────────────┐ ┌────────────────────────┐  │
│  │ Network      │ │ Chunk System │ │ Entity System          │  │
│  │ tokio, zero- │ │ async I/O,   │ │ region-parallel tick,  │  │
│  │ copy packets │ │ parallel gen │ │ SoA storage            │  │
│  └──────────────┘ └──────────────┘ └────────────────────────┘  │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │ World State (single source of truth, off-heap memory)    │  │
│  │ chunk sections, palettes, heightmaps, light, entities    │  │
│  └──────────────────────────────────────────────────────────┘  │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │ Scheduler: tick phases, region graph, rayon thread pool  │  │
│  └──────────────────────────────────────────────────────────┘  │
├────────────────────────────────────────────────────────────────┤
│ Bridge: Panama FFM (Java 21+) — much faster than JNI           │
│  + shared off-heap memory (Java reads chunk data directly)     │
├────────────────────────────────────────────────────────────────┤
│ Embedded JVM (started by Rust via invocation API)              │
│  Fabric Loader + Mixin                                         │
│  mcrs-bridge (our own Fabric mod; redirects vanilla to Rust)   │
│  vanilla server.jar (fallback logic) + user's mods             │
└────────────────────────────────────────────────────────────────┘
```

### The key trick: the bridge mod
We ship our own Fabric mod, `mcrs-bridge`. It uses Mixins to rewire vanilla:
- `PalettedContainer` / `ChunkSection` store their data in **off-heap memory owned by Rust**.
  Java code (vanilla fallback + mods) reads blocks without any JNI call.
- `ServerChunkManager` / `ChunkMap` call the Rust chunk system instead of loading chunks themselves.
- `ServerWorld.tickEntities` calls the Rust entity scheduler. That scheduler calls back into Java only
  for entity types that mods have changed.

Other mods keep working because the vanilla classes and methods still exist. Only their storage
and scheduling are redirected.

### Mixin Conflict Scanner
At startup, read every mod's `*.mixins.json` and scan the target classes:
- Builds a map: `vanilla class/method → mods touching it`.
- For each Rust subsystem, decides **Rust** or **Java fallback**, per entity type and
  per worldgen feature.
- Prints a report: "Zombie: Java (modified by mod X)", "Lighting: Rust", ...

---

## 3. Chunk System (highest priority)

### 3.1 Pipeline
Each chunk goes through stages. Each stage runs on a thread pool:

```
request → [Disk I/O] → [Decompress] → [NBT parse] → [Upgrade/DFU] → ready
              │ (missing)
              ▼
        [Worldgen: structure_starts → biomes → noise → surface → carvers
                   → features → light] → ready
```

| Stage | Threading | Notes |
|---|---|---|
| Region file I/O | tokio, io_uring (Linux) / IOCP (Windows) | Batched reads, sector cache |
| Decompression | rayon | zlib (`zlib-rs`), LZ4; parallel per chunk |
| NBT parse | rayon | Zero-copy parser straight into palette structures |
| Data upgrade (DFU) | Java fallback at first | Only needed for old worlds; port later |
| Noise / biomes / surface | rayon, fully parallel | Each chunk is independent |
| Carvers | rayon | Independent per chunk |
| Features | Parallel with **3×3 neighbor locking** | Features write into neighbor chunks |
| Lighting | Parallel per section group | Starlight-style algorithm, results must match vanilla |
| Saving | Background thread, copy-on-write snapshot | Never blocks the tick |

### 3.2 Worldgen Determinism (critical for 100% vanilla)
- Rebuild exactly: `java.util.Random` (LCG), `Xoroshiro128PlusPlus`, `LegacyRandomSource`,
  `PositionalRandomFactory`, `WorldgenRandom` seeding (decoration seed, feature seed).
- Vanilla seeds randomness **per chunk/feature position**, so parallel generation gives the same
  result as vanilla (C2ME already shows this works).
- Floats: Rust `f64`/`f32` are IEEE 754 like Java. Rebuild `Mth.sin`/`cos` (lookup table),
  `Mth.floor`, and the `StrictMath` functions exactly. Avoid FMA and `-ffast-math` style shortcuts.
- **Density functions are data:** vanilla worldgen is driven by datapacks (JSON). Write a
  density-function interpreter → later JIT-compile it to native code (Cranelift) for a big
  speedup.

### 3.3 Fabric Mod Compatibility in Worldgen
- **Data-driven mods** (new biomes, structures, features via datapack JSON) → run fully in Rust.
  Most worldgen mods (Terralith, Tectonic, etc.) are mainly datapacks.
- **Custom Java `Feature` / `ChunkGenerator` classes** → Rust calls the Java class for that one
  feature through the bridge. Everything else stays in Rust.
- Mods that mixin into the noise or chunk-gen pipeline → that stage falls back to Java.

### 3.4 Targets
- Loading existing chunks: **5–10×** faster than vanilla, scales with cores.
- Generating new chunks: at least as fast as C2ME, better with many cores.
- Main thread never waits on chunk I/O.

---

## 4. Entity System (second priority)

### 4.1 The Problem
Vanilla ticks all entities **one after another in a fixed order** on one thread. Entities
interact (pushing, AI targets, item pickup, damage). Naive multithreading changes the results
and causes race conditions.

### 4.2 Solution: Region-Based Parallelism (Folia-style)
- Split the world into **regions**: groups of loaded chunks that are far apart from each other
  (e.g. > 8 chunks gap).
- Regions **tick in parallel**. Inside a region, entities tick in **vanilla order**.
- Regions **merge** when they get close and **split** when they move apart.
- Cross-region actions (portals, teleports, projectiles crossing borders) go through a queue
  and are applied at a sync point at the end of the tick.

**Parity note:** within a region, the result is identical to vanilla. Across regions, vanilla's
global order is gone, but entities that are hundreds of blocks apart can't affect each other within
one tick. The few global effects (raids, scoreboard, `/execute` across worlds, global RNG)
are handled in a **serial phase**.

**Strict mode:** config flag `parallel_regions = false` → everything single-threaded, exact
vanilla order (for debugging and parity tests).

### 4.3 Inside a Region
- **Data layout:** entity data stored as Structure of Arrays (position, velocity, bounding box
  in separate arrays) → cache-friendly, SIMD for movement and collisions.
- **Parallel sub-phases** where order doesn't matter (all results are read-only first,
  applied later):
  - Collision broad-phase (spatial hash grid)
  - Pathfinding (A* per mob, results applied in vanilla order)
  - Target / sensor searches (`getEntities` in an area, `Brain` sensors)
- **Serial phase in vanilla order:** apply movement, damage, AI decisions, despawning.

### 4.4 Rust vs. Java per Entity Type
- The Mixin scanner checks each entity class (`Zombie`, `Villager`, ...).
- Entity type not touched by mods → **Rust implementation**.
- Entity type modified by a mod, or added by a mod → **Java tick through the bridge**. Its region
  calls Java at the right point in the order, so the order stays correct.
- Port order (by typical server load): items/XP orbs → villagers → mobs from farms
  (zombies, skeletons, iron golems, cows/chickens) → hoppers & minecarts → the rest.

### 4.5 Mod Thread Safety
Many mods assume there is only one main thread. Solution:
- A mod whose mixins touch entity/world tick code → the regions it touches run on the
  **"Java lane"**, a single thread with vanilla order.
- Per-mod overrides in config (`thread_safe = true/false`), plus a community list of
  tested mods.

---

## 5. Other Subsystems (later)

| Subsystem | Phase | Approach |
|---|---|---|
| Networking | 2 | tokio; compression and encryption on a thread pool; packet batching |
| Chunk sending to players | 2 | Serialize chunk packets in parallel, rate-limit per player |
| Block entities (hoppers, furnaces) | 5 | Region-parallel like entities |
| Redstone | 6 | Serial inside a region, exact vanilla update order (no "fast redstone") |
| Random ticks / fluid ticks | 5 | Per region, vanilla order |
| Commands, advancements, recipes | — | Stay in Java; not performance-critical |

---

## 6. Tech Stack

| Area | Choice |
|---|---|
| Language | Rust (stable), Java 21+ for the bridge mod |
| Async I/O | `tokio` |
| Parallel compute | `rayon` (custom thread pools per subsystem) |
| JVM embedding | `jni` crate (invocation API) + Panama FFM for hot calls |
| Compression | `zlib-rs`, `lz4_flex` |
| NBT | Custom zero-copy parser (or fork from Pumpkin/Valence) |
| Density function JIT | `cranelift` |
| Profiling | `tracy`, `perf`, spark (Java side) |
| Bridge mod build | Gradle + Fabric Loom |

### Repository Layout
```
server-project/
├── crates/
│   ├── mcrs/              # main binary, launcher, config
│   ├── mcrs-jvm/          # JVM embedding, FFM/JNI bridge
│   ├── mcrs-world/        # world state, off-heap chunk storage
│   ├── mcrs-chunk/        # chunk pipeline, region files, NBT
│   ├── mcrs-worldgen/     # noise, density functions, features, RNG
│   ├── mcrs-light/        # lighting engine
│   ├── mcrs-entity/       # entity storage, region scheduler, AI
│   ├── mcrs-net/          # networking
│   ├── mcrs-mixinscan/    # mixin conflict scanner
│   └── mcrs-parity/       # differential testing tools
├── bridge-mod/            # Fabric mod (Java), Mixins into vanilla
├── tools/                 # jar analysis, mapping tools
└── tests/
```

---

## 7. Proving 100% Vanilla: Differential Testing

This is what makes "100% vanilla" more than a slogan. Build it **from the start**.

1. **Worldgen parity:** generate the same seeds and areas in vanilla and in mcrs, then compare
   every block, biome, heightmap, and light value. Run in CI on hundreds of seeds and all
   dimensions.
2. **Chunk I/O round-trip:** load → save → byte-compare the NBT against vanilla's output.
3. **Tick replay:** record a vanilla world plus player inputs, replay them in mcrs, and compare
   the full entity state every tick. The first difference stops the test and prints a diff.
4. **Farm test suite:** a collection of known technical-Minecraft farms (iron farm, gold farm,
   villager breeder, mob farms, TNT duper, zero-tick and update-order contraptions) must produce
   the same rates and behavior.
5. **Mod compatibility CI:** boot the top ~100 Fabric mods (Modrinth), run test worlds, and check
   that nothing crashes and the Rust/Java decisions are as expected.
6. **Benchmarks in CI:** chunk loading/generation speed, MSPT with N entities, compared against
   vanilla + Lithium + C2ME. Regressions fail the build.

---

## 8. Roadmap

| Phase | Content | Milestone |
|---|---|---|
| **0: Foundation** | Cargo workspace, Rust launcher boots Fabric + mods in embedded JVM, bridge mod skeleton, benchmark harness | `cargo run` → working Fabric server |
| **1: Shared world memory** | Off-heap chunk storage, bridge mod redirects `PalettedContainer` / `ChunkSection` | Vanilla runs unchanged on Rust-owned memory |
| **2: Chunk I/O** | Region files, decompression, NBT, async load/save pipeline, parallel chunk sending | Existing worlds load 5×+ faster, round-trip parity |
| **3: Worldgen** | RNG, noise, density functions, biomes, surface, carvers | Bit-identical terrain for vanilla seeds |
| **4: Worldgen II** | Structures, features (3×3 locking), lighting, Java feature fallback, density JIT | Full worldgen in Rust, mod worldgen works |
| **5: Entity foundation** | SoA storage, region scheduler (strict mode first), mixin scanner per entity type, Java lane | Entities tick through Rust scheduler with exact parity |
| **6: Entity parallel** | Parallel regions, parallel collisions/pathfinding, port entity types one by one | Large MSPT reduction on entity-heavy servers |
| **7: Block entities, ticks, redstone** | Hoppers, furnaces, random/fluid ticks, redstone (serial) | Farm test suite passes |
| **8: Hardening → 1.0** | Mod compat list, docs, config, crash reporting | Public release |

Rough effort: phases 0–4 are a solid **1–2 years for a solo developer**, phases 5–8 another
**1–2 years**. Each phase already makes the server faster on its own, so it can be released
and used early.

---

## 9. Risks & Mitigations

| Risk | Mitigation |
|---|---|
| Hidden vanilla quirks break parity | Differential testing from day 1; strict mode |
| Mods aren't thread-safe | Mixin scanner + Java lane + per-mod overrides |
| Bridge overhead eats the speed gains | Shared off-heap memory, FFM instead of JNI, batch calls, never call Java per block |
| Mods that mixin into chunk storage (e.g. other performance mods) | Mark as incompatible; mcrs replaces them (Lithium/C2ME/Starlight not needed) |
| Minecraft updates change internals | Pin one version; mapping-diff tooling to speed up ports |
| Legal (Mojang EULA) | No Mojang code in repo; runtime jar download; clean rewrites only |
| Scope too big | Ship every phase as a usable release; chunk system alone is already valuable |

---

## 10. Progress & Baseline

**Target:** Minecraft 26.3 (unobfuscated: real class names, no mappings needed), Fabric Loader
0.19.5, Java 25, Rust 1.99.

**Phase 0 — done.** `cargo run` boots Fabric inside an embedded JVM; the bridge mod binds Rust
functions via Panama FFM; Rust logs TPS/MSPT; `/mcrs bench` measures chunks and entities.

**Baseline** (plain Fabric, nothing in Rust yet; 16 threads, ZGC, 4 GB):

| Benchmark | Result |
|---|---|
| `/mcrs bench chunks 16` (generate 1089 chunks) | 82.8 chunks/s |
| `/mcrs bench chunks 16 <x> <z>` (load the same 1089 from disk, after restart) | 939 chunks/s |
| `/mcrs bench entities minecraft:cow 1000` | MSPT avg 11.43, p95 13.98 |
| `/mcrs bench entities minecraft:villager 300` | MSPT avg 9.61, p95 11.71 |
| Rust `mcrs-chunk` scan: read + decompress (warm cache) | 21k chunks/s 1 thread, 77k chunks/s 16 threads |

**Findings that change the plan:**
- Vanilla's `IOWorker` serializes all region reads + decompression + NBT parsing of a dimension on
  one `PriorityConsecutiveExecutor`.
- Raw read + decompress is ~20× faster than vanilla's whole load path even on one thread, so the
  cost is in the Java side (NBT → objects → `SerializableChunkData` → `ProtoChunk`), not in zlib.
- Therefore **chunk I/O (Phase 2) comes before off-heap storage (Phase 1)**: Rust parses chunk
  NBT straight into the palette + `long[]` layout vanilla already uses, Java only copies arrays.
  Vanilla's storage classes stay untouched, which keeps performance mods like Lithium working.
  Off-heap storage is revisited when entity work needs it.

**Phase 2a: Rust chunk reading (done).** `IOWorker.loadAsync` for chunk storage goes to Rust on a
parallel pool (pending writes still served by vanilla). Rust decompresses, parses the NBT and replaces
each section palette with global ids (`mcrs:palette`); the bridge mod builds containers with vanilla's
own `PalettedContainer.unpack`. Ids come from the running server (modded blocks and datapack biomes
included). Anything Rust can't resolve exactly goes to vanilla unchanged.

| Check | Result |
|---|---|
| Parity (`-Dmcrs.chunkio.verify=true`), every container decoded both ways | 274,272 compared, 0 mismatches |
| Palettes resolved by Rust (offline, whole test world) | 880,512 / 880,512 |
| Load 4225 chunks, vanilla (`-Dmcrs.chunkio=false`), 2 runs | 1178, 1116 chunks/s |
| Load 4225 chunks, mcrs, 2 runs | 1820, 1839 chunks/s (**1.6×**) |

Note: 26.x palettes store default states as bare names and others as `{id, properties}` (not the old
`{Name, Properties}`), and NBT lists can be heterogeneous (strings wrapped as `{"": ...}`).

**Phase 2b: block counts (done).** Rust also computes `LevelChunkSection` block counts while it has
the palette; Java skips the 4096-block scan. Verified on 137k sections. Warm A/B (JIT warmed up
by a first load): vanilla ~1630 chunks/s, mcrs ~2340 chunks/s (1.44×). The remaining loading cost
is mostly main-thread work (`SerializableChunkData.read`) that vanilla deliberately keeps there.
Loading is no longer the player-facing bottleneck (~2300 chunks/s vs ~95 chunks/s generation).

**Phase 3: worldgen port (in progress)** in `crates/mcrs-worldgen`, checked against vanilla by
`tools/oracle` (runs vanilla's own classes and writes reference values):

| Module | Oracle check |
|---|---|
| Random sources, seeding, `Mth.getSeed` | 538 values identical |
| fdlibm `log/exp/pow/sin/cos` (`jmath`) | 1,200,204 values identical |
| Perlin, smeared Perlin, simplex, `NoiseStack`, `NormalNoise`, blended fbm | 12,744 values identical |

Decision: HotSpot's `Math.log/exp/pow/sin/cos` intrinsics differ from fdlibm in 2-10% of inputs and
between x86 and ARM, so vanilla itself is not deterministic across platforms. mcrs starts the JVM
with `-XX:-UseLibmIntrinsic` (config `deterministic_math`), which makes `Math` equal `StrictMath`
(0 differences in 2M inputs) and lets the Rust port match bit for bit.

26.x specifics: density functions compute in `float`; `PerlinNoise.get` and `addToVolume` sum dot
products in different orders (both ported as written); `NormalNoise` uses `DoubleStream.sum`
(Kahan summation).

**Phase 3b: generation speed (current).** Measured with `tools/bench/gen.sh` (fresh world, fixed
seed, four fixed areas), vanilla and mcrs back to back:

| Area | Vanilla | mcrs |
|---|---|---|
| 1 (cold JIT) | 69.6 chunks/s | 117.0 chunks/s |
| 2 | 80.8 | 164.5 |
| 3 | 107.2 | 194.5 |
| 4 | 119.1 | 253.1 (2.1×) |

What got it there:
- **Parallel features:** vanilla runs the FEATURES step on one serial executor (workers were ~6.5 of
  15 busy). It now runs on the worker pool behind a lock over the 3×3 chunks and every structure
  start it touches; the shared `StructureTemplate$Palette` cache is made concurrent. Vanilla's own
  output already depends on feature order (two vanilla runs differ in 2,643 of 4,357 chunks); every
  parallel result is an order vanilla could also produce.
- **Terrain fill and surface in Rust:** `doFill`, aquifer, Beardifier and `MaterialSystem` run
  in Rust, verified identical to vanilla (blocks, heightmaps, post-processing, density caches,
  aquifer state) on 4,050+ chunks. On their own they're about as fast as Java's JIT;
  the gain comes from skipping Java's per-block `setBlockState` path and exact shortcuts.

## 11. Next Steps

1. Density function framework (`densityfunction/*`, ~4300 lines): parse the vanilla datapack's
   `worldgen/density_function` and `noise_settings` JSON, compile to samplers, oracle-test every type.
2. `NoiseChunk` / `NoiseBasedChunkGenerator.doFill` with aquifers and ore veins → first Rust-filled
   terrain, compared block by block against vanilla chunks.
3. Surface (`MaterialSystem`), then hook into the chunk pipeline behind a verify mode.
4. Mixin conflict check: disable fast paths when another mod targets the same vanilla code.
