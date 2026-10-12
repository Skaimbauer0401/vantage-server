#!/usr/bin/env sh
# Full comparison: Java vanilla, Java vanilla with the usual Fabric performance mods, and
# Vantage Vanilla, Balanced and Performance, every scenario.
#   tools/bench/full.sh [ticks] [chunky seconds] [performance mods dir]
# (defaults 600 ticks, 60 s; about 80 minutes). The mods dir holds Lithium, C2ME, ScalableLux,
# FerriteCore and Krypton jars for the "Java vanilla + performance mods" column; without it
# that column is skipped.
# "Java vanilla" is the same Fabric server with every Vantage change switched off, so all
# runs share the JDK, Fabric and settings. Results: target/full-results.md (mean tick time in
# ms, lower is better; Chunky in chunks/s, higher is better).
set -u
cd "$(dirname "$0")/../.."
T=${1:-600}
CS=${2:-60}
MODS_DIR=${3:-}
JAVA="-Dmcrs.chunkio=false -Dmcrs.fastBlocks=false -Dmcrs.fastEntities=false -Dmcrs.fastLight=false -Dmcrs.fastOres=false -Dmcrs.parallelFeatures=false -Dmcrs.worldgen=false -Dmcrs.parallelSave=false -Dmcrs.edition=vanilla -Dmcrs.approxWorldgen=0"
VAN="-Dmcrs.edition=vanilla -Dmcrs.approxWorldgen=0"
BAL="-Dmcrs.edition=balanced -Dmcrs.approxWorldgen=0"
PERF="-Dmcrs.edition=performance -Dmcrs.approxWorldgen=3"
VARIANTS="java van bal perf"
[ -n "$MODS_DIR" ] && VARIANTS="java mods van bal perf"
OUT=target/full-results.md
RAW=target/full-raw.txt
: > "$RAW"
clean() { sed 's/\x1b\[[0-9;]*m//g' "$1"; }
harness_mean() { clean "$1" | grep -a '\[harness\]' | grep -ao 'ticks, mean [0-9.]*' | tail -1 | grep -o '[0-9.]*$'; }
bots_mean() { clean "$1" | grep -a '\[bots\]' | grep -ao 'ticks, mean [0-9.]*' | tail -1 | grep -o '[0-9.]*$'; }
chunky_rate() { clean "$1" | grep -ao 'Rate: [0-9,.]* cps' | tail -1 | grep -o '[0-9,.]*' | tr ',' '.'; }
record() { echo "$1|$2|${3:-failed}" | tee -a "$RAW"; }

for v in $VARIANTS; do
  unset EXTRA_MODS
  case $v in java) O=$JAVA ;; mods) O=$JAVA; EXTRA_MODS="$(ls "$MODS_DIR"/*.jar | tr '
' ' ')"; export EXTRA_MODS ;; van) O=$VAN ;; bal) O=$BAL ;; perf) O=$PERF ;; esac
  sh tools/bench/chunky.sh "$O" "full-$v" "$CS" >/dev/null 2>&1
  record chunky "$v" "$(chunky_rate target/chunky-server/chunky-full-$v.log)"
  sh tools/bench/bots.sh 10 0.5 "$T" "$O" "full-$v" >/dev/null 2>&1
  record players "$v" "$(bots_mean target/bot-server/bots-full-$v.log)"
  sh tools/bench/tick.sh mobs 1500 "$T" false "$O -Dmcrs.harness.watcher=true" "full-near-$v" >/dev/null 2>&1
  record mobs-near "$v" "$(harness_mean target/tick-server/tick-full-near-$v.log)"
  sh tools/bench/tick.sh mobs 1500 "$T" false "$O" "full-far-$v" >/dev/null 2>&1
  record mobs-far "$v" "$(harness_mean target/tick-server/tick-full-far-$v.log)"
  sh tools/bench/tick.sh redstone 320 "$T" false "$O" "full-red-$v" >/dev/null 2>&1
  record redstone "$v" "$(harness_mean target/tick-server/tick-full-red-$v.log)"
  sh tools/bench/tick.sh hoppers 1600 "$T" false "$O" "full-hop-$v" >/dev/null 2>&1
  record hoppers "$v" "$(harness_mean target/tick-server/tick-full-hop-$v.log)"
  sh tools/bench/tick.sh tradinghall 1000 "$T" false "$O -Dmcrs.harness.watcher=true" "full-trade-$v" >/dev/null 2>&1
  record tradinghall "$v" "$(harness_mean target/tick-server/tick-full-trade-$v.log)"
  sh tools/bench/tick.sh farm 20 "$T" false "$O" "full-farm-$v" >/dev/null 2>&1
  record farm "$v" "$(harness_mean target/tick-server/tick-full-farm-$v.log)"
done

python - "$RAW" "$OUT" <<'PY'
import sys
raw, out = sys.argv[1], sys.argv[2]
data = {}
for line in open(raw):
    s, v, val = line.strip().split("|")
    data[(s, v)] = val
rows = [
    ("chunky", "Chunky pregeneration, chunks/s (higher is better)", True),
    ("players", "10 players flying over a world, ms/tick", False),
    ("mobs-near", "1500 mobs next to a player, ms/tick", False),
    ("mobs-far", "1500 mobs with no player nearby (e.g. a farm), ms/tick", False),
    ("redstone", "320 redstone clocks, ms/tick", False),
    ("hoppers", "1600 hoppers, ms/tick", False),
    ("tradinghall", "trading hall, 1000 boxed villagers, ms/tick", False),
    ("farm", "crop farm, fast random ticks, ms/tick", False),
]
cols = [c for c in ["java", "mods", "van", "bal", "perf"] if any(k[1] == c for k in data)]
titles = {"java": "Java vanilla", "mods": "Java vanilla + performance mods", "van": "Vantage Vanilla", "bal": "Vantage Balanced", "perf": "Vantage Performance"}
lines = ["| Scenario | " + " | ".join(titles[c] for c in cols) + " |", "|---" * (len(cols) + 1) + "|"]
for key, name, higher in rows:
    base = data.get((key, "java"), "failed")
    cells = []
    for c in cols:
        val = data.get((key, c), "failed")
        try:
            x, b = float(val), float(base)
            if c == "java":
                cells.append(f"{x:.1f}")
            else:
                factor = x / b if higher else b / x
                cells.append(f"{x:.1f} ({factor:.2f}x)")
        except ValueError:
            cells.append(val)
    lines.append(f"| {name} | " + " | ".join(cells) + " |")
open(out, "w").write("\n".join(lines) + "\n")
print("\n".join(lines))
PY
