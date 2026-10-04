# Plan — Instead Technical Test (Tax Form Annotation Spec)

## The brief

Define a new data structure for annotating fields/boxes on a U.S. tax form. Deliver it as
JSON, XML, or classes in a popular language. Include written documentation. Someone should be
able to annotate a form per the spec and build an app that prints values into each box, using
the annotation plus their own code. The spec must cover positioning, formatting, and
referencing a value in deeply nested data. Include a ≤5 minute video walkthrough.

**Graded on:** scope accounted for, cleanliness of the deliverable, and quality of the
walkthrough. Decisions and future enhancements should be called out explicitly.

**Submission:** email to jaitee.wazalwar@instead.com, subject
`Instead technical test submission - engineer - <name>`.

## Research phase (done)

Studied five public GitHub submissions to this same brief, plus one YouTube walkthrough, by
actually cloning, building, testing, and running each one rather than only reading code. Full
writeup: `../instead-reference/COMPARISON.md` (sibling folder, kept separate from this
submission on purpose — reference material, not part of what gets shipped).

Patterns that showed up repeatedly in the strongest submissions, adopted here:
- PDF points for position, origin declared explicitly (not left implicit)
- A deliberately restricted JSONPath-style path syntax for data binding — not a full
  expression language, not a custom ad-hoc DSL
- Named format types (currency with IRS whole-dollar rounding and parens for negatives, SSN as
  a masked "comb" field, dates, checkboxes)
- An explicit per-field overflow policy (shrink / truncate / error) rather than one implicit
  global behavior
- A repeating "table" field type for dependents etc., supporting a **column** axis as well as
  rows — the real 1040 dependents section lays out sideways
- Separating "resolve the values and plan what to draw" from "actually draw it" as two steps
- Collecting every validation problem in one pass and failing loudly, rather than crashing on
  the first bad field or silently skipping it

The one gap no reference example covered well: nested repeats (e.g. several W-2s, each with its
own repeating list of box-12 codes). Not attempting to fully solve this here either — it's
called out as a named future enhancement instead, same as it was in the best reference repo.

## What's being built

1. **JSON Schema** (`spec/annotation.schema.json`) — the annotation format itself, the
   actual required deliverable.
2. **Written specification** (`docs/SPEC.md`) — explains the format: coordinate system, path
   syntax, field types and formatting, the table/repeating type, the rendering pipeline,
   design decisions with reasoning, future enhancements.
3. **A real worked example** — Form 1040, page 1, annotated for real
   (`spec/f1040-page1.annotation.json`), against the actual IRS PDF, covering filing status,
   name/SSN, address, the dependents table, and the income lines on that page.
4. **Sample taxpayer data** (`data/sample-taxpayer.json`) — nested JSON with multiple W-2s and
   multiple dependents, so the aggregate and table logic have something real to chew on.
5. **A Java renderer** (`renderer/`, Maven + PDFBox + Jackson) — optional per the brief, but
   included because every strong reference submission had one. Proves the spec is actually
   usable rather than just asserted to work: reads the annotation + data + blank PDF, resolves
   and formats each value, draws it, and writes a filled PDF.
6. **A timed video outline** (`docs/VIDEO_OUTLINE.md`) — a script to read from while recording,
   not just talking points, aimed at the same ≤5 minute structure the strongest examples used.

## Scope decisions

- Page 1 of Form 1040 only, not the full form or every schedule — enough to exercise every
  field type and the repeating/nested-data requirement without spending the whole budget on
  coordinate measurement.
- Java for the renderer (not matching any of the five reference repos' stacks on purpose) —
  the spec itself stays JSON regardless, since the brief's consumer is "their proprietary
  code" and JSON is the language-neutral choice there.
- Coordinates are derived from layout analysis of the real downloaded PDF, not hand-measured
  pixel-by-pixel in a PDF editor — documented as an approximation that would get a visual pass
  in a real authoring workflow. Honesty about this beats pretending otherwise.

## Repo & submission logistics

- New standalone git repo, separate from the `instead-reference/` research folder.
- Commit author: the user's own name/email (existing global git config) — never attributed to
  Claude or any AI assistant.
- Pushed to GitHub as a new **public** repository once content is reviewed.
- Final step after this plan: record the video from `docs/VIDEO_OUTLINE.md`, then email the
  repo + video to jaitee.wazalwar@instead.com with the exact required subject line.
