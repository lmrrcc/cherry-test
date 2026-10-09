#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Generate site/data/releases.js from git tags and GitHub Releases.

Usage:
  site/scripts/generate-releases.sh [--repository OWNER/NAME] [--output FILE]

Options:
  --repository OWNER/NAME   GitHub repository holding release assets (default: lmrrcc/test)
  --output FILE             Output file (default: site/data/releases.js)
  -h, --help                Show this help

Requires git, jq, and an authenticated gh CLI. Fails when the release API is unreachable.
USAGE
}

repository="${SITE_REPOSITORY:-lmrrcc/test}"
script_directory="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
output="$script_directory/../data/releases.js"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --repository)
      [[ $# -ge 2 ]] || { echo "error: --repository requires a value" >&2; exit 2; }
      repository="$2"; shift 2 ;;
    --output)
      [[ $# -ge 2 ]] || { echo "error: --output requires a value" >&2; exit 2; }
      output="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "error: unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
done

for tool in git jq gh; do
  command -v "$tool" >/dev/null 2>&1 || { echo "error: $tool is required" >&2; exit 1; }
done

repository_root="$(git -C "$script_directory" rev-parse --show-toplevel)"

if ! release_assets="$(gh api --paginate "repos/$repository/releases" --jq '[.[] | {tag: .tag_name, published: .published_at, url: .html_url, assets: [.assets[] | {name, size, url: .browser_download_url}]}]' | jq -s 'add // []')"; then
  echo "error: could not read releases for $repository" >&2
  exit 1
fi

commit_entries() {
  local range="$1"
  git -C "$repository_root" log --no-merges --format='%H%x1f%s' "$range" | jq -R -s '
    split("\n") | map(select(length > 0) | split("\u001f") | {hash: .[0][0:8], subject: .[1]})
    | map(. + (
        (.subject | capture("^(?<type>[a-z]+)(\\((?<scope>[^)]*)\\))?!?: (?<text>.*)$"))
        // {type: "other", scope: null, text: .subject}
      ))
    | map({hash, type, scope, text})'
}

version_tags=()
while IFS= read -r tag; do
  version_tags+=("$tag")
done < <(git -C "$repository_root" tag --list 'v*' --sort=-v:refname)

[[ ${#version_tags[@]} -gt 0 ]] || { echo "error: no v* tags found" >&2; exit 1; }

versions="[]"
latest_tag="${version_tags[0]}"
unreleased="$(commit_entries "$latest_tag..HEAD")"
if [[ "$(jq 'length' <<<"$unreleased")" -gt 0 ]]; then
  workspace_version="$(sed -n 's/^version *= *"\(.*\)"/\1/p' "$repository_root/Cargo.toml" | head -n 1)"
  versions="$(jq --argjson commits "$unreleased" --arg version "$workspace_version" \
    '. + [{tag: null, version: $version, date: null, commits: $commits}]' <<<"$versions")"
fi

for index in "${!version_tags[@]}"; do
  tag="${version_tags[$index]}"
  next_index=$((index + 1))
  if [[ $next_index -lt ${#version_tags[@]} ]]; then
    range="${version_tags[$next_index]}..$tag"
  else
    range="$tag"
  fi
  date="$(git -C "$repository_root" log -1 --format=%cs "$tag")"
  commits="$(commit_entries "$range")"
  versions="$(jq --arg tag "$tag" --arg date "$date" --argjson commits "$commits" \
    '. + [{tag: $tag, version: ($tag | ltrimstr("v")), date: $date, commits: $commits}]' <<<"$versions")"
done

generated_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
document="$(jq -n --arg repository "$repository" --arg generated "$generated_at" \
  --argjson versions "$versions" --argjson releases "$release_assets" '
  {
    repository: $repository,
    generatedAt: $generated,
    versions: ($versions | map(. as $version | . + {
      release: (($releases | map(select(.tag == $version.tag)) | first) // null)
    }))
  }')"

mkdir -p "$(dirname "$output")"
printf 'window.SITE_RELEASES = %s;\n' "$document" > "$output"
echo "wrote $output ($(jq '.versions | length' <<<"$document") versions, $(jq '[.versions[].release | select(. != null) | .assets[]] | length' <<<"$document") binaries)"
