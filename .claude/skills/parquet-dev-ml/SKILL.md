---
name: parquet-dev-ml
description: Search the Apache parquet-dev mailing list by downloading the raw monthly mbox archives from lists.apache.org and grepping them locally. Use whenever the user wants to find, read, or quote a thread on the Parquet dev list (dev@parquet.apache.org) — e.g. "search the parquet-dev ML", "find the thread about X on the parquet mailing list", "what did the parquet community decide about Y". Always go via the mbox download; never rely on the JS-rendered list.html page or a summarizing web fetch.
---

# Search the parquet-dev mailing list

The `lists.apache.org` browse UI is a JavaScript app and its `stats.lua` JSON gets truncated/hallucinated when read through a summarizing web fetch. The reliable path is to **download the raw monthly `mbox` archive and grep it locally**. This skill encodes that path.

Archive URL (per calendar month):

```
https://lists.apache.org/api/mbox.lua?list=dev&domain=parquet.apache.org&d=YYYY-MM
```

## Caching rule

- **Past months are immutable** — download once and reuse the cached copy.
- **The current month is live** — always re-download it, because new messages arrive continuously.

`mlfetch.sh` implements exactly this: it re-fetches `YYYY-MM` if it equals the current month (`date +%Y-%m`) or the cache file is missing/empty, otherwise it reuses the cache. Cache lives at `${PARQUET_ML_CACHE:-${XDG_CACHE_HOME:-$HOME/.cache}/parquet-dev-ml}`.

## Procedure

All commands assume the skill directory:

```bash
SKILL="/workspace/.claude/skills/parquet-dev-ml"
```

1. **Fetch the month(s) you need.** Pass one or more `YYYY-MM`. It prints the local mbox path(s):

   ```bash
   "$SKILL/mlfetch.sh" 2026-07                 # current month → always re-downloaded
   "$SKILL/mlfetch.sh" 2026-05 2026-06 2026-07 # older months served from cache
   ```

   If you don't know when a thread happened, fetch the last few months and grep across all of them. Widen the range rather than guessing a single month.

2. **List subjects / find the thread.** Grep the mbox(es) directly:

   ```bash
   CACHE="${PARQUET_ML_CACHE:-$HOME/.cache/parquet-dev-ml}"
   grep -hi '^Subject:' "$CACHE"/parquet-dev-2026-07.mbox | sort -u        # every subject
   grep -hi '^Subject:.*thrift' "$CACHE"/parquet-dev-*.mbox | sort -u      # by keyword
   ```

   Note the wording of subjects: a `[DISCUSS]`/`[VOTE]`/`[ANNOUNCE]` prefix and phrasing may differ from how the user remembers it (e.g. the user's "Inline parquet.thrift" was really `[DISCUSS] Inline parquet.thrift into parquet-java`). Match on a distinctive substring, not the exact remembered title.

3. **Read a full thread**, de-duplicated (the mbox lists each message twice) and with quoted reply-text stripped, ordered oldest→newest:

   ```bash
   "$SKILL/thread.py" "inline parquet.thrift" "$CACHE"/parquet-dev-2026-07.mbox
   ```

   `thread.py` takes a case-insensitive Subject substring and one or more mbox files, matches every message in that thread, dedupes by `Message-ID`, drops `>`-quoted lines and signatures, and prints each message's From/Date/body.

## Reporting back

When summarizing a thread for the user, give the exact subject line, the participants, and the outcome/consensus — quote the load-bearing sentences rather than paraphrasing a vote. Point them at the saved mbox path so they can read the raw thread if they want.
