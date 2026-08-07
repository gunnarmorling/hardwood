#!/usr/bin/env bash
#
# Publishes the agent skills from this repository to a local checkout of
# hardwood-hq/hardwood-skills, which distributes them as a Claude Code plugin.
#
# The main repository is the source of truth for skill content so it stays in
# lockstep with the CLI it documents; hardwood-skills only wraps it in the
# plugin/marketplace manifests. This script copies skills/ into that checkout;
# review and commit there afterwards.
#
# Usage: tools/publish-skills.sh /path/to/hardwood-skills

set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
dest="${1:?Usage: tools/publish-skills.sh /path/to/hardwood-skills}"

if [ ! -d "$dest/.claude-plugin" ]; then
  echo "error: $dest does not look like a hardwood-skills checkout (no .claude-plugin/)" >&2
  exit 1
fi

rm -rf "$dest/skills"
cp -a "$repo_root/skills" "$dest/skills"

sha="$(git -C "$repo_root" rev-parse --short HEAD)"
echo "Synced skills/ -> $dest/skills/ (from hardwood@$sha)"
echo
echo "Next, review and commit in the hardwood-skills checkout:"
echo "  git -C \"$dest\" add skills"
echo "  git -C \"$dest\" commit -m \"Publish skills from hardwood@$sha\""
echo "  git -C \"$dest\" push"
