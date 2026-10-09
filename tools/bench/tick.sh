#!/usr/bin/env sh
# Reproducible game logic run (mobs or redstone) on a fresh flat world:
#   tools/bench/tick.sh <scenario> <size> <ticks> <hash true|false> "<extra JVM options>" <label> [wait seconds]
# With hashing, the per-tick state hashes land in target/tick-results/<label>.txt; two
# runs are the same game exactly when those files are equal.
set -eu
cd "$(dirname "$0")/../.."
DIR=target/tick-server
mkdir -p "$DIR" target/tick-results
rm -rf "$DIR/world" "$DIR/harness-$1.txt" "$DIR/harness-$1-entities.txt" "target/tick-results/$6.txt" "target/tick-results/$6-entities.txt"
cat > "$DIR/server.properties" <<PROPS
level-seed=1
level-name=world
level-type=minecraft\:flat
generate-structures=false
online-mode=false
spawn-protection=0
PROPS
[ -f "$DIR/eula.txt" ] || cp run/eula.txt "$DIR/eula.txt"
LOG="$DIR/tick-$6.log"
WAIT=${7:-$(( $3 / 20 + 30 ))}
( sleep 20
  echo "mcrs harness $1 $2 $3 $4"; sleep "$WAIT"
  echo stop; sleep 20 ) | MCRS_RUN_DIR="$DIR" MCRS_AOT_CACHE=0 MCRS_JVM_OPTIONS="-Dmcrs.deterministic=1 $5" ./target/release/mcrs > "$LOG" 2>&1
[ -f "$DIR/harness-$1.txt" ] && cp "$DIR/harness-$1.txt" "target/tick-results/$6.txt"
[ -f "$DIR/harness-$1-entities.txt" ] && cp "$DIR/harness-$1-entities.txt" "target/tick-results/$6-entities.txt"
sed 's/\x1b\[[0-9;]*m//g' "$LOG" | grep -a "mcrs::java" | grep -ao "\[harness\].*" | tail -2
