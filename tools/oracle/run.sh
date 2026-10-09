#!/usr/bin/env sh
# Compiles and runs the vanilla oracle against the server jar in run/ and writes
# reference values to target/oracle/. Run `cargo run` once first so run/ exists.
set -eu
cd "$(dirname "$0")/../.."

JAVA_HOME="${MCRS_JAVA_HOME:-${JAVA_HOME:-$(ls -d .tools/jdk-* 2>/dev/null | tail -n 1)}}"
SEP=":"
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=";" ;; esac

SERVER_JAR=$(ls run/versions/*/server-*.jar | head -n 1)
CP="$SERVER_JAR"
for jar in $(find run/libraries -name '*.jar'); do CP="$CP$SEP$jar"; done

mkdir -p target/oracle/classes
printf '%s' "$CP" > target/oracle/classpath.txt
"$JAVA_HOME/bin/javac" -nowarn -d target/oracle/classes -cp "$CP" tools/oracle/src/*.java
"$JAVA_HOME/bin/java" -XX:+UnlockDiagnosticVMOptions -XX:-UseLibmIntrinsic -cp "target/oracle/classes$SEP$CP" Oracle target/oracle
