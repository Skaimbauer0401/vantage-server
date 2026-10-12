#!/usr/bin/env sh
# Chunk generation with Chunky on a fresh world (seed 20261010), like a real pregeneration:
#   tools/bench/chunky.sh "<extra JVM options>" <label> [seconds]
# Generates for <seconds> (default 90) after a 20 s warm-up and prints Chunky's last rate.
# Needs Chunky and Fabric API in run/mods (copied from there). EXTRA_MODS="a.jar b.jar" adds
# more mods (e.g. other performance mods) for this run.
set -eu
cd "$(dirname "$0")/../.."
DIR=target/chunky-server
SECS=${3:-90}
mkdir -p "$DIR/mods"
rm -rf "$DIR/world" "$DIR/config/chunky/tasks"
rm -f "$DIR/mods/"*.jar
cp run/mods/Chunky-*.jar run/mods/fabric-api-*.jar "$DIR/mods/"
for m in ${EXTRA_MODS:-}; do cp "$m" "$DIR/mods/"; done
cat > "$DIR/server.properties" <<PROPS
level-seed=20261010
level-name=world
online-mode=false
spawn-protection=0
PROPS
[ -f "$DIR/eula.txt" ] || cp run/eula.txt "$DIR/eula.txt"
LOG="$DIR/chunky-$2.log"
( sleep 25
  echo "mcrs compresstest"; echo "chunky center 0 0"; echo "chunky radius 1500"; echo "chunky start"; sleep 1; echo "chunky confirm"; sleep 19
  echo "chunky progress"; echo "mcrs genstats"; sleep "$SECS"
  echo "chunky progress"; echo "mcrs genstats"; sleep 2
  echo "chunky cancel"; sleep 1; echo "chunky confirm"; sleep 3; echo stop; sleep 30 ) | MCRS_RUN_DIR="$DIR" MCRS_AOT_CACHE=0 MCRS_JVM_OPTIONS="$1" ./target/release/mcrs > "$LOG" 2>&1
sed 's/\x1b\[[0-9;]*m//g' "$LOG" | grep -aoE "Processed: [0-9]* chunks.*|\[genstats\].*" | tail -3
