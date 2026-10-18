#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
if [ "$(uname -s)" = Darwin ]; then
  JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Djava.net.preferIPv4Stack=true"
  export JAVA_TOOL_OPTIONS
  exec sandbox-exec -f scripts/loopback-only.sb ./mvnw -o -B -ntp -Dmini.discord.isolated=true "$@"
fi
exec docker run --rm --network none -v "$PWD/target/offline-evidence:/evidence" -e CHERRY_TEST_REPORT_DIR=/evidence mini-discord-tests -Dmini.discord.isolated=true "$@"
