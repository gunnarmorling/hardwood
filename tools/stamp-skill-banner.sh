#!/usr/bin/env bash
#
#  SPDX-License-Identifier: Apache-2.0
#
#  Copyright The original authors
#
#  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
#

#
# Stamps a "generated file" banner into each published SKILL.md so nobody edits
# the mirror by hand. Shared by the publish path — tools/publish-skills.sh and
# the hardwood-skills receiver workflow both call it, so the banner can't drift.
#
# The banner is inserted as YAML comments immediately after the opening `---` of
# the frontmatter: a comment before `---`, or an HTML comment inside the
# frontmatter, would stop the skill's frontmatter from parsing.
#
# Usage: tools/stamp-skill-banner.sh <skills-dir>

set -euo pipefail

dir="${1:?Usage: tools/stamp-skill-banner.sh <skills-dir>}"

while IFS= read -r -d '' skill; do
  name="$(basename "$(dirname "$skill")")"
  if [ "$(head -n 1 "$skill")" != "---" ]; then
    echo "error: $skill does not start with YAML frontmatter ('---')" >&2
    exit 1
  fi
  url="https://github.com/hardwood-hq/hardwood/blob/main/skills/$name/SKILL.md"
  tmp="$(mktemp)"
  {
    printf -- '---\n'
    printf '# =============================================================================\n'
    printf '# ATTENTION: GENERATED FILE — DO NOT EDIT.\n'
    printf '# This is a published copy. Edit the source of truth in hardwood-hq/hardwood\n'
    printf '# and CI republishes it here:\n'
    printf '#   %s\n' "$url"
    printf '# =============================================================================\n'
    tail -n +2 "$skill"
  } > "$tmp"
  mv "$tmp" "$skill"
done < <(find "$dir" -name SKILL.md -print0)
