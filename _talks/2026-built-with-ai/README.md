# Built with AI, not by AI

Keynote deck. 45 minutes, internal developer conference.

| | |
|---|---|
| Audience | Senior/staff consultants and mid/junior developers. No management in the room. |
| Mandate | Change how they work, and open the day with energy. |
| Theme | Verify the output: the job moves from the *how* to the *what*. |
| Take-away | *Review the claim, not the diff.* |

## Files

| File | What it is |
|---|---|
| `slides.md` | The first version of the deck, kept for reference. |
| `slides-loops.md` | Second version, organised by topic around feedback loops. Open with `?slides=slides-loops.md`. |
| `slides-cinderella.md` | **The current version**: a rise, a fall and a rise, told as a story. Open with `?slides=slides-cinderella.md`. |
| `timeline/` | Data and generators for the charts: `posts.tsv` + `make_timeline.py` for the likes timeline, `make_felt_map.py` for the "how it felt" curve shown at the act breaks. |
| `index.html` | reveal.js shell, loaded from CDN. Rarely touched. |
| `serve.py` | Local dev server with browser caching turned off. The page reloads itself on changes. |
| `helix.js` | The spiral reveal: a ring seen from above that a fragment tilts into a climbing helix. Use `<svg class="helix" data-helix data-turns="3">` plus a `data-helix-tilt` fragment. |
| `custom.css` | Small layout overrides on the reveal `white` theme. |
| `images/` | Screenshots. `01`, `05` and `07`–`10` are real; `02`–`04` and `06` are SVG placeholders. |

## Presenting

reveal.js loads `slides.md` over `fetch`, so it needs a web server; opening
`index.html` from the file system shows a blank deck.

```bash
python3 _talks/2026-built-with-ai/serve.py        # → http://localhost:8000
```

Open `http://localhost:8000/?slides=slides-cinderella.md` for the current version. Without the parameter the page loads `slides.md`.

On `localhost` the page checks the deck file, `custom.css`, `helix.js` and
`index.html` every second and reloads when one of them changes, staying on the
current slide and fragment. That works with any static server; `serve.py` also
turns off browser caching, so reloads never show stale CSS or images. Changed
images alone don't trigger a reload.

- `S` opens the speaker view with notes, timer and next-slide preview. A reload
  of the main window disconnects it; press `S` again.
- `Esc` is the slide overview.
- `?grid` (e.g. `http://localhost:8000/?slides=slides-cinderella.md&grid`) shows every slide at once as a thumbnail grid, with slide numbers. Press `g` on any slide to get here, scrolled to that slide; click a thumbnail to open the deck at it (Cmd/Ctrl-click for a new tab). Zoom the browser to change the thumbnail size.
- `?` lists all shortcuts.

## Exporting a PDF

```
http://localhost:8000/?print-pdf
```

Then print from Chrome: destination *Save as PDF*, layout *Landscape*, margins
*None*, and enable *Background graphics*. That PDF is what goes to Speaker Deck.

## Editing

One slide per `---`, with a blank line either side:

```markdown
## A heading

Body text.

Note:
Speaker notes. Everything after `Note:` is notes, not slide content.

---

## Next slide
```

The blank lines matter: the separator is anchored to `^\n---\n$`, so a Markdown
table (`|---|---|`) or a setext heading underline will not accidentally split a
slide.

Every slide has one of four types, set with a comment on its first line:

| Type | Markup | Layout |
|---|---|---|
| Regular | *(none)* | Title pinned at the top, footer (`#Hardwood #AI · @gunnarmorling`) and slide number |
| Hero | `<!-- .slide: class="hero" -->` | One big sentence or a quote, centred, no footer. `<span class="overline">…</span>` for a small label above |
| Hero image | `<!-- .slide: class="hero-image" -->` | One visual, centred; a `##` title becomes a quiet caption; no footer |
| Section | `<!-- .slide: class="section" data-state="section-slide" data-background-image="images/sections/….jpg" data-background-opacity="0.55" -->` | Act divider: dimmed full-bleed photo, big white caption, `<span class="credit">` for the photo credit, no footer |

Aim for a keynote mix: mostly hero and hero image, regular only where a list, table or code is the point.

Other useful bits:

- `<!-- .slide: class="statement" -->` on the first line — one big sentence
  filling the slide.
- `<!-- .element: class="fragment" -->` after a list item — reveals on click.
- `<em>…</em>` — accent colour, for the one word that carries the sentence.
- `<span class="aside">…</span>` — quiet grey line.
- Fenced code blocks are highlighted; keep them under ~12 lines.

## Screenshots still to take

Replace the placeholder and change the extension in `slides.md`:

| File | What to capture |
|---|---|
| `02-cli-skill` | `hardwood-cli` SKILL.md front matter — name, description, triggers. |
| `03-n300-skill` | `n300-profiling` SKILL.md — the *Access* and conventions section. |
| `04-review-file` | A `_reviews/pr-N-review.md` with `[ ]` checkboxes, grouped by priority. |
| `06-n300-box` | A photo of the N300 on the desk, for scale. |

`08`–`10` are the tweet thread: `08` is the full tweet, `08a`/`08b` are its body
and counters cropped out of it, `09` and `10` are the two replies trimmed to
their content.

`05-note-reading.png` is the screenshot from the note-reading repo's README.
`07-code-review-pyramid.png` is the image from the Code Review Pyramid post on morling.dev.

Worth considering as a sixth: the `git log --oneline | wc -l` / repo-stats
terminal output, if the numbers table feels too abstract on the day.

## Section photos

From Flickr via Openverse, licence allowing commercial use (no share-alike, no
no-derivatives), loosely connected to the act. Credits are on the slides and in
`images/sections/credits.json`. Other candidates are in
`images/sections/candidates/` (with `candidates.json`); delete that folder before
committing.

| File | Photo | Author | Licence | Link |
|---|---|---|---|---|
| `title.jpg` | "Wood Grain" | mrpolyonymous | CC BY 2.0 | https://flic.kr/p/a6j2Z7 |
| `the-magic.jpg` | "TNT" | Alex Holyoake | CC BY 2.0 | https://flic.kr/p/AN2ZRn |
| `midnight.jpg` | "Brighton Clock Tower" | Dominic's pics | CC BY 2.0 | https://flic.kr/p/96bybq |
| `hinge.jpg` | "Rusty hinge" | ConspiracyofHappiness | CC BY 2.0 | https://flic.kr/p/KqAcY |
| `new-way.jpg` | "Mechanics' Institute spiral staircase, from above" | chad_k | CC BY 2.0 | https://flic.kr/p/6AH9Zu |
| `price-joy.jpg` | "Meteorite" | Michael Elleray | CC BY 2.0 | https://flic.kr/p/aCqL2a |

## Timing

`slides-cinderella.md`:

| Part | Slides | Minutes |
|---|---|---|
| Prologue: the feature that didn't exist | 1–6 | 4 |
| 1 · The magic | 7–21 | 10 |
| 2 · Midnight | 22–53 | 18 |
| Hinge | 54–57 | 2 |
| 3 · A new way of working: note trainer, realisation, levels | 58–67 | 7 |
| Behaviour | 68–83 | 10 |
| Performance | 84–101 | 12 |
| The loop itself | 102–106 | 3 |
| Design | 107–122 | 10 |
| Claims, the last curve, the spiral | 123–130 | 5 |
| 4 · The price, and the joy | 131–144 | 8 |

About 89 minutes for a 45-minute slot. Built out on purpose; trim once the
story settles.
