# Video Walkthrough Script (<= 5 minutes)

Spoken-style notes to read off while screen-recording. Timestamps are targets, not hard cuts —
keep moving if you're running long.

---

### 0:00-0:15 — Framing (15s)

> "This is my spec for annotating U.S. tax forms. It's a JSON format that says where on a PDF a
> value goes, how to pull that value out of a nested taxpayer data set, and how to format it —
> and I built a working Java renderer to prove it against a real IRS Form 1040."

### 0:15-0:55 — Repo tour (40s)

- Open the file tree. Point at each top-level folder as you say it:
- "`spec/` has the JSON Schema that defines the format, plus a real annotation of Form 1040 page
  one — about 27 declared fields that expand to 44 actual drawn marks once you count the
  dependents table and the checkbox groups."
- "`docs/SPEC.md` is the actual written specification — coordinate system, the path syntax,
  every field type, and a design-decisions section."
- "`data/` has a realistic nested taxpayer profile — two W-2s, three dependents, a negative
  capital loss to test negative-number formatting."
- "`forms/` has the real, unmodified 2025 1040 PDF downloaded from the IRS — I never touch this
  file, the renderer only reads it."
- "`renderer/` is a Maven project — Apache PDFBox plus Jackson — that actually implements the
  spec end to end."

### 0:55-2:05 — Field types and formatting, with one concrete example (70s)

- Open `docs/SPEC.md`, scroll to the field-type catalogue.
- "There are eight field types: text, currency, number, date, SSN — which is a one-digit-per-cell
  comb style — checkbox, radio-group, and table, which is the repeating one."
- Open `spec/f1040.annotation.json`, scroll to the `f1040.line1a` field. Show it on screen.
- "Here's line 1a, total wages from W-2s. The `box` says page 1, these exact x/y/width/height
  coordinates in PDF points. The `type` is currency. The `value.path` is
  `$.income.w2[*].box1Wages` — that wildcard means 'every W-2's box 1 wages' — and because it's
  a wildcard, I have to declare an `aggregate`, which here is `sum`. The `format` says round
  half-up to the nearest whole dollar and wrap negatives in parentheses, which is the actual IRS
  convention."
- "That one field definition is the whole story: position, type, data binding, aggregation, and
  formatting, all declared, nothing hidden in renderer code."

### 2:05-2:15 — Data-binding syntax, one sentence (10s)

> "The path syntax is a deliberately restricted JSONPath — just member access, numeric index,
> and wildcard — no filters, no functions, so the annotation file stays inert data, not a
> program, and anyone can audit exactly what it depends on."

### 2:15-2:20 — Rendering pipeline, one sentence (5s)

> "The pipeline is strictly 'plan then draw' — resolve, aggregate, format, and check overflow
> for every field first, collect every problem in one pass, and only paint pixels if nothing
> came back as an error."

### 2:20-3:20 — Live demo (60s)

- Switch to terminal.
- Run: `mvn clean package` inside `renderer/` (or show it already built, and just run the jar to
  save time).
- Run:
  ```
  java -jar renderer/target/annotation-renderer.jar \
    --annotation spec/f1040.annotation.json \
    --data data/sample-taxpayer.json \
    --pdf forms/f1040.pdf \
    --out output/f1040-filled.pdf
  ```
- "It printed one diagnostic — a warning that one dependent is missing an optional full-time-student
  flag — and still rendered, because that's a warning, not an error. Forty-four draw operations,
  zero errors."
- Open `output/f1040-filled.pdf` side by side with `forms/f1040.pdf` (or side by side with the
  `f1040.line1a` JSON still on screen).
- "Line 1a shows 140,371 — that's both sample W-2s summed and rounded half-up. Line 7a shows
  (1,250) in parentheses — that's the negative capital loss from the sample data. And the
  dependents table — three dependents, laid out side by side in columns, which is how the real
  1040 actually prints them."

### 3:20-4:20 — Design decisions and why (60s) — graders specifically want this part

Pick 2-3, say them like real tradeoffs, not bullet points:

1. **PDF points with an explicit top-left origin, not normalized coordinates.** "Every
   coordinate tool I used to actually derive these numbers from the real PDF reports top-left
   points directly. Normalizing to 0-1 would mean converting twice for no benefit, since tax
   forms are fixed-size pages anyway. The renderer does one documented y-flip at draw time to
   match PDFBox's native bottom-left space."
2. **A restricted path syntax instead of full JSONPath.** "This file might get opened and edited
   by someone who isn't an engineer. A full expression language — filters, functions — turns a
   data file into a small program. I kept it to four constructs: enough to express everything on
   this form, simple enough to read like a sentence."
3. **Tables support a columns axis, not just rows.** "I almost missed this — the Dependents
   section on the actual 1040 doesn't repeat downward, it repeats sideways, four slots side by
   side. If my table type only supported rows, I couldn't correctly describe the most common
   repeating structure on the most common tax form."
4. (If time) **SHA-256 pinning the exact source PDF.** "If the IRS revises this form even
   slightly, my coordinates could silently point at the wrong box. The annotation records a hash
   of the exact PDF it was built against, and the renderer refuses to run if that doesn't match."

### 4:20-4:50 — Future enhancements (30s)

> "Things I'd build next: nested repeats, so each W-2 could have its own repeating list of box-12
> codes — that's the real 'deeply nested data' case I deliberately scoped out of v1. Conditional
> fields that only print when some other value matches. Continuation pages when a table has more
> data than it has slots. And a visual click-to-annotate tool, since right now these coordinates
> came from text-layout analysis and would still need one visual fine-tuning pass."

### 4:50-5:00 — Closing (10s)

> "That's the spec, the schema, and a working renderer proving it against a real 1040. Thanks
> for watching."
