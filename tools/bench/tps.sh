#!/usr/bin/env sh
# Short TPS comparison of everything: vanilla vs mcrs (vs mcrs + activation range for players).
#   tools/bench/tps.sh [ticks]      (default 600 ticks = 30 s per run, ~15 min in total)
# TPS = 1000 / max(50 ms, mean tick time), as the server can't tick faster than 20/s.
set -u
cd "$(dirname "$0")/../.."
T=${1:-600}
VANILLA="-Dmcrs.chunkio=false -Dmcrs.fastBlocks=false -Dmcrs.fastEntities=false -Dmcrs.fastLight=false -Dmcrs.fastOres=false -Dmcrs.parallelFeatures=false -Dmcrs.worldgen=false"
OUT=target/tps-results.txt
: > "$OUT"
mean_of() { sed 's/\x1b\[[0-9;]*m//g' "$1" | grep -ao 'ticks, mean [0-9.]*' | tail -1 | grep -o '[0-9.]*$'; }
row() { # scenario variant log
  m=$(mean_of "$3")
  if [ -z "$m" ]; then printf '%-34s %-26s %10s\n' "$1" "$2" "failed" | tee -a "$OUT"; return; fi
  printf '%-34s %-26s %7.1f ms %6.1f TPS\n' "$1" "$2" "$m" "$(awk -v m="$m" 'BEGIN { print 1000 / (m < 50 ? 50 : m) }')" | tee -a "$OUT"
}
harness() { # scenario size label-prefix description
  sh tools/bench/tick.sh "$1" "$2" "$T" false "$VANILLA" "tps-$3-v" >/dev/null 2>&1; row "$4" vanilla "target/tick-server/tick-tps-$3-v.log"
  sh tools/bench/tick.sh "$1" "$2" "$T" false "" "tps-$3-m" >/dev/null 2>&1; row "$4" mcrs "target/tick-server/tick-tps-$3-m.log"
}
printf '%-34s %-26s %10s %10s\n' scenario variant "mean tick" TPS | tee -a "$OUT"
sh tools/bench/bots.sh 10 0.5 "$T" "$VANILLA" tps-bots-v >/dev/null 2>&1; row "10 players flying" vanilla target/bot-server/bots-tps-bots-v.log
sh tools/bench/bots.sh 10 0.5 "$T" "" tps-bots-m >/dev/null 2>&1; row "10 players flying" mcrs target/bot-server/bots-tps-bots-m.log
sh tools/bench/bots.sh 10 0.5 "$T" "-Dmcrs.activationRange=true" tps-bots-a >/dev/null 2>&1; row "10 players flying" "mcrs + activation range" target/bot-server/bots-tps-bots-a.log
harness mobs 1500 mobs "1500 mobs in a pen"
harness redstone 320 red "320 redstone clocks"
harness farm 20 farm "crop farm (fast random ticks)"
echo "saved to $OUT"
