# The arc, section by section

One sentence per section that the audience should leave with, the beats that carry it, and the slides that carry each beat. A slide that carries no beat goes to `slides-cinderella-cuts.md`. Slide numbers refer to `slides-cinderella.md` as of 2026-09-16 and shift as slides move.

Budget: 45 minutes.

## Prologue: the feature that didn't exist (~3 min)

**Message:** A careful maintainer shipped a convincing, tested feature that could not exist.

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
| Reach: no mandatory dependencies, S3 without the SDK, the line moved | 11–13 |
| Speed, part 2: to 1.0, with CR1 quietly shipping the geo feature | 14 |
| The top of the curve | 15 |

## 2 · Midnight (~9 min)

**Message:** The same speed turned against me: plausible but wrong output, threads that never end, a pace that wears you down. A feature that doesn't exist shipped under my name, and I'd lost full control of my codebase.

| Beat | Slides |
|---|---|
| Meandering: amazing and useless the same afternoon | 17 |
| Plausible but wrong: the edited test, "stop probing or guessing" | 18, 19 |
| Early giving up | 20 |
| Thread pulling, and why: #1198 (the +6 −4 diff; "a few moments later"; the 27 PRs popping up, then the stats), the old brake was effort | 21–24 |
| Context switching ("like a psychopath" as relief), no flow: Sep 9 as 205 prompt ticks, split by session, zoom on 45 minutes | 25–27 |
| The geo bug returns: caught before Final, how it got through (claim circled) | 28, 29 |
| The loss: "every diff", lost full control | 30, 31 |
| The bottom: the exhausting post and its counters, the manager thread | 32, 33 |
| Bridge: you are the feedback loop; the curve | 34, 35 |

## Hinge (~2 min)

**Message:** I had the best case; you have the worst, so the way out matters more for you.

Slides 36–38: section, "My case, and yours" (two columns), "I had the best case".

## 3 · A new way of working (~23 min)

**Message:** Where there's no feedback loop, you are the feedback loop. Stop being the loop: build it, make it fast, review what no feedback loop can see, and raise the floor. The four chapters come back as the closing slide.

| Chapter | Beat | Slides |
|---|---|---|
| Intro | The note trainer: vibe-coded, checked by ear | 39–41 |
| Intro | The realisation and the rule: what a feedback loop can check, you can hand off | 42, 43 |
| Intro | The map: stop being the loop (cycle) | 44 |
| Build the feedback loop | What is the oracle (Hardwood / your project), the mirror | 45–47 |
| Build the feedback loop | "Make it faster, Claude!" on the N300, perfasm and the one-local fix, the number decides | 48–50 |
| Make it fast | "I'm feeling our feedback loop is too slow": slow ITs, the licence check CI never saw; give the agent an instrument | 51–53 |
| Review what no feedback loop can see | What caught the geo bug, I own the design (abstract diagram: the API as the hard border, graded attention inside), every API change on a list (japicmp report), the pyramid, what breaks tomorrow, the claim transcript | 54–59 |
| Raise the floor | The ladder as stairs with dated examples, prose rots; the curve, the helix, what outlasts the turn | 60–65 |

Cut: the predicate audit, "Durable knowledge", "The base is the what" (now the pyramid's aside). Backburner: "Decisions are not findings", "Review, as an artifact".

## 4 · The price, and the joy (~5 min)

**Message:** It isn't free, and it's worth it. You give up knowing every line, the code needs constant tending, and people around you struggle to keep up. In exchange, you build things you otherwise never would. "Built with AI, not by AI" means the job moved from the how to the what: review the claim, not the diff.

| Beat | Slides |
|---|---|
| Price: control ("I no longer know every line. And I'm fine with that.") | 67 |
| Price: upkeep (expand, then consolidate) | 68 |
| Price: other people (contributors can't follow) | 69 |
| Joy: nine months; things you otherwise wouldn't build | 70, 71 |
| Message: a quality claim that holds only if you're the arbiter of the what; review the claim, not the diff | 72, 73 |
| Close: stop being the loop, with the sign-off | 74 |

Cut: the search-before-writing rule, the chain. Backburner: sketching code, the real timeline, the formal-specs outlook.

## Backburner

The last section of `slides-cinderella.md`, after the closing slide: candidates that might come back in: upgrading to current Java (two slides), the Flink diagram, the three-turns table, sandboxing (two slides), the contact slide, sketching code, the real timeline, the formal-specs outlook.

