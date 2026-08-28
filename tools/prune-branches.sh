#!/usr/bin/env bash
#
#  SPDX-License-Identifier: Apache-2.0
#
#  Copyright The original authors
#
#  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
#

# prune-branches.sh — interactively delete worktrees and branches that have been
# merged into main.
#
# Four categories are handled, each with a different (and increasingly safe) delete:
#
#   0. WORKTREES whose checkout is merged          -> git worktree remove <path>
#         Runs first: while a branch is checked out it cannot be deleted, so
#         removing the worktree is what lets category 1 offer the branch.
#   1. LOCAL branches whose PR is merged           -> git branch -D <b>
#   2. Branches you OWN on 'origin' (merged)       -> git push origin --delete <b>
#         "own" = tip commit authored by your git user.email.
#         DESTRUCTIVE: this removes the branch from the shared hardwood-hq repo.
#   3. Remote-tracking refs for OTHER remotes       -> git branch -r -d <remote>/<b>
#      (contributor forks) whose PR is merged
#         Local cleanup only — never touches anyone's fork on GitHub.
#
# WHY GitHub PR state instead of `git merge-base --is-ancestor`:
#   This repo squash-merges, so a feature branch's tip commit is almost never an
#   ancestor of main. Plain git ancestry would therefore report nothing as merged.
#   We ask GitHub (via `gh`) whether each branch's PR is MERGED instead.
#
# WHAT COUNTS AS MERGED: two conditions, both required.
#   1. The ref's tip commit IS the commit GitHub recorded as the PR head
#      (headRefOid) — this identifies which PR the ref belongs to.
#   2. Merging the ref into that PR's merge commit S changes nothing: the result
#      is S's own tree. So every change the ref carries was already in place when
#      the PR merged, and S is reachable from the trunk.
#   (1) alone is not enough — headRefOid tracks the head ref, so it keeps moving
#   if anyone pushes to the branch after the merge. (2) is what actually proves
#   the content landed; refs that pass (1) but fail (2) are reported and kept.
#
# Nothing is deleted without a per-branch y/N confirmation. Use --dry-run to preview.
#
# Portability: written for bash 3.2 (the macOS system bash) — no associative arrays.

set -euo pipefail

DRY_RUN=false
DO_FETCH=true
DO_WORKTREES=true
DO_LOCAL=true
DO_ORIGIN=true
DO_FORKS=true

usage() {
  cat <<'EOF'
Usage: prune-branches.sh [options]

  --dry-run       Show what would be deleted; never prompt, never delete.
  --no-fetch      Skip the initial `git fetch origin --prune`.
  --no-worktrees  Skip category 0 (worktrees on merged checkouts).
  --no-local      Skip category 1 (local branches).
  --no-origin     Skip category 2 (your branches on origin).
  --no-forks      Skip category 3 (contributor fork tracking refs).
  -h, --help      This help.

A branch is "obsolete" when its tip commit is the head of a MERGED GitHub PR.
Requires `gh` (authenticated).
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run)  DRY_RUN=true ;;
    --no-fetch) DO_FETCH=false ;;
    --no-worktrees) DO_WORKTREES=false ;;
    --no-local) DO_LOCAL=false ;;
    --no-origin) DO_ORIGIN=false ;;
    --no-forks) DO_FORKS=false ;;
    -h|--help)  usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
  esac
  shift
done

# --- preconditions ----------------------------------------------------------

command -v git >/dev/null || { echo "git not found" >&2; exit 1; }
command -v gh  >/dev/null || { echo "gh (GitHub CLI) not found — needed for merge detection" >&2; exit 1; }
git rev-parse --git-dir >/dev/null 2>&1 || { echo "not inside a git repository" >&2; exit 1; }
gh auth status >/dev/null 2>&1 || { echo "gh is not authenticated — run 'gh auth login'" >&2; exit 1; }
git remote get-url origin >/dev/null 2>&1 || { echo "no 'origin' remote" >&2; exit 1; }

MY_EMAIL=$(git config user.email || true)
[[ -n "$MY_EMAIL" ]] || { echo "git user.email is not set" >&2; exit 1; }

# hardwood-hq/hardwood — derived from origin's URL (handles ssh and https forms).
ORIGIN_URL=$(git remote get-url origin)
REPO=${ORIGIN_URL#*github.com[:/]}
REPO=${REPO%.git}
REPO_OWNER=${REPO%%/*}

# --- refresh remote state ----------------------------------------------------

# A dry run must be side-effect-free, and `git fetch --prune` mutates local
# tracking refs — so skip it (results may be as stale as your last fetch).
if $DRY_RUN; then
  DO_FETCH=false
  echo ">> --dry-run: skipping fetch (tracking refs may be stale)"
fi

# Fetch ONLY origin — enough to make categories 1/2 act on current state.
# Deliberately NOT `--all`: fetching contributor forks would re-create the very
# tracking refs category 3 then deletes (and re-create them again next run,
# since the branches still exist on those forks). Category 3 works purely on
# whatever fork refs you have already accumulated locally.
if $DO_FETCH; then
  echo ">> git fetch origin --prune"
  git fetch origin --prune --quiet
fi

# --- build merged-PR lookup (one API call) -----------------------------------
# Tab-separated "headOid<TAB>prNumber<TAB>owner/headRef<TAB>mergeOid", one line
# per merged PR. Looked up per ref with awk (exact field match — no regex/glob
# surprises with slashes or dots in branch names).

MERGED_FILE=$(mktemp)
trap 'rm -f "$MERGED_FILE"' EXIT

echo ">> querying merged PRs from $REPO"
gh pr list --repo "$REPO" --state merged --limit 1000 \
  --json number,headRefName,headRefOid,headRepositoryOwner,mergeCommit \
  --jq '.[] | "\(.headRefOid)\t\(.number)\t\(.headRepositoryOwner.login)/\(.headRefName)\t\(.mergeCommit.oid // "-")"' > "$MERGED_FILE"
echo ">> $(wc -l < "$MERGED_FILE" | tr -d ' ') merged PR head commits known"

# The head oid is the INDEX — it points at the PR a ref might belong to. Matching
# on branch NAME instead is unsound in both directions: a local branch reused for
# later work keeps a name whose PR is long merged, and a fork's tracking-ref
# prefix is a local remote nickname that need not equal the GitHub login.
merged_pr_at() { # $1 = commit oid -> prints "<number>\t<owner>/<headRef>\t<mergeOid>"
  awk -F'\t' -v o="$1" '$1==o { print $2 "\t" $3 "\t" $4; exit }' "$MERGED_FILE"
}

# ...but the index alone is not PROOF. GitHub reports headRefOid from the head
# ref, which keeps moving if anyone pushes to the branch after the merge, so an
# oid match can name a PR while the ref carries commits that never landed.
#
# The proof is a tree comparison anchored at the merge commit S: if merging the
# ref into S yields exactly S's own tree, then every change the ref carries was
# already present the moment the PR merged — true for squash, rebase and merge
# commits alike, since trees are compared rather than commits or patch ids.
# Anchoring at S and not at the trunk tip is essential: against a trunk that has
# since evolved the same lines, the merge conflicts and proves nothing.
#
# Then S must be reachable from the trunk, which is what puts the content in the
# branch we actually care about (a PR merged into some other base that was later
# squashed away is not reachable, and correctly fails here).
TRUNK=origin/main
git rev-parse --verify --quiet "$TRUNK" >/dev/null || TRUNK=main

merged_into_trunk() { # $1 = ref, $2 = merge commit oid -> success if ref adds nothing to it
  local ref=$1 merge=$2 want got
  [[ "$merge" == "-" ]] && return 1
  git cat-file -e "$merge^{commit}" 2>/dev/null || return 1   # merge commit not fetched
  git merge-base --is-ancestor "$merge" "$TRUNK" 2>/dev/null || return 1
  want=$(git rev-parse "$merge^{tree}")
  got=$(git merge-tree --write-tree "$merge" "$ref" 2>/dev/null | head -1) || got=
  [[ -n "$got" && "$got" == "$want" ]]
}

# Refs that name a merged PR but fail the tree proof — reported, never deleted.
UNPROVEN=()

provably_merged() { # $1 = ref, $2 = display name, $3 = merge oid, $4 = pr number
  if merged_into_trunk "$1" "$3"; then
    return 0
  fi
  UNPROVEN+=("$2 (PR #$4) — tip matches the PR head but its content is not contained in $TRUNK")
  return 1
}

# --- protected branches ------------------------------------------------------
# These guards are NOT about losing commits — the containment proof already
# settles that. They protect refs whose existence is itself the point:
#
#   main/master/HEAD  A rebase-merged PR can leave headRefOid equal to its own
#                     merge commit (PR #789 did), so the trunk tip can legitimately
#                     match a merged PR head and pass every content check. Deleting
#                     the trunk would be "safe" and catastrophic. Also covers the
#                     origin/HEAD symref, which resolves to the trunk tip.
#   docs / release    Publishing jobs track these by name. Their content is
#                     merged; their job is to keep existing.

is_protected() { # $1 = bare branch name (no remote prefix)
  local b=$1
  case "$b" in
    main|master|HEAD|'') return 0 ;;
    *-docs)              return 0 ;;   # 1.0.0.CR1-docs, v1.0.0.Final-docs, ...
    v[0-9]*|[0-9]*.[0-9]*) return 0 ;; # v1.0.0.CR2, 1.0.0.Beta2, ...
    *[Rr]elease*)        return 0 ;;   # aalmiray_releases, release-*, ...
  esac
  return 1
}

# Being checked out blocks `git branch -D` and nothing else, so it is a category 1
# concern only — deliberately NOT folded into is_protected(). Doing so also
# shielded origin/<b> and <fork>/<b> from categories 2 and 3, where whether a
# local worktree happens to sit on the branch has no bearing on the remote ref.
#
# Recomputed after every worktree removal: freeing a branch in category 0 is
# precisely what makes it deletable in category 1.
CHECKED_OUT=''
refresh_checked_out() {
  CHECKED_OUT=$(git worktree list --porcelain | sed -n 's#^branch refs/heads/##p')
}
refresh_checked_out

is_checked_out() { # $1 = bare branch name
  grep -Fxq "$1" <<<"$CHECKED_OUT"
}

# --- confirmation + counters -------------------------------------------------

deleted=0
skipped=0

confirm() { # $1 = human description of the action
  if $DRY_RUN; then
    printf '    [dry-run] would %s\n' "$1"
    return 1
  fi
  local ans
  read -r -p "    Delete ($1)? [y/N] " ans </dev/tty
  [[ "$ans" == y || "$ans" == Y ]]
}

tip_line() { # $1 = a committish; prints "<short-sha> <subject>"
  git log -1 --format='%h %s' "$1" 2>/dev/null || echo '<unknown>'
}

# ============================================================================
# 0. WORKTREES whose checkout is merged
# ============================================================================
# Keyed on the worktree's HEAD commit rather than on its branch, so a detached
# worktree parked on a merged PR head is caught the same way a branch is.
#
# Two states mean someone is still using the tree. Both are reported and kept:
#   locked  `git worktree add` under a claude session locks the worktree for the
#           lifetime of that session. Removing it would pull the tree out from
#           under a running agent, so a lock is a hard no — this script never
#           unlocks and never passes --force.
#   dirty   Uncommitted work exists nowhere else. Ignored build output (target/,
#           ...) does not count: `git status --porcelain` omits it.
KEPT_WORKTREES=()

if $DO_WORKTREES; then
  echo
  echo "== worktrees on merged checkouts =="
  # The main worktree holds the repository itself and is always listed first.
  # Nor can a worktree be removed from inside itself.
  MAIN_WORKTREE=$(git worktree list --porcelain | sed -n '1s/^worktree //p')
  HERE=$(git rev-parse --show-toplevel)
  while IFS=$'\t' read -r path head branch locked; do
    [[ "$path" == "$MAIN_WORKTREE" || "$path" == "$HERE" ]] && continue
    is_protected "$branch" && continue
    hit=$(merged_pr_at "$head")
    [[ -z "$hit" ]] && continue
    IFS=$'\t' read -r pr headref merge <<<"$hit"
    label=${branch:-'(detached)'}
    provably_merged "$head" "$path [$label]" "$merge" "$pr" || continue
    if [[ -n "$locked" ]]; then
      KEPT_WORKTREES+=("$path [$label] (PR #$pr) — locked: $locked")
      continue
    fi
    if [[ -n "$(git -C "$path" status --porcelain 2>/dev/null)" ]]; then
      KEPT_WORKTREES+=("$path [$label] (PR #$pr) — uncommitted changes")
      continue
    fi
    printf '  %s  [%s]  (PR #%s)  %s\n' "$path" "$label" "$pr" "$(tip_line "$head")"
    if confirm "git worktree remove $path"; then
      # Freeing the branch here is what lets category 1 offer it below.
      git worktree remove "$path" && { deleted=$((deleted + 1)); refresh_checked_out; }
    elif $DRY_RUN; then
      # A dry run removes nothing, so the branch would still read as checked out
      # and category 1 would show an empty list — the opposite of what a real run
      # does. Drop it from the cache instead to preview the full cascade.
      CHECKED_OUT=$(grep -Fxv "$branch" <<<"$CHECKED_OUT" || true)
    else
      skipped=$((skipped + 1))
    fi
  done < <(git worktree list --porcelain | awk '
      /^worktree /  { path = substr($0, 10) }
      /^HEAD /      { head = $2 }
      /^branch /    { branch = substr($0, 19) }          # strip "branch refs/heads/"
      /^locked/     { locked = (length($0) > 7) ? substr($0, 8) : "held by another process" }
      /^$/          { if (path != "") print path "\t" head "\t" branch "\t" locked
                      path = head = branch = locked = "" }
      END           { if (path != "") print path "\t" head "\t" branch "\t" locked }
    ')
fi

# ============================================================================
# 1. LOCAL branches
# ============================================================================
if $DO_LOCAL; then
  echo
  echo "== Local branches (merged) =="
  # Local review checkouts (pr-880, pr497-rework, …) need no special casing: they
  # sit on the contributor's merged head commit, so the oid lookup finds them.
  # A branch carrying extra local commits on top of that head does NOT match and
  # is deliberately kept — those commits exist nowhere else.
  while IFS= read -r b; do
    is_protected "$b" && continue
    is_checked_out "$b" && continue   # `git branch -D` would only error out
    hit=$(merged_pr_at "$(git rev-parse "refs/heads/$b")")
    [[ -z "$hit" ]] && continue
    IFS=$'\t' read -r pr head merge <<<"$hit"
    provably_merged "refs/heads/$b" "$b" "$merge" "$pr" || continue
    label="PR #$pr"
    # Name the head ref when it isn't this branch — a local checkout of someone
    # else's PR reads confusingly otherwise.
    [[ "$head" != "$REPO_OWNER/$b" ]] && label="PR #$pr, head $head"
    printf '  %s  (%s)  %s\n' "$b" "$label" "$(tip_line "$b")"
    if confirm "git branch -D $b"; then
      git branch -D "$b" && deleted=$((deleted + 1))
    else
      skipped=$((skipped + 1))
    fi
  done < <(git for-each-ref --format='%(refname:short)' refs/heads/)
fi

# ============================================================================
# 2. Branches you own on origin  (DESTRUCTIVE: git push origin --delete)
# ============================================================================
if $DO_ORIGIN; then
  echo
  echo "== origin branches you own (merged) =="
  while IFS= read -r ref; do
    b=${ref#refs/remotes/origin/}
    [[ "$b" == "$ref" ]] && continue      # not under origin/
    is_protected "$b" && continue
    hit=$(merged_pr_at "$(git rev-parse "$ref")")
    [[ -z "$hit" ]] && continue
    IFS=$'\t' read -r pr head merge <<<"$hit"
    provably_merged "$ref" "origin/$b" "$merge" "$pr" || continue
    owner_email=$(git log -1 --format='%ae' "$ref" 2>/dev/null || true)
    [[ "$owner_email" == "$MY_EMAIL" ]] || continue   # only branches you authored
    printf '  origin/%s  (PR #%s)  %s\n' "$b" "$pr" "$(tip_line "$ref")"
    if confirm "git push origin --delete $b  [removes it from the shared repo]"; then
      git push origin --delete "$b" && deleted=$((deleted + 1))
    else
      skipped=$((skipped + 1))
    fi
  done < <(git for-each-ref --format='%(refname)' refs/remotes/origin/)
fi

# ============================================================================
# 3. Contributor fork tracking refs (local cleanup only)
# ============================================================================
if $DO_FORKS; then
  echo
  echo "== contributor fork tracking refs (merged) =="
  echo "   (removes the LOCAL tracking ref only; it returns on the next fetch of"
  echo "    that fork if the branch still exists there. To stop tracking a"
  echo "    contributor for good, remove the remote: git remote remove <name>.)"
  for remote in $(git remote); do
    [[ "$remote" == origin ]] && continue
    while IFS= read -r ref; do
      b=${ref#refs/remotes/$remote/}
      [[ "$b" == "$ref" ]] && continue
      is_protected "$b" && continue
      # Keyed on the tip commit, not "$remote/$b": a remote's name is a local
      # nickname ('arnab' for the fork of GitHub user 'arnabnandy7'), so pairing
      # it with the head ref name silently misses that contributor's merged PRs.
      hit=$(merged_pr_at "$(git rev-parse "$ref")")
      [[ -z "$hit" ]] && continue
      IFS=$'\t' read -r pr head merge <<<"$hit"
      provably_merged "$ref" "$remote/$b" "$merge" "$pr" || continue
      printf '  %s/%s  (PR #%s)  %s\n' "$remote" "$b" "$pr" "$(tip_line "$ref")"
      if confirm "git branch -r -d $remote/$b  [local tracking ref only]"; then
        git branch -r -d "$remote/$b" && deleted=$((deleted + 1))
      else
        skipped=$((skipped + 1))
      fi
    done < <(git for-each-ref --format='%(refname)' "refs/remotes/$remote/")
  done
fi

echo
# `${arr[*]+x}` rather than `${#arr[@]}`: under `set -u`, bash 3.2 rejects the
# length of an empty array as an unbound variable. The `+` form never does.
if [[ -n "${UNPROVEN[*]+x}" ]]; then
  echo "== kept: named a merged PR but failed the containment proof =="
  echo "   (usually commits pushed to the branch after its PR merged — those exist"
  echo "    nowhere else, so review them by hand before removing the ref.)"
  for u in "${UNPROVEN[@]}"; do echo "  $u"; done
  echo
fi

if [[ -n "${KEPT_WORKTREES[*]+x}" ]]; then
  echo "== kept: merged worktrees still in use =="
  echo "   (unlock by ending the claude session holding it, or commit/discard the"
  echo "    changes, then re-run.)"
  for w in "${KEPT_WORKTREES[@]}"; do echo "  $w"; done
  echo
fi

if $DRY_RUN; then
  echo "Dry run complete — nothing deleted."
else
  echo "Done. Deleted: $deleted, kept: $skipped."
fi
