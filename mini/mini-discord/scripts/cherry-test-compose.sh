#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
CHERRY_TEST_JAR=${CHERRY_TEST_JAR:-../../java/cherry-test-cli/target/cherry-test-java.jar}
MINI_DISCORD_TEST_SECRET=$(openssl rand -hex 32)
export MINI_DISCORD_TEST_SECRET
if [ "$(uname -s)" != Darwin ]; then
  echo 'For Linux use container-smoke.sh and cherry-test-docker.sh with network isolation.' >&2
  exit 1
fi
MINI_DISCORD_DOCKER_ENDPOINT=$(docker context inspect --format '{{(index .Endpoints "docker").Host}}')
case "$MINI_DISCORD_DOCKER_ENDPOINT" in
  unix://*) MINI_DISCORD_DOCKER_SOCKET=${MINI_DISCORD_DOCKER_ENDPOINT#unix://} ;;
  *) echo 'A local Docker Unix socket is required.' >&2; exit 1 ;;
esac
JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Djava.net.preferIPv4Stack=true"
export JAVA_TOOL_OPTIONS
exec sandbox-exec -D "DOCKER_SOCKET=$MINI_DISCORD_DOCKER_SOCKET" -f scripts/loopback-docker.sb \
  java -jar "$CHERRY_TEST_JAR" --root examples/compose --no-open "$@"
