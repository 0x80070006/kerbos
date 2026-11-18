#!/bin/bash
# Build helper: uses the portable toolchain in .toolchain
cd "$(dirname "$0")"
TD="$(cygpath -m "$(pwd)/.toolchain/tmp")"; mkdir -p .toolchain/tmp
export TEMP="$TD" TMP="$TD" JAVA_HOME="$(pwd)/.toolchain/jdk" GRADLE_USER_HOME="$(pwd)/.toolchain/gradle-home"
export GRADLE_OPTS="-Djdk.net.unixdomain.tmpdir=$TD -Djava.io.tmpdir=$TD"
./gradlew "$@"
