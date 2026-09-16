"""Writes the two release-timeline slides into slides-cinderella.md.

The slides sit between <!-- release-timeline:N --> and <!-- /release-timeline:N --> markers.
Part 1 reveals Jan to Mar, part 2 shows those as given and reveals Apr to Jun.
Coordinates are slide pixels (1280 x 720)."""

from datetime import date
from pathlib import Path
import re

DECK = Path(__file__).resolve().parent.parent / "slides-cinderella.md"

AXIS_Y = 395
LEFT, RIGHT = 96, 1184
START, END = date(2026, 1, 1), date(2026, 7, 1)


def x(d):
    return round(LEFT + (d - START).days / (END - START).days * (RIGHT - LEFT))


# Each event: date, part, optional release label, optional card. Events of the slide's own part
# appear one click each, unless "step" groups them; "card_step" gives the card its own click.
# A card is (kind, content, left, width, height) for a note, or (kind, content, left, width, native size)
# for an image. Cards keep CARD_GAP from the axis, above and below alike.
CARD_GAP = 97
BELOW_TOP = AXIS_Y + CARD_GAP
EVENTS = [
    dict(d=date(2026, 1, 4), part=1, label="First commit", card_date=date(2026, 1, 6),
         card=("img", "images/tl-2026-01-06-announced.png", 96, 280, (1288, 510)), above=True),
    dict(d=date(2026, 1, 31), part=1,
         card=("img", "images/tl-2026-01-31-perf.png", 392, 380, (1402, 594)), above=True),
    dict(d=date(2026, 2, 7), part=1,
         card=("img", "images/x-2026-02-07-race-condition.png", 96, 320, (599, 262)), above=False),
    dict(d=date(2026, 2, 26), part=1, label="Alpha1",
         card=("img", "images/tl-2026-02-26-alpha1.png", 430, 360, (1570, 585)), above=False),
    dict(d=date(2026, 3, 17), end=date(2026, 3, 27), part=1, span_label="S3 in ten days"),
    # Part 2 in two clicks: all releases up to 1.0 at once, then the CR1 note on its own.
    dict(d=date(2026, 4, 2), part=2, step=0, label="Beta1"),
    dict(d=date(2026, 4, 29), part=2, step=0, label="Beta2"),
    dict(d=date(2026, 5, 31), part=2, step=0, card_step=1, label="CR1",
         card=("note", "Geospatial pruning ships", 966, 218, 70), above=True),
    dict(d=date(2026, 6, 7), part=2, step=0, label="CR2"),
    dict(d=date(2026, 6, 25), part=2, step=0, label="Final",
         card=("img", "images/tl-2026-06-25-final.png", 810, 374, (1550, 524)), above=False),
]

MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun"]


def svg(body):
    return f'<svg class="rtl-layer" viewBox="0 0 1280 720" width="1280" height="720">{body}</svg>'


def axis():
    body = f'<line class="rtl-axis" x1="{LEFT}" y1="{AXIS_Y}" x2="{RIGHT}" y2="{AXIS_Y}"/>'
    for i, m in enumerate(MONTHS):
        mx = x(date(2026, i + 1, 1))
        body += f'<line class="rtl-tick" x1="{mx}" y1="{AXIS_Y - 6}" x2="{mx}" y2="{AXIS_Y + 6}"/>'
        body += f'<text class="rtl-month" x="{mx + 6}" y="{AXIS_Y + 26}">{m}</text>'
    return svg(body)


def event(e):
    """Returns the event's two layers: connector and card, then dot and labels.

    The second layer of every event is stacked above the first layer of all events,
    so a connector never runs over a dot or a label."""
    ex = x(e["d"])
    marks = ""
    if "end" in e:
        marks += f'<line class="rtl-span" x1="{ex}" y1="{AXIS_Y}" x2="{x(e["end"])}" y2="{AXIS_Y}"/>'
        ex = (ex + x(e["end"])) // 2
    else:
        marks += f'<circle class="rtl-dot" cx="{ex}" cy="{AXIS_Y}" r="7"/>'
    if "span_label" in e:
        marks += f'<text class="rtl-span-label" x="{ex}" y="{AXIS_Y + 26}" text-anchor="middle">{e["span_label"]}</text>'
    if "label" in e:
        marks += f'<text class="rtl-label" x="{ex}" y="{AXIS_Y - 16}" text-anchor="middle">{e["label"]}</text>'
    card_layer = ""
    card = e.get("card")
    if card:
        kind, content, left, width, size = card
        height = round(width * size[1] / size[0]) if kind == "img" else size
        top = AXIS_Y - CARD_GAP - height if e["above"] else BELOW_TOP
        px = x(e.get("card_date", e["d"])) if "end" not in e else ex
        # The connector leaves the axis at the date and meets the card edge at the nearest point.
        cx = min(max(px, left + 16), left + width - 16)
        cy = top + height if e["above"] else top
        connector = f'<line class="rtl-connector" x1="{px}" y1="{AXIS_Y}" x2="{cx}" y2="{cy}"/>'
        style = f'left: {left}px; top: {top}px; width: {width}px; height: {height}px'
        if kind == "img":
            html = f'<img class="rtl-card" src="{content}" style="{style}" alt="">'
        else:
            html = f'<div class="rtl-card rtl-note" style="{style}">{content}</div>'
        card_layer = svg(connector) + html
    return card_layer, svg(marks)


def layer(content, fragment_index):
    if fragment_index is None:
        return f'<div class="rtl-event">{content}</div>'
    return f'<div class="rtl-event fragment" data-fragment-index="{fragment_index}">{content}</div>'


def slide(part):
    cards, marks = [], []
    step = 0
    for e in EVENTS:
        if e["part"] > part:
            continue
        index = card_index = None
        if e["part"] == part:
            if "step" in e:
                index = e["step"]
            else:
                index, step = step, step + 1
            card_index = e.get("card_step", index)
        card_layer, mark_layer = event(e)
        if card_layer:
            cards.append(layer(card_layer, card_index))
        marks.append(layer(mark_layer, index))
    return f'<div class="rtl">\n{axis()}\n' + "\n".join(cards + marks) + '\n</div>'


def main():
    text = DECK.read_text()
    for part in (1, 2):
        pattern = re.compile(
            rf"(<!-- release-timeline:{part} -->\n)(?:.*?\n)?(<!-- /release-timeline:{part} -->)", re.S)
        if not pattern.search(text):
            raise SystemExit(f"markers for release-timeline:{part} not found in {DECK}")
        text = pattern.sub(lambda m: m.group(1) + slide(part) + "\n" + m.group(2), text)
    DECK.write_text(text)


if __name__ == "__main__":
    main()
