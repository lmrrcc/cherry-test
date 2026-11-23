# Releases

The canonical Cherry Test repository is [lmrrcc/cherry-test](https://github.com/lmrrcc/cherry-test). In the maintainer checkout, the `cherry-release` remote points there; `origin` and `test-split` point to older repositories. Check the remote URL before publishing. Do not push a release to an old repository merely because its remote is named `origin`.

## Prepare and verify

1. Update the Rust workspace version, its entries in `Cargo.lock`, the Java parent and module versions, and version strings shipped by the Java CLI. Keep Rust and Java on the same release version.
2. Commit any sample changes in their own repositories and push those commits before committing updated submodule pointers in Cherry Test.
3. Review the complete release diff and run the checks below. Record failures or unavailable checks in the release notes; a failed required check must be resolved before publication.

```bash
cargo fmt --all --check
cargo test -p cherry-test -p cherry-test-core -p cherry-test-common -p cherry-test-macros
cargo build -p cherry-test
cargo audit
(cd java && ./mvnw -B -ntp verify)
cargo cherry-test --root app/mini --no-open
```

The sample smoke requires initialized submodules, their installed dependencies, and Docker. Frontend changes also require the relevant package-manager audit. Changes to mutation execution require real Maven/Java and Gradle/Kotlin runs: verify that unchanged sources are excluded and that killed, surviving, and uncovered mutants appear separately from ordinary test counts.

## Publish the source release

Use a new immutable tag, such as `v0.3.17`, on the reviewed release commit. First check the canonical branch and existing tags, then push the branch without force and publish the tag. If the branch advanced, reconcile and rerun affected checks before publishing.

```bash
git remote get-url cherry-release
git ls-remote cherry-release refs/heads/main refs/tags/v0.3.17
git push cherry-release main
git tag -a v0.3.17 -m 'Cherry Test 0.3.17'
git push cherry-release v0.3.17
gh release create v0.3.17 --repo lmrrcc/cherry-test --verify-tag \
  --title 'Cherry Test 0.3.17' --notes-file /path/to/release-notes.md
```

Release notes should describe the changes, compatibility and default-behavior changes, validation performed, and any distribution limitations. A GitHub release provides source archives. It does not, by itself, provide prebuilt binaries or update Homebrew.

Validated local builds can be attached to the GitHub release with `gh release upload`. Package Rust binaries with their target triple in the filename and provide SHA-256 checksums. The Java CLI shaded jar requires JDK 25. Verify each attached artifact's version; uploading artifacts to GitHub does not update the website or Homebrew.

Install the tagged Rust release directly from the canonical source repository:

```bash
cargo install --git https://github.com/lmrrcc/cherry-test --tag v0.3.17 --locked --bins cherry-test
```

For the Java edition, check out the tag and run `java/install.sh` with JDK 25 available. See the [Java build and installation instructions](../java/README.md).

## Binary distribution is a separate step

[`.github/workflows/release.yml`](../.github/workflows/release.yml) runs on `v*` tag pushes. Its only job sends a `cherry-test-release` repository-dispatch event to [lmrrcc/lmrr.cc](https://github.com/lmrrcc/lmrr.cc), using the `LMRR_RELEASE_TOKEN` repository secret. The payload identifies the version tag, source repository, and commit SHA. This workflow does not build binaries, create a GitHub release, or publish Java artifacts itself.

As checked on 2026-11-23, the distribution repository exposed no GitHub Actions workflows. Do not assume that a successful dispatch updates `lmrr-cc.pages.dev`, Homebrew formulae, or the private Cargo registry. The distribution workflow and its credentials must be restored and its available inputs inspected before requesting a binary release. Do not use the older `review-it` release instructions for Cherry Test.

After a distribution run becomes available, verify the resulting Cherry Test binary version, published checksums, and installation metadata before announcing binary availability. Report a source release and binary publication separately when only the source release has completed.
