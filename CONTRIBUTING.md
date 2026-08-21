# Contributing to Hardwood

Thanks for your interest in contributing. This guide covers how to find work, make changes, and get a pull request merged.

For build instructions and the overall project layout, see the [README](README.md).

## Finding something to work on

- **New to the project?** Look for issues labeled [`good first issue`](https://github.com/hardwood-hq/hardwood/labels/good%20first%20issue). These are scoped to be approachable without prior context on the codebase.
- **Already comfortable with the code?** Issues labeled [`help wanted`](https://github.com/hardwood-hq/hardwood/labels/help%20wanted) are bounded and welcome PRs, but may expect some orientation first.
- **Have an idea not yet tracked?** Open an issue to discuss it before starting work. This avoids wasted effort on something the maintainers would decline or redesign.

## Issue-first workflow

Every change should be linked to a GitHub issue. If one doesn't exist for what you're about to do, file it first — even for small fixes. Commit messages and pull requests reference the issue number.

## Making changes

- Run `./mvnw clean verify` locally before pushing. Docker must be running (the test suite uses Testcontainers).
- Run `./mvnw process-sources` to apply the project's formatting and import ordering.
- Cover behavior changes with tests. Bug fixes should start with a failing test that reproduces the bug.
- If your change adds or modifies a user-facing API (factory method, record, enum, configuration option, CLI option), update the documentation under `docs/content/` in the same PR.
- Keep the public API surface small. Put anything that doesn't need to be user-facing in an `internal` package.

## Adding an encoding

An encoding is a read-side decoder, a write-side encoder, or both. Work through the table for the
direction you are adding. Paths are relative to `core/src/main/java/dev/hardwood/`.

### Read path

| File | Change | Why |
|---|---|---|
| `metadata/Encoding.java` | Add the constant | The name a chunk's metadata reads back as |
| `internal/thrift/ThriftEnumLookup.java` | Add it to `ENCODINGS`, at its Thrift index | Maps the value on the wire to that constant; without it the encoding reads back as `UNKNOWN` |
| `internal/encoding/<Name>Decoder.java` | Implement `ValueDecoder` | Reads a page's values into the reader's primitive arrays |
| `internal/reader/PageDecoder.java` | Add the case to `decodeTypedValues` | The reader's only dispatch point |

### Write path

| File | Change | Why |
|---|---|---|
| `writer/ColumnEncoding.java` | Add the policy | What a caller can name in `WriterConfig` |
| `internal/writer/EncodingSupport.java` | Add it to `supports`, naming its legal physical types | The pairs the writer accepts, rejected at writer creation rather than at flush |
| `internal/encoding/<Name>Encoder.java` | Implement the inverse of the decoder | Produces one page's value section |
| `internal/writer/<Type>ValueEncoder.java` | Add the case to `encode`, in each type that may carry it | Each physical type encodes from its own value store |
| `internal/writer/ColumnChunkBuffer.java` | Map the policy in `valueEncoding` | Names the encoding in the page header and `ColumnMetaData.encodings` |
| `WriterEncodingPolicyTest`, `CoverageDomain`, `WriterInteropTest` | Restate the policy in each switch, and add it to the interop axis | Sweeps it over every legal type, the coverage domain, and the parquet-java interop axis |

The write path is guarded by exhaustive switches, so the compiler names most of the rows above. The
read path is not, so cover the decoder yourself — a round trip through the writer where Hardwood can
write the encoding, and a `parquet-testing` fixture where it can only read it.

Finally, update `docs/content/` for the public enums, and `ROADMAP.md` and `FORMAT_COVERAGE.md` for
the capability itself. Nothing in the build checks those.

## Design docs for larger changes

Larger changes — new features, refactorings that affect the system design — should start with a short Markdown document under `_designs_/` describing the intended end state. Open the design as a PR so it can be reviewed before implementation starts. Mark it complete once the work lands.

## Agent skills

This repository is the source of truth for the [agent skills](https://agentskills.io) under `skills/` (e.g. `skills/hardwood-cli/`, which teaches AI coding agents how to drive the `hardwood` CLI). They live here, alongside the code they document, so a change to a CLI flag, output label, or command is made in the same PR as the matching skill edit — the skill can't drift from the tool.

Distribution is automated. The [`hardwood-hq/hardwood-skills`](https://github.com/hardwood-hq/hardwood-skills) repository wraps `skills/` in the Claude Code plugin and marketplace manifests and is what users install. It is a **published mirror** — never edit its `skills/` directly. When a change to `skills/` lands on `main`, the [Trigger skills publish](.github/workflows/skills-trigger.yml) workflow fires a `repository_dispatch` at that repository, which re-syncs `skills/` from here and commits it; you don't need to do anything.

To publish out-of-band — testing against a local checkout, or a manual backfill — run the same sync by hand:

```shell
tools/publish-skills.sh /path/to/hardwood-skills
```

then commit and push in that checkout (the script prints the exact commands).

## Commit messages

Every commit message begins with the issue key:

```
#123 Brief description of the change
```

This applies to every commit, including fixups.

Focus the body on **why** the change is being made, with at most a high-level overview of **what**. The diff already shows the what; don't restate it as a bullet list. Keep it concise — a short paragraph is usually enough.

## Opening a pull request

The [PR template](.github/PULL_REQUEST_TEMPLATE.md) covers the basics: build passes, commit message format, test coverage, and documentation updates. Please run through it before requesting review.

## A note on AI-assisted contributions

This project is aiming for a high-quality, maintainable codebase.

This informs our stance on using coding agents:
LLM assistance is welcome; vibe coding is not.
Use whatever tools you like, but you are responsible for the changes you submit.
You must understand at least the key parts of a change and be able to stand behind it in review.
Built with AI, not by AI.

Specifically, it is not acceptable to point an LLM to an issue from the tracker,
accept its output without consideration and open a pull request.
Instead, satisfy yourself a) that the original issue was sensible to begin with,
b) that the generated code solves that issue, and c) that it does so in line with the
project's standards for correctness, completeness and performance.

In addition, pull requests opened by autonomous agents, with no person submitting them, are closed without review,
whether or not the change itself is correct.
The same applies to unsolicited pull requests from accounts submitting at scale across many unrelated projects,
whether or not a person is behind them: the project has no review capacity for them,
and that holds regardless of how good an individual change may be.

Code reviews are also how we get to know the people we're working with,
and that only works when there is an actual human being on the other side of the conversation,
who has an interest in this project.
Contributing under your real name is encouraged for the same reason, though it is not a requirement.

The PR template asks you to confirm that the change comes from a human
who understands it and can discuss it in review.
Ticking it untruthfully is a conduct issue rather than a code-quality one, and accounts that repeat it are blocked.
If a PR of yours is closed under this policy and that was a misread, say so on the PR and it will be re-evaluated.

## License

By contributing, you agree that your contributions will be licensed under the Apache License 2.0.
