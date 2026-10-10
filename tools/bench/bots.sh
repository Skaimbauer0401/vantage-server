#!/usr/bin/env sh
# Fake players flying over a pregenerated world (radius 1500, seed 20261010):
#   tools/bench/bots.sh <count> <speed blocks/tick> <ticks> "<extra JVM options>" <label> [wait seconds]
# Each run starts from a fresh copy of target/bench-worlds/pregen-1500.
set -eu
cd "$(dirname "$0")/../.."
DIR=target/bot-server
mkdir -p "$DIR"
rm -rf "$DIR/world" "$DIR/bots-result.txt"
cp -r target/bench-worlds/pregen-1500 "$DIR/world"
cat > "$DIR/server.properties" <<PROPS
level-seed=20261010
level-name=world
online-mode=false
spawn-protection=0
view-distance=10
simulation-distance=10
PROPS
[ -f "$DIR/eula.txt" ] || cp run/eula.txt "$DIR/eula.txt"
LOG="$DIR/bots-$5.log"
WAIT=${6:-$(( $3 / 20 + 40 ))}
( sleep 25
  echo "mcrs bots $1 $2 $3"; sleep "$WAIT"
  echo stop; sleep 25 ) | MCRS_RUN_DIR="$DIR" MCRS_AOT_CACHE=0 MCRS_JVM_OPTIONS="$4" ./target/release/mcrs > "$LOG" 2>&1
sed 's/\x1b\[[0-9;]*m//g' "$LOG" | grep -a "mcrs::java" | grep -ao "\[bots\].*" | tail -1
