#!/usr/bin/env python3
"""Print a de-duplicated parquet-dev thread with quoted text stripped.

Usage: thread.py SUBJECT_SUBSTRING mbox [mbox ...]

Matches every message whose Subject contains SUBJECT_SUBSTRING (case-insensitive,
so it catches both the root and its "Re:" replies), dedupes by Message-ID (the
lists.apache.org mbox lists each message twice), orders oldest->newest, and prints
each message's From/Date/body with '>'-quoted lines and signatures removed.
"""
import sys
import mailbox
import email.utils
import hashlib
from email.header import make_header, decode_header
from pathlib import Path
import re


def hdr(value):
    if not value:
        return ""
    return str(make_header(decode_header(value)))


def when(msg):
    try:
        return email.utils.parsedate_to_datetime(msg.get("date")).timestamp()
    except (TypeError, ValueError, OverflowError):
        return 0.0


def body(msg):
    if msg.is_multipart():
        for part in msg.walk():
            if part.get_content_type() == "text/plain" and not part.get_filename():
                payload = part.get_payload(decode=True) or b""
                return payload.decode(part.get_content_charset() or "utf-8", "replace")
        return ""
    payload = msg.get_payload(decode=True) or b""
    return payload.decode(msg.get_content_charset() or "utf-8", "replace")


def strip_quotes(text):
    out = []
    blanks = 0
    for line in text.splitlines():
        stripped = line.strip()
        if stripped.startswith(">"):
            strip_quote_attribution(out)
            continue
        if stripped.startswith("--") and len(stripped) < 40:
            break  # signature delimiter
        if not stripped:
            blanks += 1
            if blanks > 1:
                continue
        else:
            blanks = 0
        out.append(line.rstrip())
    return "\n".join(out).strip()


def strip_quote_attribution(lines):
    end = len(lines) - 1
    while end >= 0 and not lines[end].strip():
        end -= 1
    if end < 0 or not re.search(
        r"(?:\bwrote|\ba écrit)\s*:$", lines[end].strip(), re.IGNORECASE
    ):
        return
    start = end
    while start > 0 and lines[start - 1].strip():
        start -= 1
    del lines[start:]


def identity(msg, text):
    message_id = msg.get("message-id")
    if message_id:
        return message_id
    fallback = "\0".join(
        (hdr(msg.get("from")), hdr(msg.get("date")), hdr(msg.get("subject")), text)
    )
    return hashlib.sha256(fallback.encode("utf-8")).hexdigest()


def main():
    if len(sys.argv) < 3:
        print(__doc__.strip(), file=sys.stderr)
        sys.exit(2)
    needle = sys.argv[1].casefold()
    paths = [Path(path) for path in sys.argv[2:]]
    missing = [str(path) for path in paths if not path.is_file()]
    if missing:
        print(f"mbox file not found: {missing[0]}", file=sys.stderr)
        sys.exit(2)
    seen = set()
    msgs = []
    for path in paths:
        for msg in mailbox.mbox(path, create=False):
            subject = hdr(msg.get("subject"))
            if needle not in subject.casefold():
                continue
            text = body(msg)
            mid = identity(msg, text)
            if mid in seen:
                continue
            seen.add(mid)
            msgs.append((msg, text))

    if not msgs:
        print(f"no messages matched subject substring: {sys.argv[1]!r}", file=sys.stderr)
        sys.exit(1)

    msgs.sort(key=lambda item: when(item[0]))
    print(f"Thread: {len(msgs)} message(s) matching {sys.argv[1]!r}\n")
    for i, (msg, text) in enumerate(msgs, 1):
        print("#" * 90)
        print(f"[{i}] {hdr(msg.get('from'))}  |  {msg.get('date')}")
        print(f"    {hdr(msg.get('subject'))}")
        print("#" * 90)
        print(strip_quotes(text))
        print()


if __name__ == "__main__":
    main()
