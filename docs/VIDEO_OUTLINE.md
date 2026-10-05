# Video Walkthrough Script (<= 5 minutes)

Spoken-style notes to read off while screen-recording. Timestamps are targets, not hard cuts —
keep moving if you're running long. Everything should be **pre-built** before recording — do not
run `mvn clean package` / `npm install` / `pip install` on camera; have all three jars/scripts
ready to invoke directly. Pre-stage terminal tabs and PDF viewer windows so you aren't scrolling
or waiting on camera.

---

### 0:00-0:15 — Framing: lead with the thing nobody else found (15s)

> "This is my spec for annotating U.S. tax forms. The design decision I want to show first: on
> the real 1040, the Dependents section doesn't repeat *down* the page like a normal table — it
> repeats *sideways*, four slots side by side. My table type declares that explicitly. I'll come
> back to why that matters, but first, the shape of the whole thing."

### 0:15-0:35 — Repo tour, fast (20s)

- Open the file tree for about 15 seconds total. Don't narrate every folder — just these three:
- "`spec/` has the JSON Schema and two real annotations — the full Form 1040, both pages, and a
  complete Form W-2. `docs/SPEC.md` is the written specification. And there are three independent
  renderers — Java, Python, and Node — that all read the same annotation files."

### 0:35-1:20 — Field types and one concrete example (45s)

- Open `docs/SPEC.md`, scroll past the field-type catalogue quickly.
- "Eight field types — text, currency, number, date, a comb-style SSN, checkbox, radio-group,
  and the repeating table type."
- Open `spec/f1040.annotation.json`, scroll to `f1040.line1a`. Show it on screen.
- "Here's line 1a, total wages. `box` is page 1, exact x/y/width/height in PDF points. `type` is
  currency. `value.path` is `$.income.w2[*].box1Wages` — every W-2's box 1 wages — and because
  it's a wildcard, it declares an `aggregate`: `sum`. `format` says round half-up to the nearest
  dollar, parentheses for negatives — the actual IRS convention. One field definition: position,
  type, data binding, aggregation, formatting. Nothing hidden in renderer code."

### 1:20-1:45 — The columns-axis table, with the JSON on screen (25s)

- Open `spec/f1040.annotation.json`, scroll to the `f1040.dependents` table field.
- "Here's the table. `axis: columns`. `pitch: dx 108, dy 0` — each dependent's whole vertical
  strip of sub-fields shifts 108 points to the *right*, not down. Inside the table, `$` means
  'the current dependent' — so `$.firstName` resolves to `dependents[N].firstName`. I almost
  missed this. If my table type only supported rows, I couldn't describe the most common
  repeating structure on the most common U.S. tax form."

### 1:45-1:55 — Data-binding syntax, one sentence (10s)

> "The path syntax is a deliberately restricted JSONPath — member access, numeric index,
> wildcard, nothing else. No filters, no functions. The annotation file stays inert data, not a
> program, so anyone can audit exactly what it depends on just by reading it."

### 1:55-2:05 — Rendering pipeline, one sentence (10s)

> "The pipeline is strictly 'plan then draw': resolve, aggregate, format, and check overflow for
> every field first, collect every problem in one pass, and only paint pixels if nothing came
> back as an error."

### 2:05-3:25 — Live demo: three runs (80s)

**Run 1 — happy path, both forms (~25s)**

```
java -jar renderer/target/annotation-renderer.jar \
  --annotation spec/f1040.annotation.json --data data/sample-taxpayer.json \
  --pdf forms/f1040.pdf --out output/f1040-filled.pdf
```

> "One warning — a dependent missing an optional field, by design — and 82 draw operations across
> both pages of the 1040. Here's the output." (Open the PDF.) "Line 1a: 140,371, both W-2s summed
> and rounded. Line 7a: (1,250), the negative capital loss in parentheses. And here's the
> dependents table — three dependents, side by side in columns, exactly like the real form. And
> the same annotation format covers a completely different form." (Switch to the W-2 output.)
> "Zero code changes to the renderer were needed for this second form — that's the schema being
> genuinely form-agnostic, not just working for one example."

**Run 2 — `--debug` overlay (~15s)**

```
java -jar renderer/target/annotation-renderer.jar \
  --annotation spec/f1040.annotation.json --data data/sample-taxpayer.json \
  --pdf forms/f1040.pdf --out output/f1040-filled-debug.pdf --debug
```

> "Same annotation, debug flag — every declared box outlined in red and labeled with its field
> id. This is how I verify coordinates, and it's how an annotator would check a new form before
> trusting it."

**Run 3 — three renderers, one annotation, same answer (~25s)**

```
python3 renderer-python/render.py --annotation spec/f1040.annotation.json \
  --data data/sample-taxpayer.json --pdf forms/f1040.pdf --out output/f1040-filled-python.pdf
node renderer-node/render.js --annotation spec/f1040.annotation.json \
  --data data/sample-taxpayer.json --pdf forms/f1040.pdf --out output/f1040-filled-node.pdf
```

> "Same annotation file, two completely independent renderers — Python with reportlab, Node with
> pdf-lib, zero shared code with the Java one or each other. Same diagnostic, same 82 draws, and
> the extracted text from all three outputs is byte-for-byte identical. That's the actual proof
> this is a portable spec and not just something that happens to work with my renderer."

**Run 4 — refuses bad input (~15s, if time allows — cut run 2 shorter if needed)**

```
java -jar renderer/target/annotation-renderer.jar \
  --annotation spec/f1040.annotation.json --data data/sample-taxpayer-invalid.json \
  --pdf forms/f1040.pdf --out /tmp/x.pdf
```

> "This data has a filing status that matches no box and a malformed SSN. It reports *both*
> problems in one pass, not just the first one, and refuses to write any output file at all. A
> partially-correct tax form is worse than no tax form."

### 3:25-4:15 — Design decisions and why (50s) — graders specifically want this

Pick these, say them like real tradeoffs:

1. **PDF points, explicit top-left origin, not normalized coordinates.** (~12s) "Every tool I
   used to derive these numbers from the real PDF reports top-left points directly. The renderer
   does one documented y-flip at draw time to match PDF's native bottom-left space."
2. **SHA-256 pinning the exact source PDF.** (~13s) "If the IRS revises this form, my coordinates
   could silently point at the wrong box. The annotation records a hash of the exact PDF it was
   built against, and all three renderers refuse to run if that hash doesn't match."
3. **The annotation never does cross-line tax arithmetic.** (~25s) "One decision I want to
   defend. Line 9 is 'add lines 1z through 8,' line 11a is 'line 9 minus line 10.' I deliberately
   gave the annotation format no way to express that. The moment an annotation can reference
   another field on the same form, it's a tax engine, not a presentation layer — and tax engines
   belong behind a test suite, not in a data file a non-engineer edits. These lines bind straight
   to pre-computed totals in the data. The engine computes; the annotation presents."

### 4:15-4:50 — Future enhancements, cost-aware (35s)

> "Three things I'd build next, and I know roughly what each costs. Nested repeats — each W-2
> having its own repeating list of box-12 codes — is the natural next level of 'deeply nested
> data.' The planner already passes a per-instance scope down the recursion for the dependents
> table, so this is a scope-stacking change, not an architecture change. Conditional fields that
> only print when another value matches — already specified in the docs, just not built.
> And a disagreement between two independently-built, spec-conformant renderers is itself a
> finding: it means the specification's prose is ambiguous, not that either implementation is
> wrong. That's a cheap, continuous way to pressure-test the spec as it grows."

### 4:50-5:00 — Closing (10s)

> "Spec, schema, two real forms, three independent renderers that agree, and a format that
> correctly describes a table that repeats sideways. Thanks for watching."

---

## Production notes

- Have every renderer pre-built (`renderer/target/annotation-renderer.jar` already packaged,
  `renderer-node/node_modules` already installed, `renderer-python` dependencies already
  installed) before recording. Do not build anything live.
- Pre-stage terminal tabs and PDF windows. Have `spec/f1040.annotation.json` already open and
  scrolled to `f1040.line1a` and `f1040.dependents` — don't search for them on camera.
- Zoom the terminal and editor font up for readability at 720p.
- If you build the optional Makefile (see `ideas-validated.md` K7), replace the raw `java -jar`
  / `python3` / `node` invocations above with `make demo`, `make debug`, `make three-way`,
  `make bad` — shorter on-screen commands read better on camera than multi-line invocations.
