# The arc, section by section

One sentence per section that the audience should leave with, the beats that carry it, and the slides that carry each beat. A slide that carries no beat goes to `slides-cinderella-cuts.md`. Slide numbers refer to `slides-cinderella.md` as of 2026-09-16 and shift as slides move.

Budget: a 75-minute slot including Q&A; about 60 minutes of talk.

## Prologue: the feature that didn't exist (~3 min)

**Message:** A careful maintainer shipped a convincing, tested feature that could not exist.

The geo slides (2, 3, 26, 27, 50) share a backdrop, a faint map grid with a dashed bounding box; the CR1 card on the release timeline has the same dashed outline.

| Beat | Slides |
|---|---|
| The PR #413 screenshot, with stamps landing on it: tests green, merged, shipped in CR1, then "The feature couldn't work" | 2 |
| How does someone careful end up here? | 3 |

## 1 · The magic (~8 min)

**Message:** AI made a project that used to be irrational cheap enough to start, and progress was startlingly fast.

| Beat | Slides |
|---|---|
| The frenzy at the turn of the year (three clicks) | 5 |
| Hype or real? My Jan 4 post; only one way to find out, on a real problem | 6, 7 |
| Nobody built this, because it cost too much, until the price changed | 8 |
| A new project | 9 |
| Speed, part 1: first mention, perf numbers, race condition, Alpha1, "Is Hardwood vibe-coded? Absolutely not." (plants the title), S3 in ten days | 10 |
| Reach: S3 without the SDK, the line moved | 11–12 |
| Speed, part 2: to 1.0, with CR1 quietly shipping the geo feature | 13 |
| The top of the curve | 14 |

## 2 · Midnight (~9 min)

**Message:** The same speed turned against me: plausible but wrong output, threads that never end, a pace that wears you down. A feature that doesn't exist shipped under my name, and I'd lost full control of my codebase.

| Beat | Slides |
|---|---|
| Meandering: amazing and useless the same afternoon | 16 |
| Plausible but wrong: the edited test | 17 |
| Early giving up | 18 |
| Thread pulling, and why: #1198 (the +6 −4 diff; "a few moments later"; the 27 PRs popping up, then the stats), the old brake was effort | 19–22 |
| The pace: Sep 9 as 205 prompt ticks, split into 21 sessions, zoom on 45 minutes; the confession "I'm dialling it back"; "like a psychopath" as relief | 23–25 |
| At that pace, something slips: the geo bug returns, caught before Final, how it got through (claim circled) | 26, 27 |
| The loss: "every diff", lost full control | 28, 29 |
| The bottom: the exhausting post ("The exhaustion is real"): counters boxed, then the replies threaded below (accountability, then "welcome to being a manager") | 30 |
| Bridge: you are the feedback loop; the curve | 31, 32 |

## Hinge (~2 min)

**Message:** I had it easy; your projects are harder, so the way out matters more for you.

Slides 33–34: section, "My case, and yours" (two columns, with "I had it easy. Your projects are harder. So what got me out?" as the last click).

## 3 · A new way of working (~23 min)

**Message:** Where there's no feedback loop, you are the feedback loop. Stop being the loop: build it, make it fast, review what no feedback loop can see, and raise the floor. The four chapters come back as the closing slide.

| Chapter | Beat | Slides |
|---|---|---|
| Intro | The note trainer: vibe-coded, checked by playing it | 36, 37 |
| Intro | The realisation and the rule: what a feedback loop can check, you can hand off | 38, 39 |
| Intro | The map: stop being the loop (cycle) | 40 |
| Build the feedback loop | Where the agent lives: one directory mounted, a Docker socket that only starts containers — the precondition for leaving it running | 42 |
| Build the feedback loop | What is the oracle (Hardwood / your project), the mirror | 43, 44 |
| Build the feedback loop | "Make it faster, Claude!" as the loop made physical (branch → the 7 W box → numbers back), perfasm and the one-local fix, with "the number decides" as its last click | 45, 46 |
| Make it fast | "I'm feeling our feedback loop is too slow": licence check, parallel tests, Hadoop's config in the slowest tests, dev loop 60 s → 28 s (bar chart); give the agent an instrument | 48, 49 |
| Review what no feedback loop can see | What caught the geo bug, I own the design (abstract diagram: the API as the hard border, graded attention inside), every API change on a list (japicmp report), the pyramid, what no test catches (the docs sentence on read parallelism, caught by knowing the design) | 51–55 |
| Raise the floor | The ladder as stairs with dated examples, prose rots (the matcher comment perfasm disproved); the curve, the helix | 57–60 |

Cut: the predicate audit, "Durable knowledge", "The base is the what" (now the pyramid's aside). Backburner: "Decisions are not findings", "Review, as an artifact", "What specifically breaks?", the #1142 claim transcript (outcome superseded on main; visual prototype in `slides-prototypes.md`).

## 4 · The price, and the joy (~5 min)

**Message:** It isn't free, and it's worth it. You give up knowing every line, the code needs constant tending, and people around you struggle to keep up. In exchange, you build things you otherwise never would. "Built with AI, not by AI" means the job moved from the how to the what: review the claim, not the diff.

| Beat | Slides |
|---|---|
| Price: control ("I no longer know every line. And I'm fine with that.") | 62 |
| Price: upkeep (expand, then consolidate) | 63 |
| Price: other people (contributors can't follow) | 64 |
| Joy: nine months; things you otherwise wouldn't build | 65, 66 |
| Message: a quality claim that holds only if you're the arbiter of the what; review the claim, not the diff | 67, 68 |
| Close: stop being the loop, with the sign-off | 69 |

Cut: the search-before-writing rule, the chain. Backburner: sketching code, the real timeline, the formal-specs outlook.

## Backburner

The last section of `slides-cinderella.md`, after the closing slide: candidates that might come back in: the "What did Hardwood cost?" chart (Q&A backup), upgrading to current Java (two slides), the Flink diagram, the three-turns table, the Feb 1 sandboxing post (lead-in for "So I can walk away"), the contact slide, sketching code, the real timeline, the formal-specs outlook, "What specifically breaks?", the #1142 claim transcript, "It has no mandatory dependencies", "Can we stop any probing or guessing", "What raises the floor is what outlasts the turn", the "how can that be?" transcript excerpt (licence check).

