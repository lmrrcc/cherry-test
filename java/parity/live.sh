#!/usr/bin/env bash
{
set -euo pipefail

usage() {
  echo "usage: $0 <app dir relative to the repository root> [filter]" >&2
  exit 2
}

[ $# -eq 1 ] || [ $# -eq 2 ] || usage
app_relative=${1%/}
filter=${2:-}

repository=$(cd "$(dirname "$0")/../.." && pwd)
parity="$repository/java/parity"
app="$repository/$app_relative"
name=$(basename "$app_relative")${filter:+-$filter}
recorded="$parity/target/work/$name"
live="$parity/target/live/$name"
java_jar="$repository/java/cherry-test-cli/target/cherry-test-java.jar"
parity_jar="$parity/target/cherry-test-parity.jar"
java_binary=$(command -v java)
runs="$app/target/cherry-test/runs"
latest="$app/target/cherry-test/latest"

[ -d "$app" ] || { echo "no such app: $app" >&2; exit 2; }
[ -s "$recorded/run-id" ] || { echo "no Rust recording in $recorded; run parity.sh record first" >&2; exit 2; }
if pgrep -f -- "--root $app_relative --no-open" >/dev/null; then
  echo "a Cherry Test run on $app_relative is still in progress; stop it before a live run" >&2
  exit 1
fi

cd "$repository"
original_latest=$(readlink "$latest" 2>/dev/null || :)
rm -rf "$live"
mkdir -p "$live"
set +e
"$java_binary" --enable-native-access=ALL-UNNAMED -jar "$java_jar" --root "$app_relative" --no-open ${filter:+"$filter"} \
  </dev/null >"$live/java.stdout" 2>"$live/java.stderr"
echo $? >"$live/java.exit"
set -e
run_id=$(sed -n 's/^cherry-test run: \([^ ]*\) .*/\1/p' "$live/java.stdout" | head -n 1)
[ -n "$run_id" ] || { echo "no run id in the Java CLI output" >&2; exit 1; }
echo "$run_id" >"$live/run-id"
mv "$runs/$run_id" "$live/java-run"
rm -f "$latest"
if [ -n "$original_latest" ]; then
  ln -s "$original_latest" "$latest"
fi
echo "live Java run $run_id on $name (exit $(cat "$live/java.exit"))"
"$java_binary" -cp "$parity_jar:$(cat "$parity/target/pixels.classpath")" com.cherrytest.parity.LiveCompare \
  "$recorded/rust" "$(cat "$recorded/run-id")" "$live/java" "$run_id"
exit
}
