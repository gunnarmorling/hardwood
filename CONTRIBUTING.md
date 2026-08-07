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

## Design docs for larger changes

Larger changes — new features, refactorings that affect the system design — should start with a short Markdown document under `_designs_/` describing the intended end state. Open the design as a PR so it can be reviewed before implementation starts. Mark it complete once the work lands.

## Agent skills

This repository is the source of truth for the [agent skills](https://agentskills.io) under `skills/` (e.g. `skills/hardwood-cli/`, which teaches AI coding agents how to drive the `hardwood` CLI). They live here, alongside the code they document, so a change to a CLI flag, output label, or command is made in the same PR as the matching skill edit — the skill can't drift from the tool.

Distribution is separate. The [`hardwood-hq/hardwood-skills`](https://github.com/hardwood-hq/hardwood-skills) repository wraps `skills/` in the Claude Code plugin and marketplace manifests and is what users install. It is a **published mirror** — never edit its `skills/` directly. After changing a skill here, publish it to a local checkout of that repository:

```shell
tools/publish-skills.sh /path/to/hardwood-skills
```

then commit and push in that checkout (the script prints the exact commands). When you edit a skill, note in the PR that `hardwood-skills` needs a follow-up publish so it isn't forgotten.

## Commit messages

Every commit message begins with the issue key:

```
#123 Brief description of the change
```

This applies to every commit, including fixups.

Focus the body on **why** the change is being made, with at most a high-level overview of **what**. The diff already shows the what; don't restate it as a bullet list. Keep it concise — a short paragraph is usually enough.

## Opening a pull request

The PR template checklist ([.github/PULL_REQUEST_TEMPLATE.md](.github/PULL_REQUEST_TEMPLATE.md)) covers the basics: build passes, commit message format, test coverage, and documentation updates. Please run through it before requesting review.

## A note on AI-assisted contributions

LLM-assisted contributions are welcome, but vibe coding — accepting AI-generated changes without understanding them — is not. The aspiration is a high-quality, maintainable codebase; that requires contributors who can explain and defend every line in their PR.

## License

By contributing, you agree that your contributions will be licensed under the Apache License 2.0.
