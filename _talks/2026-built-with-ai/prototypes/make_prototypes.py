#!/usr/bin/env python3
"""Writes slides-prototypes.md: visual prototypes for text-heavy slides in slides-cinderella.md.

    python3 prototypes/make_prototypes.py

Data sources: Claude Code session transcripts under /claude-config (decision ticks),
`git log main` of the Hardwood repository (commit calendar), and fixed numbers from the deck.
"""
import collections, csv, datetime, glob, json, math, os, re, subprocess
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


# 8. The claim against its own table (#1103, Sep 10) -------------------------------------------------
def claim_table():
    return"""##"Matches both oracles"?

<span class="subtitle">PR #1103, Sep 10</span>

<span class="ct-go fragment" data-fragment-index="0"></span>

<div class="ct">
<div class="ct-claim"><span class="ct-who">Claude</span> The bug reproduces before the fix, and the fix <mark>matches both oracles</mark>.</div>

<table class="ct-table">
<thead><tr><th>#1142</th><th>parquet-java</th><th>DuckDB</th><th>Hardwood before</th><th>Hardwood after</th></tr></thead>
<tbody>
<tr><td><code>amount &gt; 1.27</code></td><td>3.00</td><td>3.00</td><td class="bad">−2.56</td><td class="ct-flag"><span class="ct-bolt">⚡</span>throws</td></tr>
</tbody>
</table>

<blockquote class="transcript ct-me fragment" data-fragment-index="1">
"…that's not what the table suggests for 1142?"
<cite>Me</cite>
</blockquote>
</div>

<div class="ct-card fragment" data-fragment-index="2">
<p class="ct-card-head">Why throw? The PR: 1.27 can be stored as <code>7F</code> or as <code>00 7F</code>,<br>so searching by bytes can miss it.</p>
<p class="ct-card-test">Test file with 1.27 stored as <code>00 7F</code>, searched with <code>7F</code>:</p>
<table class="ct-table">
<tbody><tr><td>parquet-java</td><td class="good">found</td><td class="ct-dim">compares the bytes as numbers</td></tr></tbody>
</table>
<p class="ct-verdict">"Matches both" hid a refusal, built on a false reason.<br>Reworked, it now does match both.</p>
</div>

Note:
Prototype, replaces the transcript slide in the review chapter. Three clicks.

Setup: a DECIMAL column stores numbers as bytes, and you could search it with
a byte value. Hardwood compared the bytes as plain byte strings. The bytes of
-2.56 start with FF, which sorts above 7F, the bytes of 1.27. So "amount greater
than 1.27" returned -2.56.

On arrival: Claude's summary of the before/after run against parquet-java and
DuckDB, and the row for that bug. Before the fix: -2.56, the bug reproduces.
That half of the claim holds.

Click 1: the other half doesn't. The fix doesn't compare correctly, it refuses.
Hardwood throws, while both oracles answer. "Matches both oracles" reported a
design decision as parity.

Click 2: my question. I didn't read the code for this. I read the sentence
against the table.

Click 3: why refuse? The PR said: 1.27 can be stored as 7F or padded as 00 7F,
so a byte-by-byte search with 7F could miss a padded value. Claude wrote a test
file storing 1.27 as 00 7F and searched it with 7F. parquet-java finds 1.27 in
it, because it compares these bytes as numbers. The reason, repeated in the
issue, the PR, the release note and the docs, was false. The claim wasn't just
sloppy wording: it hid the one decision in the PR that was wrong. I reworked
the PR, and it now matches both oracles.

The diff was fine. The claim wasn't.
"""

# 9. Prose rots: a comment perfasm disproved (#250 → #456) -------------------------------------------
def prose_rots():
    return"""## Prose rots. Checks don't.

<span class="subtitle">One comment, in 29 filter matchers, May 11 to Sep 10</span>

<span class="rot-go fragment" data-fragment-index="0"></span>

<pre class="rot-comment"><code class="nohighlight" data-noescape>// Build the predicate bitmap ignoring nulls. The inner loop is fixed at 64
// iterations and uses a branchless `(cond ? 1 : 0) << b` pack so HotSpot
// <mark class="rot-claim">fully unrolls it and auto-vectorizes the comparison</mark>. The tail is split
// off to keep the hot loop's trip count constant at 64.</code></pre>

<div class="rot-proof fragment" data-fragment-index="0">
<pre class="asm"><code class="nohighlight" data-noescape>mov    0x20(%rax,%r14,8),%rdi   ; load value
xor    %r13d,%r13d
cmp    %r9,%rdi                 ; compare to literal
setg   %r13b                    ; 0 or 1
shlx   %r11,%r13,%r13           ; shift into place
or     %rcx,%r8                 ; accumulate into word</code></pre>
<div class="rot-verdict">
<p><strong>perfasm</strong>, C2</p>
<p>Six scalar instructions per value</p>
<p>Zero <code>ymm</code></p>
<p>Unrolled 4×, not fully</p>
</div>
</div>

<p class="aside rot-close fragment" data-fragment-index="1">The only documentation that doesn't rot is documentation that <em>runs</em>.</p>

Note:
Prototype, gives "Prose rots. Checks don't." its evidence. Two clicks.

On arrival: a comment that sat in 29 filter matchers for four months. It said
the JIT unrolls the loop and vectorizes the comparison. That claim was the reason
the code has its awkward branchless shape.

Click 1: perfasm on the compiled code. Six scalar instructions per value, not a
single vector register. The comment was never true. It read well, it went
through review, and nothing could check it until something ran.

Click 2: the line. The design docs are worth writing as input. They are not a
record.

Facts: the comment came in with #250 (a contributed PR, May 11) and was corrected
in 49e59d81 (#456, Sep 10). The asm is an excerpt of LongGtBatchMatcher::test,
C2 level 4, JDK 25. Don't attribute the comment to anyone on stage.
"""


# 10. What no test catches: a false sentence about the design (#9 docs) --------------------------------
def no_test_catches():
    return"""## What no test catches

<span class="subtitle">Hardwood docs, the write model, Aug 22</span>

<div class="doc-excerpt">
<p><strong>Page size</strong> governs read granularity. A reader that skips pages by their statistics can only skip whole pages.</p>
<p><strong>Row-group size</strong> governs <mark class="doc-claim">read parallelism</mark> and split sizing, and on the write side it is the memory bound above.</p>
</div>

<blockquote class="transcript doc-me fragment" data-fragment-index="0">
"…governs read parallelism" in the docs is not correct: we parallelize reading at the chunk and even page level.
<cite>Me, Aug 26</cite>
</blockquote>

<div class="doc-checks fragment" data-fragment-index="1">
<span class="doc-label">Passed it</span> <span>Tests</span> <span>Docs build</span>
<span class="doc-label doc-caught">Caught it</span> <span class="doc-owner">Someone who knows the design</span>
</div>

Note:
Prototype, closes the review chapter with a catch that went right. Two clicks.

On arrival: a sentence from the writer docs. It reads like something a Parquet
expert would write. Row groups are the unit people usually associate with
parallel reads, in Spark for instance.

Click 1: it's wrong for Hardwood. The reader runs two virtual threads per
column and decodes pages concurrently inside a row group. Row-group size doesn't
bound its parallelism at all. I flagged it, and it was corrected the next day
(2c71576d).

Click 2: nothing else could have caught it. Tests don't read docs. The docs build
checks form, not truth. The only check for a sentence about
the design is someone who owns the design.

That's the part of review that stays with you.
"""


# 11. What Hardwood cost: measured token spend, extrapolated back by lines added ----------------------
TALK_SESSIONS = {'f21cf717-fb5b-4875-9391-d378ef580191', 'a39a6433-b0f8-4623-88a5-180f7d847ee5'}
DATA_FILES = re.compile(r'(\.parquet$|\.json$|\.tsv$|\.csv$|/resources/|\.svg$|\.lock$|package-lock|\.cast$|_reviews/|_talks/|\.min\.)')


def cost_chart():
    """Cumulative API-price cost of Hardwood. Measured from the session transcripts (inputs/tokens/sessions.tsv,
    written by inputs/tokens/usage.py) from Aug 10, when the kept transcripts start; before that, each week's
    lines added (git, data files excluded) times the measured cost per line added."""
    measured_from = datetime.date(2026, 8, 10)
    start = datetime.date(2026, 1, 4)
    end = datetime.date(2026, 9, 17)
    rows = list(csv.DictReader(open(os.path.join(DECK, 'inputs', 'tokens', 'sessions.tsv')), delimiter='\t'))
    cost_by_day = collections.Counter()
    for r in rows:
        if r['session'] in TALK_SESSIONS:
            continue
        d = datetime.date.fromisoformat(r['start'][:10])
        if d >= measured_from:
            cost_by_day[d] += float(r['usd'])
    log = subprocess.run(['git', '-C', REPO, 'log', 'origin/main', '--no-merges', '--numstat', '--format=@%ad', '--date=short'],
                         capture_output=True, text=True).stdout
    lines_by_day = collections.Counter()
    day = None
    for line in log.splitlines():
        if line.startswith('@'):
            day = datetime.date.fromisoformat(line[1:])
            continue
        parts = line.split('\t')
        if len(parts) == 3 and parts[0] != '-' and not DATA_FILES.search(parts[2]):
            lines_by_day[day] += int(parts[0])
    measured = sum(cost_by_day.values())
    per_line = measured / sum(v for d, v in lines_by_day.items() if d >= measured_from)

    days = [start + datetime.timedelta(n) for n in range((end - start).days + 1)]
    cum, total, split = [], 0.0, None
    for d in days:
        total += cost_by_day[d] if d >= measured_from else lines_by_day[d] * per_line
        cum.append(total)
        if d == measured_from - datetime.timedelta(1):
            split = total
    W, H, L, R, T, B = 1100, 470, 80, 150, 20, 60
    ymax = 20000
    x = lambda i: L + (W - L - R) * i / (len(days) - 1)
    y = lambda v: T + (H - T - B) * (1 - v / ymax)
    k = (measured_from - start).days
    def area(i0, i1):
        pts = ' '.join(f'{x(i):.1f},{y(cum[i]):.1f}' for i in range(i0, i1 + 1))
        return f'{x(i0):.1f},{y(0):.1f} {pts} {x(i1):.1f},{y(0):.1f}'
    body = ['<defs><pattern id="cost-hatch" width="8" height="8" patternUnits="userSpaceOnUse" patternTransform="rotate(45)">'
            f'<rect width="8" height="8" fill="#f3e3dc"/><line x1="0" y1="0" x2="0" y2="8" stroke="{ACC}" stroke-opacity="0.35" stroke-width="3"/></pattern></defs>']
    for v in range(0, ymax + 1, 5000):
        body.append(f'<line x1="{L}" y1="{y(v):.1f}" x2="{W - R}" y2="{y(v):.1f}" stroke="#e5e7eb" stroke-width="1"/>')
        body.append(f'<text x="{L - 12}" y="{y(v) + 6:.1f}" text-anchor="end" class="p-sub" fill="{DIM}">${v // 1000}k</text>')
    for m in range(1, 10):
        d = datetime.date(2026, m, 1)
        i = max(0, (d - start).days)
        body.append(f'<text x="{x(i):.1f}" y="{H - B + 30}" text-anchor="middle" class="p-sub" fill="{DIM}">{d.strftime("%b")}</text>')
    # The hatched area is the extrapolated spend, carried on under the measured weeks as their baseline;
    # the solid area is only what the transcripts measured on top of it.
    hatch = ' '.join(f'{x(i):.1f},{y(min(cum[i], split)):.1f}' for i in range(len(days)))
    body.append(f'<polygon points="{x(0):.1f},{y(0):.1f} {hatch} {x(len(days) - 1):.1f},{y(0):.1f}" fill="url(#cost-hatch)"/>')
    body.append(f'<polyline points="{" ".join(f"{x(i):.1f},{y(cum[i]):.1f}" for i in range(0, k + 1))}" fill="none" stroke="{ACC}" stroke-width="2" stroke-dasharray="6 5"/>')
    solid = ' '.join(f'{x(i):.1f},{y(cum[i]):.1f}' for i in range(k, len(days)))
    body.append(f'<polygon points="{x(k):.1f},{y(split):.1f} {solid} {x(len(days) - 1):.1f},{y(split):.1f}" fill="{ACC}" fill-opacity="0.85"/>')
    body.append(f'<line x1="{L}" y1="{y(0):.1f}" x2="{W - R}" y2="{y(0):.1f}" stroke="{INK}" stroke-width="2"/>')
    body.append(f'<text x="{x(len(days) - 1) + 12:.1f}" y="{y(cum[-1]) + 8:.1f}" class="p-big" fill="{INK}">${cum[-1] / 1000:.0f}k</text>')
    body.append(f'<text x="{x(k * 0.3):.1f}" y="{y(split * 0.62):.1f}" text-anchor="middle" class="p-label" fill="{DIM}">extrapolated</text>')
    body.append(f'<text x="{x(k * 0.3):.1f}" y="{y(split * 0.62) + 26:.1f}" text-anchor="middle" class="p-sub" fill="{DIM}">lines added × ${per_line:.3f} per line</text>')
    mx = x((k + len(days) - 1) / 2)
    body.append(f'<text x="{x(k) - 14:.1f}" y="{y(split) - 64:.1f}" text-anchor="end" class="p-label" fill="{ACC}">measured</text>')
    body.append(f'<text x="{x(k) - 14:.1f}" y="{y(split) - 38:.1f}" text-anchor="end" class="p-sub" fill="{DIM}">${measured:,.0f} in 5½ weeks →</text>')
    chart = svg(W, H, ''.join(body))
    return f"""<!-- .slide: class="" -->

## What did Hardwood cost?

<span class="subtitle">At API list prices, Jan 4 to Sep 17</span>

{chart}

Note:
Prototype. Measured: every API response in the session transcripts since Aug 10
(when the kept transcripts start), priced at list prices: Opus 5 at $5/$25 per
million tokens, cache writes 1.25x (5 min) or 2x (1 h), cache reads 0.1x.
${measured:,.0f} for Hardwood work, talk sessions excluded. Most of it is cache
reads: long contexts re-sent every turn.

Extrapolated: before Aug 10, each week's lines added on main (data files, reviews
and the talk excluded) times the measured cost per line added,
${per_line:.3f}. That assumes spring cost per line was like August's; the models
and the way of working were different, so treat the hatched part as an order of
magnitude.

Say it as "on the order of ${cum[-1] / 1000:.0f}k at API list prices". It isn't what I
paid: a subscription costs a fraction. And it's only the sessions in this
container.
"""


# 12. "Make it faster, Claude!" thinned out: the box as the hero (B), and the loop as a diagram (C) ----
def n300_hero():
    return """## "Make it faster, Claude!"

<div class="columns n300-hero">
<div>

A 7 W box, 500 EUR, silent.<br>
The agent SSHes in and measures.

<span class="aside"><code>perfnorm</code>, <code>async-profiler</code>, <code>perfasm</code>.<br>
Works pretty well. If you can tell <em>faster</em> from <em>plausible</em>.</span>

</div>
<div>
<img class="plain" src="images/06-n300-box.jpg" alt="The Minix NEO Z300 on the desk">
</div>
</div>

Note:
Prototype B for slide 44: the photo carries it, the tool names sit in the aside.

Everything else is spoken: why a separate box (macOS blocks dtrace, no clean way
to pin a core, the laptop runs everything else), why this one (eight cores of one
kind, no performance/efficiency mix), the downsides (one memory channel, no
AVX-512), and that all of it runs unattended while I read the conclusions and the
numbers under them.
"""


def n300_loop():
    """The same loop shape as the chapter, made physical, with the box's photo inside the middle node."""
    arrow = ('<svg class="n300-arrow" viewBox="0 0 90 24" width="90" height="24">'
             f'<path d="M2 12 L74 12" stroke="{ACC}" stroke-width="4"/>'
             f'<path d="M72 4 L88 12 L72 20 z" fill="{ACC}"/></svg>')
    back = ('<svg class="n300-back" viewBox="0 0 1000 90" width="1000" height="90" preserveAspectRatio="none">'
            f'<path d="M980 8 C980 78, 20 78, 20 16" fill="none" stroke="{DIM}" stroke-width="3" stroke-dasharray="8 7"/>'
            f'<path d="M12 4 L28 12 L14 22 z" fill="{DIM}"/></svg>')
    return f"""## "Make it faster, Claude!"

<span class="subtitle">A 7 W box on the desk, 500 EUR, silent</span>

<div class="n300">
<div class="n300-row">
<div class="n300-node"><strong>My branch</strong><span>pushed over SSH</span></div>
{arrow}
<div class="n300-box">
<img class="plain" src="images/06-n300-box.jpg" alt="The Minix NEO Z300 on the desk">
<div><strong>Clock pinned</strong><span><code>JMH</code></span><span><code>perfnorm</code></span><span><code>async-profiler</code></span><span><code>perfasm</code></span><span>unattended</span></div>
</div>
{arrow}
<div class="n300-node"><strong>Numbers back</strong><span>with the assembly</span></div>
</div>
{back}
<p class="n300-again">the next variant, minutes later</p>
</div>

Note:
Prototype C for slide 44, with the box's photo in the middle node: the chapter's
loop, made physical. The agent pushes the branch, the box measures, the numbers
come back, and it goes again, unattended.

Spoken: why a separate box (macOS blocks dtrace, no clean way to pin a core, the
laptop runs everything else), why this one (eight cores of one kind), the
downsides (one memory channel, no AVX-512), and that I read the conclusions and
the numbers under them. "The number decides what worked" comes one slide later,
so the caveat doesn't need to be on this slide.
"""



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
    return f'''## The agent types. I only decide.

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
    return f'''## Nine months

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
    return f'''## S3 support

<span class="subtitle">Mar 17: with the AWS SDK · Mar 27: without it</span>

{svg(1000, 410, body)}

Note:
Prototype, replaces slide 15. Mirrors the classpath slide: 31 JARs against one
file. The signer passes AWS's published test vectors.
'''


# 8. The geo thread: the slides of the geo story, with a common backdrop --------------------------------
GEO_TITLES = ["## A contributor's pull request", '## How does someone careful end up here?',
              '## We caught it before Final.', '## How it got through', '## What caught the geo bug?']


def geo_thread():
    import re
    deck = open(os.path.join(DECK, 'slides-cinderella.md')).read()
    blocks = re.split(r'\n---\n(?=\n|<!--)', deck)
    out = []
    for title in GEO_TITLES:
        block = next(b for b in blocks if title in b).strip()
        if 'geo-backdrop' in block:
            out.append(block + '\n')
            continue
        attrs = 'data-background-image="images/geo-backdrop.svg" data-background-size="cover"'
        m = re.match(r'<!-- \.slide: class="([^"]*)"(.*?)-->', block)
        if m:
            block = f'<!-- .slide: class="{m.group(1)} geo"{m.group(2)} {attrs} -->' + block[m.end():]
        else:
            block = f'<!-- .slide: class="geo" {attrs} -->\n\n' + block
        out.append(block + '\n')
    return out


def main():
    slides = [n300_hero(), n300_loop(), cost_chart(), prose_rots(), no_test_catches(), claim_table(), prologue(), thread(), ladder(), claim_vs_spec(), decisions(), calendar(), s3()] + geo_thread()
    with open(os.path.join(DECK, 'slides-prototypes.md'), 'w') as f:
        f.write('\n---\n\n'.join(s.strip() + '\n' for s in slides))


if __name__ == '__main__':
    main()
