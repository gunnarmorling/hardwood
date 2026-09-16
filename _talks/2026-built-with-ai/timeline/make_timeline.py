#!/usr/bin/env python3
"""Render the sentiment timeline as SVG from posts.tsv, phases.tsv and events.tsv.

    python3 make_timeline.py          # writes ../images/timeline-start.svg and ../images/timeline-full.svg

Column height is proportional to likes on X, on a linear scale. Posts with very
few likes still get a short stub so they stay visible; the exact count is in each
column's tooltip.
"""
import csv, datetime, html, os

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, '..', 'images')

W, H = 1200, 640
LEFT, RIGHT = 70, 30
AXIS_Y = 420            # baseline of the like columns
PLOT_H = 280            # height for the maximum like count
START = datetime.date(2025, 12, 20)
END = datetime.date(2026, 9, 20)

INK, MUTED, GRID, BAND = '#1f2937', '#6b7280', '#e5e7eb', '#f3f4f6'
ACCENT, DEEMPH = '#b5491f', '#aab1bb'
FONT = "Helvetica Neue, Helvetica, Arial, sans-serif"

def read(name):
    with open(os.path.join(HERE, name), newline='') as f:
        return list(csv.DictReader(f, delimiter='\t', quoting=csv.QUOTE_NONE))

def d(s):
    return datetime.date.fromisoformat(s)

def x(day):
    span = (END - START).days
    return LEFT + (W - LEFT - RIGHT) * (day - START).days / span

def esc(s):
    return html.escape(s, quote=True)

def render(cutoff, highlight, title, path):
    posts, phases, events = read('posts.tsv'), read('phases.tsv'), read('events.tsv')
    max_likes = max(int(p['likes']) for p in posts)
    o = []
    o.append(f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {H}" width="{W}" height="{H}" font-family="{FONT}">')
    o.append(f'<rect width="{W}" height="{H}" fill="#ffffff"/>')
    o.append(f'<text x="{LEFT}" y="40" font-size="26" font-weight="700" fill="{INK}">{esc(title)}</text>')
    o.append(f'<text x="{LEFT}" y="68" font-size="16" fill="{MUTED}">Selected posts · column height = likes on X</text>')

    # phase bands, alternating, labelled above the plot
    for i, ph in enumerate(phases):
        s, e = max(d(ph['start']), START), min(d(ph['end']), END)
        if d(ph['start']) > cutoff:
            continue
        faded = False
        if i % 2 == 0:
            o.append(f'<rect x="{x(s):.1f}" y="{AXIS_Y - PLOT_H - 30}" width="{x(e) - x(s):.1f}" height="{PLOT_H + 30}" fill="{BAND}"/>')
        if not faded:
            o.append(f'<text x="{(x(s) + x(e)) / 2:.1f}" y="{AXIS_Y - PLOT_H - 40}" font-size="14" fill="{MUTED}" text-anchor="middle">{esc(ph["label"])}</text>')

    # gridlines and y ticks
    for v in (0, 2000, 4000):
        yy = AXIS_Y - PLOT_H * v / max_likes
        o.append(f'<line x1="{LEFT}" x2="{W - RIGHT}" y1="{yy:.1f}" y2="{yy:.1f}" stroke="{GRID}" stroke-width="1"/>')
        o.append(f'<text x="{LEFT - 10}" y="{yy + 5:.1f}" font-size="13" fill="{MUTED}" text-anchor="end">{v:,}</text>')

    # month ticks on the axis
    m = datetime.date(2026, 1, 1)
    while m <= END:
        o.append(f'<text x="{x(m):.1f}" y="{AXIS_Y + 22}" font-size="13" fill="{MUTED}" text-anchor="middle">{m.strftime("%b")}</text>')
        m = datetime.date(m.year + (m.month // 12), m.month % 12 + 1, 1)

    # events (the geo bug) as a bracket just above the axis
    for ev in events:
        s_, e_ = d(ev['start']), d(ev['end'])
        if s_ > cutoff:
            continue
        yy = AXIS_Y - 34
        o.append(f'<line x1="{x(s_):.1f}" x2="{x(e_):.1f}" y1="{yy}" y2="{yy}" stroke="{INK}" stroke-width="2" stroke-linecap="round"/>')
        for xe in (x(s_), x(e_)):
            o.append(f'<line x1="{xe:.1f}" x2="{xe:.1f}" y1="{yy - 6}" y2="{yy + 6}" stroke="{INK}" stroke-width="2" stroke-linecap="round"/>')
        o.append(f'<text x="{(x(s_) + x(e_)) / 2:.1f}" y="{yy - 14}" font-size="14" fill="{INK}" text-anchor="middle">{esc(ev["label"])}</text>')

    # columns
    label_y = {'1': AXIS_Y + 58, '2': AXIS_Y + 88, '3': AXIS_Y + 118, '4': AXIS_Y + 148}
    labels = []
    for p in posts:
        day = d(p['date'])
        if day > cutoff:
            continue
        likes = int(p['likes'])
        cx = x(day)
        if p['platform'] == 'talk':
            o.append(f'<g><title>{esc(p["date"])} · {esc(p["label"])}</title>'
                     f'<rect x="{cx - 5:.1f}" y="{AXIS_Y - 5}" width="10" height="10" transform="rotate(45 {cx:.1f} {AXIS_Y})" fill="{INK}" stroke="#ffffff" stroke-width="2"/></g>')
        else:
            hgt = max(PLOT_H * likes / max_likes, 3)
            color = ACCENT if p['date'] in highlight else DEEMPH
            tip = f'{p["date"]} · {p["label"]} · {likes:,} likes'
            o.append(f'<g><title>{esc(tip)}</title>'
                     f'<rect x="{cx - 5:.1f}" y="{AXIS_Y - hgt:.1f}" width="10" height="{hgt:.1f}" rx="{min(4, hgt / 2):.1f}" fill="{color}"/>'
                     f'<rect x="{cx - 9:.1f}" y="{AXIS_Y - hgt - 4:.1f}" width="18" height="{hgt + 8:.1f}" fill="transparent"/></g>')
            if p['date'] in highlight and likes >= 1000:
                o.append(f'<text x="{cx - 12:.1f}" y="{AXIS_Y - hgt + 16:.1f}" font-size="15" font-weight="700" fill="{INK}" text-anchor="end">{likes:,}</text>')
        if p['label_row']:
            ly = label_y[p['label_row']]
            o.append(f'<line x1="{cx:.1f}" x2="{cx:.1f}" y1="{AXIS_Y + 6}" y2="{ly - 14}" stroke="{GRID}" stroke-width="1"/>')
            labels.append(f'<text x="{cx:.1f}" y="{ly}" font-size="15" fill="{INK}" text-anchor="{p["anchor"]}" stroke="#ffffff" stroke-width="5" paint-order="stroke">{esc(p["label"])}</text>')
    o.extend(labels)   # labels last, so their halo covers leader lines crossing them

    o.append(f'<line x1="{LEFT}" x2="{W - RIGHT}" y1="{AXIS_Y}" y2="{AXIS_Y}" stroke="{MUTED}" stroke-width="1"/>')
    if cutoff < END:
        o.append(f'<text x="{x(cutoff) + 40:.1f}" y="{AXIS_Y - 20}" font-size="30" fill="{MUTED}">…</text>')
    o.append('</svg>')
    with open(path, 'w') as f:
        f.write('\n'.join(o) + '\n')

render(datetime.date(2026, 1, 6), {'2026-01-04', '2026-01-06'}, 'The start of the year', os.path.join(OUT, 'timeline-start.svg'))
render(END, {'2026-09-11'}, 'Nine months, in my own posts', os.path.join(OUT, 'timeline-full.svg'))
