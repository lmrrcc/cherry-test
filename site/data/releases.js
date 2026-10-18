window.SITE_RELEASES = {
  "repository": "lmrrcc/cherry-test",
  "generatedAt": "2026-10-08T09:53:51Z",
  "versions": [
    {
      "tag": null,
      "version": "0.3.16",
      "date": null,
      "commits": [
        {
          "hash": "1b6544db",
          "type": "fix",
          "scope": "report",
          "text": "integration pass/fail from real outcome, not HTTP status"
        },
        {
          "hash": "2d80aa86",
          "type": "docs",
          "scope": "split",
          "text": "rewrite CLAUDE/README/AGENTS as test-only"
        },
        {
          "hash": "4335c088",
          "type": "style",
          "scope": "split",
          "text": "cargo fmt after review strip"
        },
        {
          "hash": "e1423f90",
          "type": "chore",
          "scope": "split",
          "text": "untrack .cursor/.claude review-rules submodules"
        },
        {
          "hash": "926637bd",
          "type": "chore",
          "scope": "split",
          "text": "remove review assets, CI steps, and scripts"
        },
        {
          "hash": "6fc6edb4",
          "type": "refactor",
          "scope": "split",
          "text": "remove inert review CSS and render_review_only_document"
        },
        {
          "hash": "038cb2a3",
          "type": "refactor",
          "scope": "split",
          "text": "strip all review code from test/core/common crates"
        },
        {
          "hash": "5bd2caaa",
          "type": "refactor",
          "scope": "split",
          "text": "delete review crates (cherry-test-review, review-it)"
        },
        {
          "hash": "80eface1",
          "type": "refactor",
          "scope": "split",
          "text": "unwire review from cherry-test build"
        },
        {
          "hash": "b47c7fff",
          "type": "feat",
          "scope": "rules",
          "text": "add static-only review profile"
        },
        {
          "hash": "62a24f97",
          "type": "feat",
          "scope": "core",
          "text": "bearer override on TestEnv request builder"
        },
        {
          "hash": "732b9c35",
          "type": "feat",
          "scope": "report",
          "text": "syntax highlighting for review diff snippets"
        },
        {
          "hash": "2261e9a9",
          "type": "feat",
          "scope": "report",
          "text": "GitLab-style review diff, palette, and comment threads"
        },
        {
          "hash": "23c83bf3",
          "type": "chore",
          "scope": null,
          "text": "ignore local agent tooling artifacts"
        },
        {
          "hash": "d9b1759b",
          "type": "chore",
          "scope": null,
          "text": "eliminate all build warnings across workspace crates"
        },
        {
          "hash": "c158513d",
          "type": "feat",
          "scope": "review",
          "text": "omit-pass response schema, relaxed echo validation, finding dedup"
        },
        {
          "hash": "6b785f4e",
          "type": "feat",
          "scope": "review",
          "text": "hardened cache key with epoch+TTL, cache CLI, static detectors"
        },
        {
          "hash": "f722fbd1",
          "type": "feat",
          "scope": "rules",
          "text": "static-checks topic, review profiles, refreshed skills manifest"
        },
        {
          "hash": "63f35711",
          "type": "chore",
          "scope": "rules",
          "text": "enable all 12 canonical rule topics"
        },
        {
          "hash": "3d513d10",
          "type": "chore",
          "scope": null,
          "text": "bump workspace version to 0.3.15"
        },
        {
          "hash": "757d25b6",
          "type": "feat",
          "scope": "review",
          "text": "branch-and-worktree scope, agent plan debug, review config"
        }
      ],
      "release": {
        "published": "2026-10-08",
        "assets": [
          { "name": "cherry-test-0.3.16-aarch64-apple-darwin.tar.gz", "size": 7931378, "url": "releases/v0.3.16/cherry-test-0.3.16-aarch64-apple-darwin.tar.gz" },
          { "name": "cherry-test-0.3.16-aarch64-apple-darwin.tar.gz.sha256", "size": 113, "url": "releases/v0.3.16/cherry-test-0.3.16-aarch64-apple-darwin.tar.gz.sha256" }
        ]
      }
    },
    {
      "tag": "v0.3.14",
      "version": "0.3.14",
      "date": "2026-06-04",
      "commits": [
        {
          "hash": "52bacd00",
          "type": "feat",
          "scope": "review",
          "text": "per-file agent plan debug output"
        }
      ],
      "release": null
    },
    {
      "tag": "v0.3.13",
      "version": "0.3.13",
      "date": "2026-06-04",
      "commits": [
        {
          "hash": "39a5c3f6",
          "type": "chore",
          "scope": null,
          "text": "rewrite rules.yaml to compact mapping format"
        },
        {
          "hash": "3107d1ff",
          "type": "feat",
          "scope": "review",
          "text": "compact rules.yaml map format with four value semantics"
        }
      ],
      "release": null
    },
    {
      "tag": "v0.3.12",
      "version": "0.3.12",
      "date": "2026-06-04",
      "commits": [
        {
          "hash": "aa4d4b41",
          "type": "fix",
          "scope": "cherry-test-core",
          "text": "add rules_profile field to ReviewDefaults struct literal"
        },
        {
          "hash": "c6f214f6",
          "type": "feat",
          "scope": "review",
          "text": "hierarchical rules.yaml and custom profiles"
        }
      ],
      "release": null
    },
    {
      "tag": "v0.3.11",
      "version": "0.3.11",
      "date": "2026-06-04",
      "commits": [
        {
          "hash": "81d74c67",
          "type": "release",
          "scope": null,
          "text": "review-it 0.3.11"
        }
      ],
      "release": null
    },
    {
      "tag": "v0.3.10",
      "version": "0.3.10",
      "date": "2026-06-03",
      "commits": [
        {
          "hash": "6c6fa68e",
          "type": "fix",
          "scope": "review-it",
          "text": "prefer origin/main and three-dot branch diff"
        }
      ],
      "release": null
    },
    {
      "tag": "v0.3.9",
      "version": "0.3.9",
      "date": "2026-06-03",
      "commits": [
        {
          "hash": "c692402d",
          "type": "feat",
          "scope": "review-it",
          "text": "print copy-paste git commands in --debug"
        }
      ],
      "release": null
    },
    {
      "tag": "v0.3.8",
      "version": "0.3.8",
      "date": "2026-06-03",
      "commits": [
        {
          "hash": "7418e544",
          "type": "release",
          "scope": null,
          "text": "review-it 0.3.8"
        }
      ],
      "release": null
    },
    {
      "tag": "v0.3.7",
      "version": "0.3.7",
      "date": "2026-06-03",
      "commits": [
        {
          "hash": "c3e483b7",
          "type": "release",
          "scope": null,
          "text": "review-it 0.3.7"
        },
        {
          "hash": "561dac7c",
          "type": "feat",
          "scope": "review",
          "text": "list all instrumentable files in --debug output"
        }
      ],
      "release": null
    },
    {
      "tag": "v0.3.6",
      "version": "0.3.6",
      "date": "2026-06-03",
      "commits": [
        {
          "hash": "5017da89",
          "type": "release",
          "scope": null,
          "text": "review-it 0.3.6"
        }
      ],
      "release": null
    },
    {
      "tag": "v0.3.5",
      "version": "0.3.5",
      "date": "2026-06-02",
      "commits": [
        {
          "hash": "a6da6419",
          "type": "release",
          "scope": null,
          "text": "review-it 0.3.5"
        },
        {
          "hash": "54f12410",
          "type": "feat",
          "scope": "review",
          "text": "default Cursor model to gpt-5.3-codex-low"
        }
      ],
      "release": null
    },
    {
      "tag": "v0.3.4",
      "version": "0.3.4",
      "date": "2026-06-02",
      "commits": [
        {
          "hash": "ebee168f",
          "type": "release",
          "scope": null,
          "text": "review-it 0.3.4"
        },
        {
          "hash": "0f1d1e93",
          "type": "fix",
          "scope": "review",
          "text": "tree diff when submodule HEAD is behind diff_base"
        },
        {
          "hash": "ec422628",
          "type": "docs",
          "scope": null,
          "text": "sample lives in lmrrcc/mini, not a cherry-test branch"
        },
        {
          "hash": "9a8be5bd",
          "type": "mini",
          "scope": null,
          "text": "track lmrrcc/mini submodule on mini branch"
        },
        {
          "hash": "835b5e1e",
          "type": "mini",
          "scope": null,
          "text": "placeholder on main for mini-branch diff"
        }
      ],
      "release": null
    },
    {
      "tag": "v0.3.3",
      "version": "0.3.3",
      "date": "2026-06-02",
      "commits": [
        {
          "hash": "b2666187",
          "type": "fix",
          "scope": "review-it",
          "text": "branch-only file scope, debug diagnostics, v0.3.3"
        }
      ],
      "release": null
    },
    {
      "tag": "v0.3.2",
      "version": "0.3.2",
      "date": "2026-06-02",
      "commits": [
        {
          "hash": "0d28e71d",
          "type": "fix",
          "scope": "review-it",
          "text": "submodule scope uses branch diff only (0.3.2)"
        }
      ],
      "release": null
    },
    {
      "tag": "v0.3.1",
      "version": "0.3.1",
      "date": "2026-06-02",
      "commits": [
        {
          "hash": "bc0bfc51",
          "type": "other",
          "scope": null,
          "text": "Bump workspace to 0.3.1"
        },
        {
          "hash": "b578b297",
          "type": "other",
          "scope": null,
          "text": "Fix submodule changed-file scope to use only diff_base range."
        }
      ],
      "release": null
    },
    {
      "tag": "v0.3.0",
      "version": "0.3.0",
      "date": "2026-06-02",
      "commits": [
        {
          "hash": "cff1cbcd",
          "type": "other",
          "scope": null,
          "text": "Release review-it 0.3.0 with git submodule support."
        },
        {
          "hash": "b14c4961",
          "type": "other",
          "scope": null,
          "text": "Add mini as git submodule for review-it submodule testing."
        }
      ],
      "release": null
    },
    {
      "tag": "v0.2.0",
      "version": "0.2.0",
      "date": "2026-06-02",
      "commits": [
        {
          "hash": "d52f2f0d",
          "type": "other",
          "scope": null,
          "text": "Add review.yaml config split for review-it v0.2.0"
        },
        {
          "hash": "b27b3401",
          "type": "other",
          "scope": null,
          "text": "Add S3.6 rule harness runner, CI gate, and bundle migration tooling."
        },
        {
          "hash": "99c359bb",
          "type": "chore",
          "scope": "review",
          "text": "rename rule harness directories to -test"
        },
        {
          "hash": "8de9116a",
          "type": "docs",
          "scope": "review",
          "text": "scope harness ignore patterns to *.harness trees"
        },
        {
          "hash": "89cb63f6",
          "type": "fix",
          "scope": "review",
          "text": "colocate harness manifests under .cursor/rules"
        },
        {
          "hash": "a7893cb6",
          "type": "fix",
          "scope": "review",
          "text": "keep rule harness fixtures out of runner repo"
        },
        {
          "hash": "cd77dcee",
          "type": "feat",
          "scope": "review",
          "text": "allow project-owned review prompt overrides"
        },
        {
          "hash": "fbf23ce8",
          "type": "mini",
          "scope": null,
          "text": "add sample project"
        },
        {
          "hash": "c319f028",
          "type": "review",
          "scope": null,
          "text": "split review tooling and align report/catalog behavior"
        },
        {
          "hash": "772fa4ae",
          "type": "other",
          "scope": null,
          "text": "Add review-it split and lmrr.cc release dispatch"
        },
        {
          "hash": "5f50ed69",
          "type": "other",
          "scope": null,
          "text": "Remove embedded review rules and add cherry-test rules install subcommand"
        },
        {
          "hash": "2e0c7793",
          "type": "other",
          "scope": null,
          "text": "Bootstrap review catalog from lmrco/.cursor when skills CLI is absent"
        },
        {
          "hash": "d75e1877",
          "type": "other",
          "scope": null,
          "text": "Fix GitHub cherry-test install without local .cursor catalog"
        },
        {
          "hash": "4fd43d40",
          "type": "other",
          "scope": null,
          "text": "Serialize review prompt env tests to prevent parallel flake"
        },
        {
          "hash": "093dec21",
          "type": "other",
          "scope": null,
          "text": "Add missing review and report modules from rev merge"
        },
        {
          "hash": "de7e8c80",
          "type": "other",
          "scope": null,
          "text": "Harden mini sample stack for CI review and expand cherry-test agent review"
        },
        {
          "hash": "5de50852",
          "type": "other",
          "scope": null,
          "text": "Add mini workspace, YAML review index, and HTML report fold defaults"
        },
        {
          "hash": "d86c64d4",
          "type": "other",
          "scope": null,
          "text": "Remove agent code review from cherry-test runner and reports"
        },
        {
          "hash": "143abdc0",
          "type": "other",
          "scope": null,
          "text": "Add cherry-test multi-project aggregator with coverage, reports, and agent code review"
        }
      ],
      "release": null
    }
  ]
};
