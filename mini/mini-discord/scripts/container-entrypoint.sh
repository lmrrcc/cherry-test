#!/bin/sh
set -eu
while ip -4 route show default | grep -q '^default'; do
  ip -4 route del default
done
while ip -6 route show default | grep -q '^default'; do
  ip -6 route del default
done
if ip -4 route get 192.0.2.1 >/dev/null 2>&1; then
  echo 'Mini-Discord requires an unreachable external network.' >&2
  exit 1
fi
exec su -p -s /bin/sh mini-discord -c 'exec java -jar /app/mini-discord.jar'
