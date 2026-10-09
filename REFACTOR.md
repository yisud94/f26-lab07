# REFACTOR.md

One section per milestone. Fill each one in as you go, in order.

Milestone 1 is written in two sittings, the pin before the refactor and the
rest after. A pin written afterwards is worth nothing, and a TA will ask.

Keep it short and specific. Point at methods, call sites, and test names.

---

## Milestone 1: Direct a refactor, characterization first

### The pin (write this section before you direct the refactor)

**The pin.** File and test name, plus one sentence naming the method and the
observable result it pins. Not "recurring bookings work". Green against the
shipped code, and you did not edit or delete an existing test method to get
there.

`src/test/java/edu/cmu/cs214/scheduling/workflow/BookingWorkflowCharacterizationTest.java`,
`recurringSubmitSkipsAWeekThatOnlyTouchesAnExistingBooking`. It pins that
`BookingWorkflow.submit` on a `RECURRING` request skips a week whose slot only
touches an existing booking (existing 08:00–09:00 and series 09:00–10:00 on
2026-10-12). The outcome lists that slot in `getSkipped()`, books weeks 1 and 3
with occurrence indices 1 and 3, reports `"series S-1: 2 booked, 1 skipped"`,
and sends 2 occurrence notifications. The suite is 36/36 green on the shipped
code (`cedea51`). It is a new test class, and no existing test was touched.

**Why that one, and does a shipped test already cover it?** Of everything
`BookingWorkflow` does, why is this the behavior worth a test? If something
shipped comes close, say what your pin adds. If nothing does, say how you
checked.

`submit` has the same overlap test written three times. The `REGULAR` and
`BLOCKED` branches use strict `<`, but the `RECURRING` branch uses `<=`, so the
series treats touching slots as a clash. `TimeSlot`'s javadoc says the end is
exclusive, so the series path disagrees with the documented model. Any
reasonable refactor of this class ends with one `overlaps(slot, booking)`
helper, and that helper will use `<`. The result would still compile and look
right, but it would quietly change which weeks a series books. That is the
change most likely to slip past review, so it is the one worth pinning.

The closest shipped test is `regularSubmitAcceptsASlotThatStartsWhenAnotherEnds`,
which pins the boundary on the *regular* path only. No shipped test calls
`getSkipped()` or builds a series against an existing booking. I checked that
by grepping `src/test` for `skipped`/`getSkipped` and for every `recurring(`
call: all four build the series in an empty room. I also flipped the two `<=`
in the `RECURRING` branch to `<` and ran the suite. Only the pin failed (35
shipped green, 1 red), then I reverted.

**What a regeneration would do differently here.** Suppose someone
threw this class away and regenerated it from a one-line description of what a
booking workflow does. Name the decision that would be made a second time, and
say which way it would probably go.

The decision is whether two slots that touch at the boundary conflict. A
regeneration would decide that once, for all booking types, and would almost
certainly choose half-open intervals (touching is fine). That follows
`TimeSlot`'s own javadoc and the regular-path test. The series path's
inclusive comparison would disappear: the new code would book weeks a member
used to see skipped. Whether the `<=` is a bug or a deliberate buffer between a
series and the meeting before it, the shipped code doesn't say.

### The directive

**The refactor and the exact directive.** Name the refactor (one from the menu
in the handout) and paste the directive you gave the agent, including the scope
you set, meaning which files and packages were in bounds, which were not, and
one line on why the boundary sits where it does.

### The result

**The diff and the suite.** How you are showing the diff to the TA (a commit,
`git diff`, a branch), and the totals line (the shipped count plus your pin,
all green).

**What did NOT change: behavior and files.** The observable behavior you
checked is still the same, including anything that surprised you while reading.
Which files outside the scope are untouched, and how you verified that rather
than assumed it. If the agent reached outside the directive, say where and what
you did about it.

**One thing the agent changed that you had to look at twice.** Something you
checked line by line before accepting. If there was nothing, say how carefully
you read the diff.

### The closing explanation

**Refactor or regenerate?** Argue whether regenerating `BookingWorkflow` from scratch
would have been the better call, using the lecture's four questions (test
coverage, code age, spec quality, and reach). Be concrete about this codebase.

No. Refactoring was the right call. All four questions point the same way.

- **Test coverage: thin where a rewrite would drift.** 18 shipped tests, and
  they mostly check *counts* (`hub.getOutbox().size()`, `activeInRoom(...).size()`)
  and `isAccepted()`. No shipped test reads a notification's text, a
  rejection's `getMessage()`, or `getSkipped()`.
  `recurringCancelReleasesTheOccurrence` cancels the *last* occurrence, so it
  can't tell "cancel this one" from "cancel this one and every later one",
  which is what `cancel` actually does. Nothing checks `MAX_SERIES_WEEKS`.
  A regenerated class could pass all 35 shipped tests and still change every
  message, the cancellation rule, and the series boundary. My pin catches
  only the last of those.
- **Code age: no history to learn from.** The repo has one commit (`cedea51`,
  2026-10-06). There is no log or issue explaining why the `RECURRING` branch
  uses `<=`, why a series skips the per-member double-booking check that
  `REGULAR` does, or why cancelling an occurrence takes the later ones with it.
  When intent can't be recovered, the code is the only record of it. A
  refactor keeps that record. A regeneration throws it away.
- **Spec quality: one sentence, and it contradicts the code.** The whole spec
  is the README line ("Members book rooms one slot at a time or as a weekly
  series…") plus thin javadoc. `TimeSlot` says the end is exclusive, but the
  recurring path treats it as inclusive. A regeneration from that spec has to
  re-decide each of these questions (touching slots, member conflicts inside a
  series, how far a cancel reaches), and it would answer them "cleanly", which
  means differently.
- **Reach: everything flows through this class.** The README says every write
  to the store and every notification goes through `BookingWorkflow`.
  `ReportService` reads what it wrote (`isCancelled()`, `getType()`, series
  occurrences), so a changed cancel rule changes occupancy and revenue reports.
  The notification text goes to members' inboxes. `ReportServiceTest` and
  `NotificationHubTest` both build their fixtures through `submit`/`cancel`.
  A behavior change here spreads to three packages and to users.

The refactor showed that the problem was *structural*. The same four-way
`switch` was repeated, and inside each branch the logic was fine. Moving each
branch body verbatim into `RegularHandler`/`RecurringHandler`/`BlockedHandler`
fixed the structure and kept every decision, including the ones nobody can
explain. A regeneration would have fixed the structure too, but it would also
have re-made those decisions, with tests too coarse to notice.

**What would flip your answer.** A condition about the artifact, not a feeling.

A written spec that answers the open decisions: whether touching slots
conflict, whether a series checks member conflicts, and how far a cancel
reaches. Plus tests that assert the observable outputs (outcome messages,
notification recipient/subject/body, `getSkipped()`, which occurrences a
cancel releases), so the suite pins *what* the class does, not just *how
many*. With both in place, a regenerated class could be checked against
something other than the old code, and regenerating would be cheaper than
another refactor.

---

## Milestone 2: The pattern critique

Read `notify/`. It works and the outbox tests pass.

### The patterns present

List every design pattern you can name in that package. For each one, the class
or classes that carry it.

### The problem each one solves

For each pattern you listed, what would have to be true about the requirements
for that pattern to be the right call? One sentence each, not in terms of
"flexibility".

### Which of those problems exist here

For each pattern, does the problem it solves exist in this codebase? Point at
the code that settles it.

### The simpler structure

**Your proposal.** What replaces `notify/`. Sketch the classes and the one
method that matters.

**What stays the same.** The tested behavior it must still produce, named
precisely enough that a reader can check it against the shipped tests.

**What you would keep, if anything.** If you would keep one interface, say
which and why. "None of it" is a fine answer if you can defend it.

### What would bring each layer back

For at least two of the layers you would remove, what requirement, if it
arrived next sprint, would make that layer the right structure? Be specific
about the requirement, not about the pattern.

**Misuse or anti-pattern?** Say which this is and why the distinction matters.

---

## Milestone 3: The missing pattern

Read `pricing/`. Not coded, one sentence.

**The pattern.** Which one fits `PriceCalculator`, and the problem that makes
it fit. Name the problem.

**Would you apply it today?** Yes or no, one line, with the reason.
