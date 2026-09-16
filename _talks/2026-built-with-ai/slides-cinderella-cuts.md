<!-- Slides cut from slides-cinderella.md, kept for Q&A or a longer slot. Not loaded by the deck. -->

<!-- .slide: class="hero-image" -->

## It was magic

<div class="pair">
  <img src="images/x-2026-02-07-race-condition.png" alt="Feb 7: it helped identify and fix a race condition">
  <img src="images/x-2026-01-20-sql-execution-plans.png" alt="Jan 20: LLMs are really good at comparing SQL execution plans">
</div>

Note:
Let the room feel the high; they felt it too in January.

A race condition found and fixed. A fast and a slow Postgres execution plan,
compared in seconds. Paste a hex dump, ask what's wrong, get the right answer.

---

<!-- .slide: class="hero-image" -->

<img class="post" src="images/x-2026-04-20-20h-refactoring.png" alt="Apr 20: Claude: that is about 20h of refactoring. 10 minutes later: Done.">

Note:
"Every single time." The first few times, this is pure joy.

Remember this post. It comes back at midnight.

---

<!-- .slide: class="hero-image" -->

## …mostly

<div class="pair">
  <img src="images/x-2026-01-17-if-else.png" alt="Jan 17: yet another if/else is not gonna save us, mate">
  <img src="images/x-2026-03-28-full-picture.png" alt="Mar 28: What would Claude say if it were a photographer? Now I have the full picture.">
</div>

Note:
The cracks are there from the start, and at this point they're funny. Get the
laugh. Both of these come back.

---

<!-- .slide: class="hero-image" -->

## Seven weeks later

<span class="aside">First commit Jan 4 · 1.0.0.Alpha1 on Maven Central Feb 26</span>

<img src="images/blog-2026-02-26-hardwood-alpha1.png" width="800" height="499" style="max-height: none" alt="Feb 26: Hardwood, a new parser for Apache Parquet: first public release">

Note:
The speed was real. Seven and a half weeks from "Initial import" to a first
release on Maven Central: a multi-threaded reader, nested schemas, all the
common encodings and codecs, and a row and a column API.

Point at the table of contents: "Built With AI, Not By AI" was already a
section in the very first release post.

---

<!-- .slide: class="hero-image" -->

<img class="post" src="images/x-2026-04-16-third-day-now.png" alt="Apr 16: iterating with Claude the third day now on a finicky redesign of performance-critical code">

Note:
Three days on one finicky redesign. Progress, but nothing like the magic.

---

<!-- .slide: class="hero-image" -->

<img class="post" src="images/x-2026-02-18-theory-completely-wrong.png" alt="Feb 18: my theory was completely wrong. Give me once that confidence to make some wild claim based on pure guess work.">

Note:
"My theory was completely wrong." Delivered with the same confidence as the
theory.

---

<!-- .slide: class="hero" -->

## "Plausible" performance wins

that benchmarks reveal as no-ops or regressions.

<span class="aside">Benchmarks are the ground truth, not Claude's narration.</span>

Note:
It will name the cause of a regression, or the reason a change is faster, with
total confidence and no measurement. Often the explanation is convincing and
the number didn't move. Sometimes it got worse.

The rules come later, when we climb out. Here, just let it cost something.

---

## Five causes. Four refuted.

<blockquote class="transcript">
"I've now predicted the cause of that four times (buffer growth, the per-entry page checks, the doubled window refill, the double flush walk) and the measurements refuted the first, the second and the fourth … Rather than offer you a fifth theory…"
<cite>Claude, Aug 26</cite>
</blockquote>

<blockquote class="transcript">
"The benchmark says I was wrong again — it got <strong>worse</strong>."
<cite>Claude, Aug 26</cite>
</blockquote>

Note:
An encode regression on the writer. Each guess came with arithmetic behind it,
which makes a guess read like a finding. Allocation counting showed the branch
allocated less, not more. Removing the "cause" made things slower, twice.

What found it in the end: `-prof gc`, a measurement.

---

<!-- .slide: class="hero" -->

<blockquote class="transcript">
"I've already reshaped this code three times today."
<cite>Claude, Aug 26</cite>
</blockquote>

<blockquote class="transcript">
"Yes we are actively working on this code, so this is expected. We won't stop before we've found a satisfying solution, that's the job."
<cite>Me</cite>
</blockquote>

Note:
The other direction. Here the agent wanted to stop, and stopping would have
been wrong.

Deciding when to stop stays with you, both ways.

---

<!-- .slide: class="hero-image" -->

## Sep 6: the pull request

<img src="images/1198-docs-commit-diff.png" width="1000" height="429" alt="Diff of commit e4dc97d in docs/content/reference/query-controls.md: +6 -4">

Note:
PR #1103 as first opened: one commit, one reference page, six lines added and
four removed. It documented a fix that had already landed.

Writing down which physical types a BigDecimal predicate covers meant checking
it, and checking it turned up three more wrong answers: #1141, #1142, #1144.
The PR was force-pushed nineteen times and merged four days later at 37 files,
+2,334 −159, under a new title: "List the predicate literals each column type
takes, and correct the orders it exposed". That is where epic #1198 began.

---

<!-- .slide: class="hero" -->

<blockquote class="transcript">
"I'd reverted them thinking another session was writing to /workspace — that was wrong, sorry."
<cite>Claude, Sep 9</cite>
</blockquote>

Note:
Parallel sessions share one checkout unless you stop them. One session
relocated another's branch; another reverted my own changes. The multi-session
section in my agent instructions exists because of this, and the first version
of it overshot: sessions started creating worktrees for everything.

---

<!-- .slide: class="hero-image" -->

<img class="post" src="images/x-2026-06-02-agent-slowness.png" alt="Jun 2: impossible to get into a flow state when you have minute-long breaks all the time">

Note:
Two things break flow: the minute-long waits, and what comes after them.

---

<!-- .slide: class="hero" -->

## The debt has a shape

Yet another if/else. And <em>duplication</em>.

<img class="callback" src="images/x-2026-01-17-if-else.png" alt="Jan 17: yet another if/else is not gonna save us, mate">

The agent will re-solve a solved problem locally
rather than find the existing solution. Every time. Cheerfully.

Note:
"Yet another if/else" was funny in January.

This is why "search for existing patterns before writing new code" is written
into my agent instructions at all. It is not a style preference, it is a
countermeasure.

The cleanup is real work, and it is never the interesting work.

---

<!-- .slide: class="hero" -->

<blockquote class="transcript">
"Stop — I was wrong, and the codebase already had a considered position I nearly destroyed."
<cite>Claude, Sep 8</cite>
</blockquote>

Note:
It had started writing validation that already existed, in a class that
implemented every check it listed and more. Caught before it landed.

---

<!-- .slide: class="hero-image" -->

<img class="post" src="images/x-2026-08-09-necessary-not-sufficient.png" alt="Aug 9: reading code is a necessary precondition, not a sufficient one">

Note:
August: reading the code is necessary, not sufficient. Things slipped through
while I was reading every diff.

---

<!-- .slide: class="hero" -->

## Development became probabilistic.

Note:
I no longer know that the code is right. I know that it passes an oracle I
trust, which is a different and weaker statement.

That should feel uncomfortable. Let it sit for a second.

---

<!-- .slide: class="hero-image" -->

<img class="zoom" src="images/08b-tweet-exhausting-counters.png" alt="304.3K views, 4.1K likes, 754 bookmarks">

Note:
304,000 views. 754 bookmarks: people saved a complaint so they could come back
to it.

Show of hands: who in this room has felt this?

Look around the room before you move on. It turns 304,000 strangers into the
people sitting in front of you.

## What I was handed for free

- A complete written specification
- A public conformance corpus of test files
- Three independent implementations to check against
- Correctness = <em>the bytes match, or they don't</em>
- Performance = <em>a number</em>
- Greenfield code. One decision-maker.

Note:
Every one of these is a gift. I did not earn any of them.

Call back to the start: this is exactly why I picked the project. I chose the best
case on purpose.

---

## What you have on Monday

- A twelve-year-old system, no specification
- Tests that assert whatever the code already does
- "Correct" means the client hasn't called
- Four teams and a client architect with a veto

Note:
Get the laugh, then land it flat.

---

<!-- .slide: class="hero-image" -->

<img class="post" src="images/li-2026-08-09-consequences.png" alt="Aug 9: the review debate comes down to vastly different consequences when shipped code fails">

Note:
The stakes, in one sentence: a bug in my note trainer costs me a wrong note.
A bug in Hardwood costs somebody else's data.

Same tool, same model. Different consequences, so a different amount of
checking.

---

<!-- .slide: class="hero-image" -->

<img class="post" src="images/x-2026-04-08-less-moat-in-code.png" alt="Apr 8: There is less and less moat in code">

<span class="aside">Written in April. It took another turn before I worked that way.</span>

Note:
Written in April, long before this talk: the moat is in everything describing a
system's behaviour verifiably. APIs, executable specifications, test suites.
For functional and non-functional requirements. "Much more focused on the
'What' and 'Why' than on the 'How'."

"I wrote this down in April. It took another turn before I actually worked that
way." Knowing it and living by it are different things.

---

## The <em>what</em> is more than the feature

You own the architecture:

- Invariants
- Threading
- Allocation budget

Note:
Otherwise "check the what" sounds like "click through the feature and see if
it works".

The what includes everything the design promises: which invariants hold, who
touches which thread, how much a read allocates. An agent can write code that
does the right thing and breaks all three.

---

<!-- .slide: class="hero" -->

<span class="overline">3 · A new way of working</span>

# Behaviour

<span class="aside">Does it do the right thing?</span>

---

<!-- .slide: class="hero-image" -->

<img class="post" src="images/x-2026-03-30-known-unknowns.png" alt="Mar 30: LLMs are great for solving known unknowns. They are confidently bad for dealing with unknown unknowns.">

Note:
A loop catches known unknowns: things somebody knew to check. The geo bug was an
unknown unknown. Nobody writes a test for a field that doesn't exist.

So loops take most of the checking off your plate, and expertise keeps the
rest.

---

<!-- .slide: class="hero" -->

## The question is never "is the AI good?"

It's "<em>what is my oracle?</em>"

---

## The oracles have bugs too

- parquet-java: `in` with `Set.of` throws; multi-value `notIn` returns every row
- DuckDB: `-0.0 = 0.0`
- A stale jar in `~/.m2` silently shadowing the code under test

Note:
Each of these produced plausible, wrong answers during the audit.

A reference implementation is still an implementation. Disagreement is where
you look; it isn't automatically the other side that's right.

---

<!-- .slide: class="hero" -->

<blockquote class="transcript">
"The oracle takes each row's value from the reader under test: values() reads the column through RowReader.getValue / getRawValue. A decode defect therefore passes on both sides."
<cite>Me, reviewing a test, Sep 15</cite>
</blockquote>

Note:
A real one, caught in review two weeks ago. The expected values came from the
code being tested, so a decoding bug would have produced matching wrong answers
on both sides.

The fix: expected values written independently, by the fixture generator, into
files next to the test data.

---

<!-- .slide: class="hero" -->

## Changes to the oracle get read line by line.

<span class="aside">Exclusion lists. Expected values. Skipped tests.</span>

Note:
The answer to "it edited the test until it agreed". Checking the what is worth
nothing if the thing doing the checking can be changed by the thing being
checked. So that part is core, and core gets every diff.

---

<!-- .slide: class="hero" -->

## The agent doesn't write throwaway scripts to look at a Parquet file.

It runs `hardwood`.

Note:
And here's the self-referential part that people remember:

I built the tool my agent debugs my library with. The CLI exists for users,
but the agent is its heaviest user.

---

## The generalisation

An agent reaching for a one-off script
is telling you your domain is missing an <em>instrument</em>.

Build it. Even if only the agent uses it.

Note:
Their version: the client system nobody can inspect without raising a ticket.
The data pipeline whose state lives in someone's head. You have been tolerating
that because *you* knew the workarounds. The agent doesn't, and it will
cheerfully invent one.

---

## Even when I vibe-code, I ask for instruments

- A strike log: what the detector heard, and why it rejected it
- A test mode that plays known notes
- Recording a session to replay against the detector

Note:
Back to the note trainer. I didn't read how the pitch detection works, but I
asked for all three of these, because "it didn't register my note" tells
neither me nor the agent anything.

Not reading the how only works if you can see the what. These are how you see
it.

---

<!-- .slide: class="hero" -->

<span class="overline">3 · A new way of working</span>

# Performance

<span class="aside">Is it fast enough?</span>

---

<!-- .slide: class="hero-image" -->

<img class="post" src="images/x-2026-01-18-async-profiler-1-claude-0.png" alt="Jan 18: async-profiler: 1, Claude Code: 0">

Note:
A breather. January: the profiler found what the agent's explanation missed.

---

<!-- .slide: class="hero-image" -->

<img class="post" src="images/x-2026-01-22-10x-100x.png" alt="Jan 22: most software is built inefficiently by factor 10x; ready to raise this 100x">

Note:
Why performance gets its own loop: generated code that nobody measures gets slow
quietly.

---

<!-- .slide: class="hero-image" -->

<img class="post" src="images/x-2026-07-16-z300.png" alt="Jul 16: a Minix NEO Z300 for profiling; coding agents work best with a tight feedback loop">

Note:
"Coding agents work best with a tight feedback loop. 'Here's a bug with a
reproducer, go and fix it' works great." The question was whether the same holds
for performance.

"This function is slow, profile it on the Z300 and address the bottleneck" has
become a workflow. And it can measure instead of guess.

---

<!-- .slide: class="hero-image" -->

## Durable knowledge

<span class="aside">Screenshot: the n300-profiling skill → images/03-n300-skill.svg</span>

![](images/03-n300-skill.svg)

Note:
A personal bare-metal box for profiling. The skill tells the agent how to get
on it, how to run the harness, and — mostly — what not to conclude from it.

---

## The method, written down

```markdown
`perfnorm` to classify (instructions/op + IPC + misses
→ compute vs memory vs branch bound)
→ `async` flamegraph for *where*
→ `perfasm` for the exact instructions.

Prove a suspected bottleneck by *changing the supply*
of the resource, not just reading the profile.
```

<span class="aside">From the skill</span>

Note:
The last line is the important one. A profile shows where time goes. It does
not show why. You find out why by changing something and measuring again.

---

## Dead ends

- Skip the per-batch trim → <em>7% slower</em>
- Pre-size the accumulator → <em>slower</em>
- Change the final-batch handoff → <em>noise</em>

<span class="aside">The time-weighted CPU profile kept pointing the wrong way.</span>

Note:
Each fix had a plausible explanation behind it. Measurement refuted all three.

Time-weighted CPU profiles kept over-weighting a few large memory moves, and
sent us after them repeatedly.

---

## The instrument lies too

- A stale CPU pin at 800 MHz: every number ~2× slow, nothing says so
- One memory channel: anything parallel <em>looks</em> bandwidth-bound
- A 7 W part: whatever runs second is penalised by heat
- A reproducer spun on row 0 for 65 minutes, on 8 cores

Note:
Each of these produced numbers that looked like results.

The stale pin is the nastiest: a previous run set the clock to its base
frequency and never restored it. JMH ran fine. Everything was twice as slow.

The spinning reproducer: its loop never advanced to the next row, and the time
cap was only checked between rounds. One round never ended.

---

<!-- .slide: class="hero" -->

## The instrument needs an oracle too.

---

## The rules

- Measure before naming a cause
- An untested theory says "untested" in the same sentence
- A/B/A, never A/B
- "Is any of this making a difference <em>end to end</em>?"
- A hard time cap, checked <em>inside</em> every run

Note:
The fourth is the one I use most. A micro-benchmark regression of 16 ns per
page is real and irrelevant. Bound the end-to-end share before probing further.

All five are written down for the agent now. Each one is an answer to midnight:
the five causes, "this drives me crazy".

---

<!-- .slide: class="hero" -->

<span class="overline">3 · A new way of working</span>

# The loop itself

<span class="aside">How fast do you find out?</span>

Note:
A loop you wait minutes for is a loop you skip. Mine had got slow.

---

<!-- .slide: class="hero" -->

## CI never saw it.

<span class="aside">Every CI checkout is fresh. Mine was full of agent worktrees.</span>

Note:
The way I work with agents was slowing down the agents' own feedback loop, and
only on my machine. Profiling found it; no dashboard would have.

---

<!-- .slide: class="hero" -->

<blockquote class="transcript">
"The dir walk <strong>is</strong> the reason. I said otherwise a message ago and that was wrong — I'd compared my hand-rolled simulation (110ms) against the whole mvnw run (9.3s)."
<cite>Claude, Sep 7</cite>
</blockquote>

Note:
Even here, the first explanation was wrong, and the measurement caught it.

---

<!-- .slide: class="hero" -->

<span class="overline">3 · A new way of working</span>

# Design

<span class="aside">Is it the right thing to build?</span>

---

<!-- .slide: class="hero" -->

## Automate the top.

---

<!-- .slide: class="hero-image" -->

<img class="post" src="images/x-2026-03-27-kids-raise-themselves.png" alt="Mar 27: asking Claude how to avoid a mistake it keeps making, and adding that to CLAUDE.md, feels like asking your kids to raise themselves">

Note:
Step one of the ladder, and why it doesn't hold on its own.

---

## The ladder, with dates

- **Sep 4:** "never add the session id to PRs", in CLAUDE.md
- **Sep 8:** "why are you adding the claude session to commits again, in claude.md we say don't do it"
- **Sep 14:** moved into the settings file

Note:
The co-author trailer. A rule in prose, broken four days later, then moved
into configuration where it can't be broken.

Same path for filler prose in the docs: a complaint in August, a memory entry,
then a check in the PR build.

---

## Promoted

| Rule | Lives as |
|---|---|
| Never use `var` | custom Error Prone check |
| No legacy `/** */` JavaDoc | custom Error Prone check |
| No filler prose in the docs | `docs-prose-check.py` in the PR build |

Note:
Two of these are compiler errors I wrote myself. The third is a Python script
that fails the build on marketing language in documentation.

Each began as a line of prose that I was tired of repeating.

---

<!-- .slide: class="hero" -->

## My design documents go stale.

90 of them. I have not kept them current.

Note:
Confess it. Everybody in the room has this problem and almost nobody says it
on a stage.

It is not a discipline failure. It is a property of the form.

---

## Teach the agent the pyramid

```markdown
## Priority frame: the Code Review Pyramid

Review effort is weighted by how expensive the issue is
to fix after merge.

When writing the findings file, sort by pyramid tier, not
by checklist section. A small API-shape concern outranks a
big style nit. Style items belong in a "Nits" footer.
```

<span class="aside">From the review skill</span>

Note:
The agent reviews too: other people's PRs, and its own output. Without the
pyramid, it sorts findings in the order it found them.

---

## AI reviews are noisy

Cut, don't report:

- Non-findings: "no API change here, looks fine"
- Taste calls with no written rule behind them
- Micro-optimisations on cold paths
- Polishing a test that is already correct

Note:
Early review files were noisy: on one PR I kept only the items that named a
real defect and cut the rest. A short review with five real defects beats a
long one I have to filter.

Same in their day jobs, with or without AI.

---

<!-- .slide: class="hero" -->

## A review that evaporates on merge taught nobody anything.

Note:
The comment thread is gone. The file is still there, and the next review
starts from it.

---

<!-- .slide: class="hero" -->

<span class="overline">3 · A new way of working</span>

# Claims

<span class="aside">Is what it tells you true?</span>

Note:
The top rung, and the one no loop covers: the agent's own account of what it
did.

---

<!-- .slide: class="hero-image" -->

<img class="post" src="images/x-2026-03-28-full-picture.png" alt="Mar 28: What would Claude say if it were a photographer? Now I have the full picture.">

<span class="aside">"I have the full picture": 12 times in my sessions last month.</span>

Note:
The joke from act one, back with a number from my own session transcripts.
"I have the full picture" is a claim too.

---

<!-- .slide: class="hero" -->

<blockquote class="transcript">
"Can you give a prompt for a new session to do the same (as your context is filling up)… I'd like to run the same analysis which started this session again, to make sure we have done the right thing."
<cite>Me, Sep 14</cite>
</blockquote>

Note:
A fresh session is a cheap second opinion: it hasn't seen the first session's
reasoning, so it can't be talked into it.

<!-- .slide: class="hero" -->

## Embeddings

A column of 768 floats per row.

The fast path for it was <em>slower</em> than reading plain float columns.

Note:
Vector embeddings stored as Parquet lists: every row is a list of exactly 768
floats. We had built a dedicated fast path for this shape. It lost to the
ordinary flat-column path, which should be its floor, not its ceiling.

Why?

---

<!-- .slide: class="hero" -->

## The batch was <em>512 MB</em>.

<span class="aside">Sized for ~6 MB. Batch sizing counted 4 bytes per row and ignored the 768.</span>

Note:
Batch sizing looked at the leaf type — a float, 4 bytes — and never at how many
values sit in one row. So a "row-count" batch held 768 times the intended data:
half a gigabyte instead of something that fits in the L2 cache.

The cost wasn't where the copy happened. It was how big the working set was.

The instrument that found it was perfnorm counting instructions and cycles. The
time-weighted profile never pointed at it.

---

## After the fix

| | Before | After |
|---|---|---|
| Time | ~690 ms | 437 ms |
| Cycles | 2.45 B | 1.50 B |
| Gap to flat columns | 1.77× | 1.07× |

<span class="aside">Single core, N300.</span>

Note:
The regular, non-fast path got faster too, because it shares the sizing: about
1.5 s down to 0.8 s.

It's a cache effect, so it should carry over to other machines, but it still
wants a confirmation on a multi-channel server before anyone quotes it. Why
that matters comes up in a few slides.

## Which is why this is a rule

```markdown
Before writing new code, search for existing patterns in the
same class/package that accomplish the same thing. Extract
repeated logic into helper methods rather than duplicating it.
```

Note:
Ordinary advice for humans. Load-bearing infrastructure for agents.

---

## The chain

- Written by AI <!-- .element: class="fragment" -->
- Reviewed by a human <!-- .element: class="fragment" -->
- Tests: green <!-- .element: class="fragment" -->
- Shipped to users <!-- .element: class="fragment" -->
- Caught by <em>one person knowing the format</em> <!-- .element: class="fragment" -->

Note:
Build it one line at a time. The last line is the only one that worked.

<!-- .slide: class="hero-image" -->

<img class="post" src="images/x-2026-09-14-note-trainer.png" alt="Sep 14: a note-reading drill that listens to my piano; AI really lets you build things that you otherwise just wouldn't">

Note:
The joy, as a callback to the note trainer from the start of act three: "AI really
lets you build things that you otherwise just wouldn't."

It's still fun.

## The predicate audit

<span class="subtitle">About <em>72,000</em> predicate cells</span>

× 5 read paths

against parquet-java and DuckDB, in every PR build

<span class="aside">tools/predicate-audit · about three minutes</span>

Note:
Every literal kind a column takes, around stored values, in gaps, past the
range, at the edge cases: NaN payloads, signed zeros, padded decimals. Every
operator, every negated and set form. Read through the row reader and the
column reader, with and without metadata filtering.

After three runs it stopped finding defects in the rule, so now it guards
against regressions.

On your project: this is the golden master, built on purpose.

---

## Durable knowledge

<span class="subtitle">Every line is a mistake that will never happen again</span>

```markdown
- **This box is not for memory-bandwidth-scaling conclusions**
  (single channel makes almost anything parallel *look*
  bandwidth-bound)

- **Poll with a process match that can't match itself.**
  `pgrep -f "mvn … install"` over SSH matches the polling
  shell's own argv, so it reports "still running" forever.
```

Note:
Read the second one aloud. It's funny, it's specific, and it cost me an hour
once. Now it costs nobody anything, ever again.

This is the actual artefact of expertise in an agentic workflow: not the code
you wrote, the traps you wrote down.

Second example, from the hardwood CLI: the agent kept parsing Parquet footers
with a hand-written Python script. "Why did you do that instead of the skill?"
Once. Now it's written down that anything touching Parquet internals goes
through the CLI first.

Same for performance work: the rules from midnight (measure before naming a
cause, A/B/A, a hard time cap) are written down for the agent, not repeated in
every session.

---

<!-- .slide: class="hero" -->

## The base is the <em>what</em>. The top is the <em>how</em>.

Note:
"Review the claim, not the diff" is this pyramid, applied to AI output.

<!-- .slide: class="hero-image" -->

<img class="post" src="images/li-2026-04-21-make-or-buy-crop.png" alt="Apr 21: a request signer passing the AWS test vectors can be pretty much one-shotted">

Note:
This replaces "I would never have written AWS request signing by hand".

Not the kind of code you would historically have wanted to write and own. But
AWS publishes a suite of test vectors next to the spec, and a signer that passes
them was pretty much one-shotted.

This is the first feedback loop in the talk, before it has a name: the test
vectors told the agent right from wrong. Make-or-buy moves wherever such a loop
exists.

"External dependencies now have to earn their place, and the bar has moved."

---

<!-- .slide: class="hero" -->

## Issue #1198

Started as a <em>small docs PR</em>.

Took a <em>week</em>.

Note:
I pulled one thread, and it kept coming. Each next step was cheap, so I took
it. A week later I had rewritten a whole area of the predicate code — well, and
it's better now, which is exactly the problem.

---

<!-- .slide: class="hero" -->

## You can delegate the work.

You can't delegate the <em>signature</em>.

Note:
The manager comparison holds for the workload and breaks down on
accountability.

For this room it's contractual. Your name, and your company's name, goes on
what you deliver to a client. "Claude wrote that part" is not an answer a
client accepts.

An engineering manager doesn't review how each line was written. They own what
gets delivered. That's the shift from the how to the what, and because the
signature stays with you, checking the what is not optional.
