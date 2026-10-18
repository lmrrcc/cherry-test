#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
MINI_DISCORD_TEST_SECRET=$(openssl rand -hex 32)
export MINI_DISCORD_TEST_SECRET
COMPOSE_PROJECT_NAME="mini-discord-$(date +%s)-$$"
export COMPOSE_PROJECT_NAME
COMPOSE_FILE=docker-compose.test.yml
export COMPOSE_FILE
cleanup() {
  docker compose logs --no-color --no-log-prefix mini-discord > target/container-evidence/service.log 2>&1 || true
  MINI_DISCORD_CONTAINER_ID=$(docker compose ps -aq mini-discord)
  if [ -n "$MINI_DISCORD_CONTAINER_ID" ]; then
    docker inspect --format '{{json .State.Health}}' "$MINI_DISCORD_CONTAINER_ID" > target/container-evidence/health.json || true
  fi
  docker network inspect --format '{{.Internal}}' "${COMPOSE_PROJECT_NAME}_discord-test" > target/container-evidence/network-internal.txt 2>&1 || true
  docker compose --profile consumer down --volumes --remove-orphans
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
mkdir -p target/container-evidence
docker compose --profile consumer build
docker compose up -d --wait mini-discord
docker compose exec -T -u 10001:10001 mini-discord sh -c '
  set -eu
  awk '\''/^Uid:/ {if ($2 != 10001 || $3 != 10001) exit 1} /^CapEff:/ {if ($2 != "0000000000000000") exit 1}'\'' /proc/1/status
  grep -E "^(Uid|CapEff|NoNewPrivs):" /proc/1/status
  if ip -4 route get 192.0.2.1; then
    echo "Unexpected external route" >&2
    exit 1
  fi
  if ip -6 route show default | grep -q .; then
    echo "Unexpected IPv6 default route" >&2
    exit 1
  fi
' > target/container-evidence/network-policy.txt 2>&1
docker compose run --rm consumer
