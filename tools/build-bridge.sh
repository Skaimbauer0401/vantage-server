#!/usr/bin/env sh
# Builds the bridge mod with the bundled JDK. OneDrive sometimes turns compiled classes into
# placeholders Gradle cannot snapshot, so the class output is cleared first.
set -eu
cd "$(dirname "$0")/../bridge-mod"
rm -rf build/classes
JAVA_HOME="$(cygpath -w ../.tools/jdk-25.0.4.1+1 2>/dev/null || echo ../.tools/jdk-25.0.4.1+1)" ./gradlew build -q 2>&1 | grep -v Hinweis || true
ls build/libs/mcrs-bridge-*.jar >/dev/null
