#!/usr/bin/env sh
# Short TPS comparison: plain vanilla (all mcrs optimizations off) vs the three editions.
#   tools/bench/tps.sh [ticks]      (default 600 ticks = 30 s per run, ~25 min in total)
# TPS = 1000 / max(50 ms, mean tick time), as the server can't tick faster than 20/s.
set -u
cd "$(dirname "$0")/../.."
T=${1:-600}
VANILLA="-Dmcrs.chunkio=false -Dmcrs.fastBlocks=false -Dmcrs.fastEntities=false -Dmcrs.fastLight=false -Dmcrs.fastOres=false -Dmcrs.parallelFeatures=false -Dmcrs.worldgen=false"
OUT=target/tps-results.txt
: > "$OUT"
# The last summary line of a run: the harness's, or the bots' if there is no harness.
mean_of() {
  clean=$(sed 's/\x1b\[[0-9;]*m//g' "$1")
  m=$(printf '%s\n' "$clean" | grep -a '\[harness\]' | grep -ao 'ticks, mean [0-9.]*' | tail -1 | grep -o '[0-9.]*$')
  [ -n "$m" ] || m=$(printf '%s\n' "$clean" | grep -a '\[bots\]' | grep -ao 'ticks, mean [0-9.]*' | tail -1 | grep -o '[0-9.]*$')
  echo "$m"
}
row() { # scenario variant log
  m=$(mean_of "$3")
  if [ -z "$m" ]; then printf '%-30s %-22s %10s\n' "$1" "$2" "failed" | tee -a "$OUT"; return; fi
  printf '%-30s %-22s %7.1f ms %6.1f TPS\n' "$1" "$2" "$m" "$(awk -v m="$m" 'BEGIN { print 1000 / (m < 50 ? 50 : m) }')" | tee -a "$OUT"
}
harness() { # scenario size label description extra-options
  sh tools/bench/tick.sh "$1" "$2" "$T" false "$VANILLA $5" "tps-$3-v" >/dev/null 2>&1; row "$4" "plain vanilla" "target/tick-server/tick-tps-$3-v.log"
  sh tools/bench/tick.sh "$1" "$2" "$T" false "$5" "tps-$3-m" >/dev/null 2>&1; row "$4" "vantage vanilla" "target/tick-server/tick-tps-$3-m.log"
  sh tools/bench/tick.sh "$1" "$2" "$T" false "-Dmcrs.edition=performance $5" "tps-$3-p" >/dev/null 2>&1; row "$4" "vantage performance" "target/tick-server/tick-tps-$3-p.log"
}
bots() { # edition-options variant label
  sh tools/bench/bots.sh 10 0.5 "$T" "$1" "tps-bots-$3" >/dev/null 2>&1; row "10 players flying" "$2" "target/bot-server/bots-tps-bots-$3.log"
}
printf '%-30s %-22s %10s %10s\n' scenario variant "mean tick" TPS | tee -a "$OUT"
bots "$VANILLA" "plain vanilla" v
bots "" "vantage vanilla" m
bots "-Dmcrs.edition=balanced" "vantage balanced" b
bots "-Dmcrs.edition=performance" "vantage performance" p
harness mobs 1500 mobs "1500 mobs next to a player" "-Dmcrs.harness.watcher=true"
harness redstone 320 red "320 redstone clocks" ""
harness hoppers 1600 hop "1600 hoppers" ""
echo "saved to $OUT"
