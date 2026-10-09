# Review HTML layout audit (TASK 24)

Date: 2026-05-29

## Verdict

Target layout from the handoff is **implemented**. Remaining work is verification, tests, and optional UX polish—not a card-based rewrite.

## Checklist

| Item | Status |
|------|--------|
| Shared shell (`report_shell`, theme toggle, `REPORT_HTML_STYLES`) | DONE |
| Standalone `review.html` uses `render_review_only_document` | DONE |
| Embedded `#review` same section body as standalone | DONE |
| `details.project` / `module` / `file` tree | DONE |
| Code-first file body (`div.review-snippet`, `.row`) | DONE |
| No `review-issues` / `review-issue` / `code-diagnostic` in render path | DONE |
| No duplicate `review-reviewed-files` HTML list | DONE |
| `reviewed_files` merged into tree (zero-finding files) | DONE |
| Severity row backgrounds + right `review-gutter` flags | DONE |
| `details.review-thread` under lines | DONE |
| `div.review-diagnostics` after tree | DONE |
| Terminal per-file / per-finding lines | DONE |
| `sort_findings`: line, then severity (HTML + terminal aligned) | DONE |

## Gaps / optional

| Item | Status |
|------|--------|
| Gutter click JS scroll (native `#anchor` + `<details>` suffice) | OPTIONAL |
| Remove dead `CodeSnippetView` / `build_code_snippet` helpers | OPTIONAL cleanup |
| Manual visual QA screenshots | TASK 48 (human) |

## Key paths

- `test/cherry-test-common/src/report_shell.rs`
- `test/cherry-test/src/report/html.rs` (`#changes` master)
- `test/cherry-test-review/src/review/html.rs`
- `test/cherry-test-review/src/review/mod.rs` (`format_review_terminal_lines`)
- `AGENTS.md` — Review HTML layout section
