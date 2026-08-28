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
from email.header import make_header, decode_header


def hdr(value):
    if not value:
        return ""
    return str(make_header(decode_header(value)))


def when(msg):
    try:
        return email.utils.parsedate_to_datetime(msg.get("date")).timestamp()
    except Exception:
        return 0.0


def body(msg):
    if msg.is_multipart():
        for part in msg.walk():
            if part.get_content_type() == "text/plain":
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


def main():
    if len(sys.argv) < 3:
        print(__doc__.strip(), file=sys.stderr)
        sys.exit(2)
    needle = sys.argv[1].lower()
    seen = set()
    msgs = []
    for path in sys.argv[2:]:
        for msg in mailbox.mbox(path):
            subject = hdr(msg.get("subject"))
            if needle not in subject.lower():
                continue
            mid = msg.get("message-id")
            if mid in seen:
                continue
            seen.add(mid)
            msgs.append(msg)

    if not msgs:
        print(f"no messages matched subject substring: {sys.argv[1]!r}", file=sys.stderr)
        sys.exit(1)

    msgs.sort(key=when)
    print(f"Thread: {len(msgs)} message(s) matching {sys.argv[1]!r}\n")
    for i, msg in enumerate(msgs, 1):
        print("#" * 90)
        print(f"[{i}] {hdr(msg.get('from'))}  |  {msg.get('date')}")
        print(f"    {hdr(msg.get('subject'))}")
        print("#" * 90)
        print(strip_quotes(body(msg)))
        print()


if __name__ == "__main__":
    main()
