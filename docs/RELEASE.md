# Releases

Git tags on `lmrrcc/test` update source only. **Homebrew and `lmrr-cc.pages.dev` binaries update only after the [lmrr.cc](https://github.com/lmrrcc/lmrr.cc) distribution pipeline runs.**

Tagged releases (`v*`) trigger that pipeline via `repository_dispatch` when `LMRR_RELEASE_TOKEN` is configured.

## Homebrew / lmrr-cc.pages.dev

`brew install lmrr/cc/review-it` downloads pre-built tarballs from `https://lmrr-cc.pages.dev/releases/`, not from the git tag. After tagging `v0.3.7` on cherry-test, run a publish step or Homebrew stays on the last published version.

**Install on another machine (after publish):**

```bash
curl -fsSL https://lmrr-cc.pages.dev/install/brew-tap.sh | bash
brew install lmrr/cc/review-it
review-it --version
```

**Publish review-it only (recommended for review-it version bumps):**

GitHub → **lmrrcc/lmrr.cc** → Actions → **Release tool** → Run workflow:

| Input | Example |
|-------|---------|
| tool_id | `review-it` |
| version | `0.3.7` |
| source_repo | `lmrrcc/test` |
| source_ref | `v0.3.7` |

Verify:

```bash
curl -fsSL https://lmrr-cc.pages.dev/formulae/review-it.rb | head -5
curl -fsSL https://lmrr-cc.pages.dev/releases/review-it/0.3.7/manifest.json
```

Do not use **Release all tools** for a review-it-only bump unless cherry-test and skills share the same semver.

## Maintainer setup

1. Create a GitHub PAT with `repo` scope on `lmrrcc/lmrr.cc` (or use `gh secret set LMRR_RELEASE_TOKEN --repo lmrrcc/test --body "$(gh auth token)"` when logged in as an owner).
2. Add repository secret `LMRR_RELEASE_TOKEN` on `lmrrcc/test`.
3. Tag and push:

```bash
git tag v0.3.7
git push origin v0.3.7
```

The pipeline builds `cherry-test` and `review-it` for macOS and Linux, uploads binaries to Cloudflare R2, updates Homebrew formulae, and publishes library crates to the sparse registry at `https://lmrr.cc/cargo/`.

## Manual release

Run workflows on `lmrrcc/lmrr.cc`:

- **Release all tools** — `workflow_dispatch` with version and `lmrrcc/test` as source (same version for every tool).
- **Release tool** — single tool id from `tools/tools.yaml` (preferred for `review-it` only).

## Library crates

Consumers add [`.cargo/config.toml` from lmrr.cc](https://github.com/lmrrcc/lmrr.cc/blob/main/docs/example-cargo-config.toml) and depend on crates with `registry = "lmrr"`.
