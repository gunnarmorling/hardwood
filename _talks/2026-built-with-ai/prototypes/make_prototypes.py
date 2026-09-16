#!/usr/bin/env python3
"""Writes slides-prototypes.md: visual prototypes for text-heavy slides in slides-cinderella.md.

    python3 prototypes/make_prototypes.py

Data sources: Claude Code session transcripts under /claude-config (decision ticks),
`git log main` of the Hardwood repository (commit calendar), and fixed numbers from the deck.
"""
import collections, datetime, glob, json, math, os, subprocess
from zoneinfo import ZoneInfo

HERE = os.path.dirname(os.path.abspath(__file__))
DECK = os.path.dirname(HERE)
REPO = os.path.dirname(os.path.dirname(DECK))
INK, ACC, DIM, GREEN, RED = '#354045', '#b5491f', '#666666', '#2e7d32', '#c62828'
TZ = ZoneInfo('Europe/Berlin')


def svg(w, h, body, cls=''):
    return f'<svg class="proto {cls}" viewBox="0 0 {w} {h}" width="{w}" height="{h}">{body}</svg>'


# 1. Prologue: stamps on the pull request -----------------------------------------------------------
def prologue():
    stamps = [
        ('✓ Tests green', GREEN, 770, 60, -5),
        ('✓ Merged, May 1', GREEN, 740, 140, 3),
        ('✓ Shipped in 1.0.0.CR1', GREEN, 640, 220, -4),
    ]
    html = '<div class="stamped">\n  <img src="images/01-geo-pr-413.png" width="1100" height="445" style="max-height: none" alt="PR #413, merged May 1">\n'
    for i, (text, color, x, y, rot) in enumerate(stamps):
        html += (f'  <div class="stamp fragment" data-fragment-index="{i}" '
                 f'style="left: {x}px; top: {y}px; --rot: {rot}deg; --c: {color}">{text}</div>\n')
    html += (f'  <div class="stamp stamp-big fragment" data-fragment-index="3" '
             f'style="left: 60px; top: 320px; --rot: -5deg; --c: {RED}">The feature couldn\'t work</div>\n</div>')
    return f'''## A contributor's pull request

{html}

Note:
Prototype, replaces slides 2–5. One picture instead of four text slides.

Click: tests green. Click: merged, May 1. Click: shipped in CR1. Pause.
Click: Parquet doesn't store that information per page. The feature couldn't work.
'''


# 2. #1198: from +6 −4 to +21,500 −6,200 ----------------------------------------------------------------
def thread():
    small_lines, big_lines = 10, 21500 + 6200
    big_side = 300
    small_side = big_side * math.sqrt(small_lines / big_lines)
    x0, base = 230, 400
    body = (
        f'<rect x="{x0}" y="{base - small_side:.1f}" width="{small_side:.1f}" height="{small_side:.1f}" fill="{ACC}"/>'
        f'<text x="{x0 - 20}" y="{base - 6}" text-anchor="end" class="p-label" fill="{INK}">Sep 6</text>'
        f'<text x="{x0 - 20}" y="{base + 24}" text-anchor="end" class="p-sub" fill="{DIM}">1 file, +6 −4</text>'
        f'<g class="fragment grow"><rect x="{x0}" y="{base - big_side}" width="{big_side}" height="{big_side}" fill="{ACC}" fill-opacity="0.85"/></g>'
        f'<g class="fragment" data-fragment-index="0">'
        f'<text x="{x0 + big_side + 60}" y="{base - big_side + 30}" class="p-label" fill="{INK}">Sep 14</text>'
        f'<text x="{x0 + big_side + 60}" y="{base - big_side + 66}" class="p-big" fill="{ACC}">+21,500 −6,200</text>'
        f'<text x="{x0 + big_side + 60}" y="{base - big_side + 100}" class="p-sub" fill="{DIM}">169 files · 60 commits</text>'
        f'<text x="{x0 + big_side + 60}" y="{base - big_side + 130}" class="p-sub" fill="{DIM}">25 issues closed, 17 of them bugs</text>'
        '</g>'
    )
    body = body.replace('class="fragment grow"', 'class="fragment grow" data-fragment-index="0"')
    return f'''## Issue #1198: a small docs PR

{svg(1000, 430, body)}

Note:
Prototype, replaces slide 24. Area is proportional to lines changed.

The tiny square is the PR as first opened. Click: eight days later.
'''


# 3. The ladder as stairs -----------------------------------------------------------------------------
def ladder():
    steps = [
        ('Ask in prose', ['Sep 4: a rule in CLAUDE.md', 'Sep 8: “why again?”']),
        ('Automated check', ['Filler prose → PR build check', '`var` → compiler error']),
        ('Unrepresentable', ['Sep 14: co-author trailer', 'off in the settings file']),
    ]
    body = ''
    w, h, x0, base = 310, 90, 25, 440
    for i, (title, lines) in enumerate(steps):
        x = x0 + i * (w + 10)
        top = base - (i + 1) * h
        g = f'<rect x="{x}" y="{top}" width="{w}" height="{base - top}" fill="{INK}" fill-opacity="{0.25 + 0.3 * i}"/>'
        g += f'<text x="{x + 16}" y="{top + 36}" class="p-label" fill="#ffffff">{i + 1}</text>'
        g += f'<text x="{x + 48}" y="{top + 36}" class="p-step" fill="#ffffff">{title}</text>'
        for k, line in enumerate(lines):
            g += f'<text x="{x + 6}" y="{top - 20 - (len(lines) - 1 - k) * 26}" class="p-sub" fill="{INK}">{line.replace("`var`", "var")}</text>'
        body += f'<g class="fragment" data-fragment-index="{i}">{g}</g>'
    return f'''## Automate the top: the ladder

{svg(1000, 460, body)}

Note:
Prototype, replaces slide 63. One click per step; the examples sit on the step
they reached.
'''


# 4. The claim next to the spec ------------------------------------------------------------------------
def claim_vs_spec():
    return '''## What caught the geo bug?

<div class="circled">
  <img src="images/01-geo-pr-413-claim.png" width="1000" height="140" alt="PR #413 description: page-level stats on ColumnIndex">
  <svg class="circle-mark" viewBox="0 0 100 100" preserveAspectRatio="none" style="left: 50%; top: 22%; width: 40.5%; height: 36%"><path d="M52 6 C 86 4, 99 30, 97 52 C 95 80, 60 96, 34 93 C 10 90, 1 68, 3 46 C 5 20, 30 5, 60 8"/></svg>
</div>

<div class="columns top">
<div>

```thrift
struct ColumnIndex {   // the page index
  1: null_pages
  2: min_values
  3: max_values
  4: boundary_order
  5: null_counts
  6: repetition_level_histograms
  7: definition_level_histograms
  8: nan_counts
}
```

</div>
<div class="verdict-list">

<p><span class="no">✗</span> The tests: they tested the claim</p>
<p><span class="no">✗</span> The review: it passed</p>
<p><span class="yes">✓</span> Someone who asked whether it exists</p>

</div>
</div>

Note:
Prototype, replaces slide 56. No clicks: the picture is the answer. Top: the
claim, page-level stats on ColumnIndex. Bottom left: what the spec's page index
actually holds. No geospatial field. Geospatial statistics exist only per column
chunk (ColumnMetaData, field 17).
'''


# 5. Decisions as ticks -------------------------------------------------------------------------------
SKIP = ('<command', '<local-command', 'Caveat:', '<system-reminder', '[Request interrupted',
        'This session is being continued', '<task-notification', '<bash-')


def human_messages(day):
    out = []
    for path in glob.glob('/claude-config/projects/-workspace/*.jsonl'):
        sid = os.path.basename(path)[:8]
        for line in open(path, errors='replace'):
            if '"type":"user"' not in line:
                continue
            try:
                d = json.loads(line)
            except ValueError:
                continue
            if d.get('type') != 'user' or d.get('isMeta') or d.get('isSidechain'):
                continue
            c = d.get('message', {}).get('content')
            if isinstance(c, list):
                if any(isinstance(x, dict) and x.get('type') == 'tool_result' for x in c):
                    continue
                c = ' '.join(x.get('text', '') for x in c if isinstance(x, dict) and x.get('type') == 'text')
            if not isinstance(c, str) or not c.strip() or c.strip().startswith(SKIP):
                continue
            t = datetime.datetime.fromisoformat(d['timestamp'].replace('Z', '+00:00')).astimezone(TZ)
            if t.date().isoformat() == day:
                out.append((t, sid))
    return sorted(out)


def decisions(day='2026-09-09'):
    msgs = human_messages(day)
    gaps = sorted((b[0] - a[0]).total_seconds() / 60 for a, b in zip(msgs, msgs[1:]))
    median = gaps[len(gaps) // 2]
    h0, h1 = 9, 24
    X0, X1 = 150, 970

    def x(t):
        return X0 + (t.hour + t.minute / 60 + t.second / 3600 - h0) / (h1 - h0) * (X1 - X0)

    axis = ''
    for hh in range(h0, h1 + 1, 3):
        xx = X0 + (hh - h0) / (h1 - h0) * (X1 - X0)
        axis += f'<text x="{xx:.1f}" y="440" text-anchor="middle" class="p-sub" fill="{DIM}">{hh:02d}:00</text>'
        axis += f'<line x1="{xx:.1f}" y1="60" x2="{xx:.1f}" y2="415" stroke="#e5e7eb" stroke-width="1"/>'
    merged = f'<text x="{X0 - 16}" y="228" text-anchor="end" class="p-sub" fill="{DIM}">all sessions</text>'
    merged += ''.join(f'<line x1="{x(t):.1f}" y1="190" x2="{x(t):.1f}" y2="250" stroke="{ACC}" stroke-width="2.5"/>' for t, _ in msgs)
    counts = collections.Counter(s for _, s in msgs)
    # One row per session, ordered by its first prompt of the day.
    order = sorted(counts, key=lambda sid: min(tt for tt, ss in msgs if ss == sid))
    row_h = 330 / len(order)
    split = f'<text x="{X0 - 16}" y="{70 + 330 / 2}" text-anchor="end" class="p-sub" fill="{DIM}">{len(order)} sessions</text>'
    for i, sid in enumerate(order):
        y = 70 + i * row_h
        split += f'<line x1="{X0}" y1="{y + row_h / 2:.1f}" x2="{X1}" y2="{y + row_h / 2:.1f}" stroke="#eef0f2" stroke-width="1"/>'
        for tt, ss in msgs:
            if ss == sid:
                split += f'<line x1="{x(tt):.1f}" y1="{y + 2:.1f}" x2="{x(tt):.1f}" y2="{y + row_h - 2:.1f}" stroke="{ACC}" stroke-width="2.5"/>'
    # Zoom: the 45 minutes with the most switches between sessions.
    z0 = datetime.datetime(2026, 9, 9, 17, 50, tzinfo=TZ)
    z1 = z0 + datetime.timedelta(minutes=45)
    window = [(tt, ss) for tt, ss in msgs if z0 <= tt < z1]
    zorder = sorted({ss for _, ss in window}, key=lambda sid: min(tt for tt, s2 in window if s2 == sid))
    switches = sum(1 for a, b in zip(window, window[1:]) if a[1] != b[1])

    def zx(tt):
        return X0 + (tt - z0).total_seconds() / (z1 - z0).total_seconds() * (X1 - X0)

    zrow = 330 / len(zorder)
    zoom = ''
    for m in range(0, 46, 15):
        xx = X0 + m / 45 * (X1 - X0)
        zoom += f'<line x1="{xx:.1f}" y1="60" x2="{xx:.1f}" y2="415" stroke="#e5e7eb" stroke-width="1"/>'
        label = (z0 + datetime.timedelta(minutes=m)).strftime('%H:%M')
        zoom += f'<text x="{xx:.1f}" y="440" text-anchor="middle" class="p-sub" fill="{DIM}">{label}</text>'
    for i, sid in enumerate(zorder):
        y = 70 + i * zrow
        zoom += f'<text x="{X0 - 16}" y="{y + zrow / 2 + 7:.1f}" text-anchor="end" class="p-sub" fill="{DIM}">session {chr(65 + i)}</text>'
        zoom += f'<line x1="{X0}" y1="{y + zrow / 2:.1f}" x2="{X1}" y2="{y + zrow / 2:.1f}" stroke="#eef0f2" stroke-width="1"/>'
    path = ''
    for k, (tt, ss) in enumerate(window):
        y = 70 + zorder.index(ss) * zrow + zrow / 2
        path += ('M' if k == 0 else 'L') + f'{zx(tt):.1f} {y:.1f}'
    zoom += f'<path d="{path}" fill="none" stroke="{ACC}" stroke-opacity="0.35" stroke-width="2"/>'
    for tt, ss in window:
        y = 70 + zorder.index(ss) * zrow
        zoom += f'<line x1="{zx(tt):.1f}" y1="{y + 8:.1f}" x2="{zx(tt):.1f}" y2="{y + zrow - 8:.1f}" stroke="{ACC}" stroke-width="5"/>'
    zoom += (f'<text x="{X1}" y="50" text-anchor="end" class="p-sub" fill="{INK}">'
             f'{z0.strftime("%H:%M")}–{z1.strftime("%H:%M")}: {len(window)} prompts, {len(zorder)} sessions, {switches} switches</text>')

    body = (f'<g class="fragment fade-out" data-fragment-index="0">{axis}</g>'
            f'<g class="fragment fade-out" data-fragment-index="0">{merged}</g>'
            f'<g class="fragment fade-in-then-out" data-fragment-index="0">{axis}{split}</g>'
            f'<g class="fragment" data-fragment-index="1">{zoom}</g>')
    body = body.replace('<g class="fragment fade-out" data-fragment-index="0">' + axis + '</g>', '')
    body = f'<g class="fragment fade-out" data-fragment-index="1">{axis}</g>' + body.replace(
        f'<g class="fragment fade-in-then-out" data-fragment-index="0">{axis}{split}</g>',
        f'<g class="fragment fade-in-then-out" data-fragment-index="0">{split}</g>')
    first, last = msgs[0][0].strftime('%H:%M'), msgs[-1][0].strftime('%H:%M')
    return f'''<!-- .slide: class="no-parquet" -->

## The agent types. I only decide.

<span class="subtitle">Sep 9: {len(msgs)} prompts to {len(counts)} sessions, one every {median:.1f} minutes</span>

{svg(1000, 450, body)}

Note:
Prototype, replaces slide 28. {first} to {last}; the 2.7 minutes is the median gap.
Every tick is a prompt I typed to an agent on Sep 9, the busiest day in the
transcripts (Jul 8 to Sep 16; times in Europe/Berlin). Tool approvals don't
count, so the real number of decisions is higher. Click: the same ticks, one row
per session, in the order they started. Every jump between rows is a context
switch.

Click: zoom into 17:50 to 18:35, the 45 minutes with the most switches. The line
follows me from prompt to prompt.
'''


# 6. Nine months as a commit calendar ------------------------------------------------------------------
def calendar():
    days = subprocess.run(['git', '-C', REPO, 'log', 'main', '--format=%ad', '--date=short'],
                          capture_output=True, text=True, check=True).stdout.split()
    counts = collections.Counter(days)
    start = datetime.date(2026, 1, 1)
    start -= datetime.timedelta(days=start.weekday())
    end = datetime.date.fromisoformat(max(days))
    cell, gap, X0, Y0 = 21, 4, 60, 40
    steps = [(0, '#efe9e3'), (1, '#e6c9a3'), (3, '#cf9d63'), (6, '#a86b32'), (11, '#6e4420')]

    def color(n):
        c = steps[0][1]
        for threshold, col in steps:
            if n >= threshold:
                c = col
        return c

    body, d, month_seen = '', start, set()
    while d <= end:
        week = (d - start).days // 7
        x = X0 + week * (cell + gap)
        y = Y0 + d.weekday() * (cell + gap)
        if d.year == 2026:
            body += f'<rect x="{x}" y="{y}" width="{cell}" height="{cell}" rx="3" fill="{color(counts.get(d.isoformat(), 0))}"/>'
            if d.day <= 7 and d.month not in month_seen and d.weekday() == 0:
                month_seen.add(d.month)
                body += f'<text x="{x}" y="{Y0 - 12}" class="p-sub" fill="{DIM}">{d.strftime("%b")}</text>'
        d += datetime.timedelta(days=1)
    for label, day in [('Alpha1', '2026-02-26'), ('1.0', '2026-06-25'), ('1.1 Beta1', '2026-08-31')]:
        dd = datetime.date.fromisoformat(day)
        x = X0 + (dd - start).days // 7 * (cell + gap) + cell / 2
        body += f'<line x1="{x}" y1="{Y0 + 7 * (cell + gap)}" x2="{x}" y2="{Y0 + 7 * (cell + gap) + 18}" stroke="{INK}" stroke-width="2"/>'
        body += f'<text x="{x}" y="{Y0 + 7 * (cell + gap) + 42}" text-anchor="middle" class="p-sub" fill="{INK}">{label}</text>'
    lx = X0 + 30 * (cell + gap)
    ly = Y0 + 7 * (cell + gap) + 70
    body += f'<text x="{lx - 10}" y="{ly + 16}" text-anchor="end" class="p-sub" fill="{DIM}">fewer</text>'
    for i, (_, col) in enumerate(steps):
        body += f'<rect x="{lx + i * (cell + gap)}" y="{ly}" width="{cell}" height="{cell}" rx="3" fill="{col}"/>'
    body += f'<text x="{lx + len(steps) * (cell + gap) + 6}" y="{ly + 16}" class="p-sub" fill="{DIM}">more commits</text>'
    return f'''<!-- .slide: class="no-parquet" -->

## Nine months

<span class="subtitle">{len(days):,} commits to main, {datetime.date.fromisoformat(min(days)).strftime("%b %-d")} to {datetime.date.fromisoformat(max(days)).strftime("%b %-d")}</span>

{svg(1000, 330, body)}

<span class="aside">Test code about as big as main code · 90 design documents · 221 review files</span>

Note:
Prototype, replaces slide 72. One square per day, from `git log main`.
'''


# 7. S3: 31 JARs against one file ----------------------------------------------------------------------
def s3():
    body = ''
    cols = 8
    for i in range(31):
        x = 40 + (i % cols) * 54
        y = 60 + (i // cols) * 62
        body += f'<rect x="{x}" y="{y}" width="46" height="54" rx="5" fill="{INK}" fill-opacity="0.8"/>'
        body += f'<text x="{x + 23}" y="{y + 33}" text-anchor="middle" class="p-tiny" fill="#ffffff">jar</text>'
    body += f'<text x="40" y="360" class="p-label" fill="{INK}">AWS SDK S3 client</text>'
    body += f'<text x="40" y="392" class="p-sub" fill="{DIM}">31 JARs, ~8 MB</text>'
    fx = 640
    body += f'<rect x="{fx}" y="60" width="200" height="250" rx="8" fill="#ffffff" stroke="{ACC}" stroke-width="4"/>'
    for k in range(9):
        body += f'<line x1="{fx + 24}" y1="{100 + k * 22}" x2="{fx + 176 - (k * 37 % 70)}" y2="{100 + k * 22}" stroke="#d6dae0" stroke-width="6" stroke-linecap="round"/>'
    body += f'<text x="{fx}" y="360" class="p-label" fill="{INK}">Aws4Signer.java</text>'
    body += f'<text x="{fx}" y="392" class="p-sub" fill="{DIM}">289 lines, JDK crypto only</text>'
    return f'''<!-- .slide: class="no-parquet" -->

## S3 support

<span class="subtitle">Mar 17: with the AWS SDK · Mar 27: without it</span>

{svg(1000, 410, body)}

Note:
Prototype, replaces slide 15. Mirrors the classpath slide: 31 JARs against one
file. The signer passes AWS's published test vectors.
'''


def main():
    slides = [prologue(), thread(), ladder(), claim_vs_spec(), decisions(), calendar(), s3()]
    with open(os.path.join(DECK, 'slides-prototypes.md'), 'w') as f:
        f.write('\n---\n\n'.join(s.strip() + '\n' for s in slides))


if __name__ == '__main__':
    main()
