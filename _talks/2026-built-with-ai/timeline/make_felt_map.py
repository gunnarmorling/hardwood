#!/usr/bin/env python3
"""Render the "how it felt" map for the act breaks: rise, fall, rise.

    python3 make_felt_map.py        # writes ../images/felt-map-{magic,midnight,now}.svg

This is a drawing of a felt experience, not a chart of data: no axes, no dates.
The part of the curve already travelled is dark, the rest light, and an accent
dot marks where the talk is.
"""
import os

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, '..', 'images')
W, H = 1200, 520
PAD_X, TOP, BOTTOM = 80, 70, 420
INK, MUTED, LIGHT, ACCENT = '#1f2937', '#6b7280', '#d6dae0', '#b5491f'
FONT = "Helvetica Neue, Helvetica, Arial, sans-serif"

# control points in unit space: x left→right in story time, y bottom→top in how it felt
POINTS = [(0.00, 0.45), (0.12, 0.62), (0.28, 0.90), (0.42, 0.55), (0.55, 0.12), (0.70, 0.38), (0.85, 0.66), (1.00, 0.80)]

def to_px(p):
    return PAD_X + p[0] * (W - 2 * PAD_X), BOTTOM - p[1] * (BOTTOM - TOP)

def catmull_rom(points, steps=40):
    pts = [points[0]] + points + [points[-1]]
    out = []
    for i in range(1, len(pts) - 2):
        p0, p1, p2, p3 = pts[i - 1], pts[i], pts[i + 1], pts[i + 2]
        for s in range(steps):
            t = s / steps
            t2, t3 = t * t, t * t * t
            out.append(tuple(0.5 * ((2 * p1[k]) + (-p0[k] + p2[k]) * t + (2 * p0[k] - 5 * p1[k] + 4 * p2[k] - p3[k]) * t2
                                    + (-p0[k] + 3 * p1[k] - 3 * p2[k] + p3[k]) * t3) for k in (0, 1)))
    out.append(points[-1])
    return out

def polyline(pts, color, width):
    d = ' '.join(f'{x:.1f},{y:.1f}' for x, y in (to_px(p) for p in pts))
    return f'<polyline points="{d}" fill="none" stroke="{color}" stroke-width="{width}" stroke-linecap="round" stroke-linejoin="round"/>'

def render(name, here_x, caption):
    curve = catmull_rom([(x, y) for x, y in POINTS])
    idx = min(range(len(curve)), key=lambda i: abs(curve[i][0] - here_x))
    o = [f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {H}" width="{W}" height="{H}" font-family="{FONT}">',
         f'<rect width="{W}" height="{H}" fill="#ffffff"/>',
         polyline(curve, LIGHT, 5),
         polyline(curve[:idx + 1], INK, 5)]
    for label, (lx, ly), anchor in [('The magic', (0.28, 0.90), 'middle'), ('Midnight', (0.55, 0.12), 'middle'), ('A new way of working', (1.00, 0.80), 'end')]:
        px, py = to_px((lx, ly))
        dy = -26 if ly > 0.5 else 44
        o.append(f'<text x="{px:.1f}" y="{py + dy:.1f}" font-size="24" font-weight="700" fill="{INK}" text-anchor="{anchor}">{label}</text>')
    hx, hy = to_px(curve[idx])
    o.append(f'<circle cx="{hx:.1f}" cy="{hy:.1f}" r="11" fill="{ACCENT}" stroke="#ffffff" stroke-width="3"/>')
    o.append(f'<text x="{hx:.1f}" y="{hy + (40 if curve[idx][1] > 0.5 else -24):.1f}" font-size="18" fill="{INK}" text-anchor="middle" stroke="#ffffff" stroke-width="5" paint-order="stroke">you are here</text>')
    o.append(f'<text x="{PAD_X}" y="{H - 24}" font-size="16" fill="{MUTED}">{caption}</text>')
    o.append('</svg>')
    with open(os.path.join(OUT, name), 'w') as f:
        f.write('\n'.join(o) + '\n')

CAPTION = 'How it felt. Story time, not a calendar; a feeling, not data.'
render('felt-map-magic.svg', 0.28, CAPTION)
render('felt-map-midnight.svg', 0.55, CAPTION)
render('felt-map-now.svg', 1.00, CAPTION)
