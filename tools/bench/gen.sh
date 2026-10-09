#!/usr/bin/env sh
# Reproducible generation benchmark: fresh world with a fixed seed, fixed areas.
#   tools/bench/gen.sh "<extra JVM options>" [label]
# Uses target/bench-server as its own server directory (EULA must be accepted there).
set -eu
cd "$(dirname "$0")/../.."
DIR=target/bench-server
mkdir -p "$DIR"
rm -rf "$DIR/world"
cat > "$DIR/server.properties" <<PROPS
level-seed=8682522807148012
level-name=world
online-mode=false
spawn-protection=0
PROPS
[ -f "$DIR/eula.txt" ] || cp run/eula.txt "$DIR/eula.txt"
LOG="$DIR/bench-${2:-run}.log"
( sleep 20
  for c in "0 2000" "2000 0" "-2000 -2000" "3000 3000"; do echo "mcrs bench chunks 16 $c"; sleep 20; done
  echo stop; sleep 30 ) | MCRS_RUN_DIR="$DIR" MCRS_JVM_OPTIONS="$1" ./target/release/mcrs > "$LOG" 2>&1
sed 's/\x1b\[[0-9;]*m//g' "$LOG" | grep -a "mcrs::java" | grep -aoE "[0-9]+ full chunks in [0-9.]+ s = [0-9.]+ chunks/s|terrain: .*|light engine busy.*"
