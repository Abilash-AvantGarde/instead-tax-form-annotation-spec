# Tax Form Annotation Specification (v1.0)

A data structure and file format for describing where to print values on a U.S. tax form PDF,
how to find those values in a nested taxpayer data set, and how to format them. The format is
JSON; the normative shape is `spec/annotation.schema.json` (JSON Schema, draft 2020-12). A
mirrored Java class model lives in `renderer/src/main/java/.../model/` for teams that prefer
typed classes over raw JSON.

## 1. Overview

An **annotation document** describes one form (e.g. "Form 1040, 2025, page 1") as a flat list of
**fields**. Each field says:

1. **where** it goes (`box` — page, x, y, width, height),
2. **what kind** of thing it is (`type` — text, currency, number, date, ssn, checkbox,
   radio-group, table),
3. **where its value comes from** (`value.path`, a restricted path into the taxpayer's JSON
   data, plus an optional `aggregate`),
4. **how to format** the resolved value (`format`), and
5. **what to do if it doesn't fit** (`overflow`).

A conformant renderer reads an annotation document plus a taxpayer data document, resolves every
field's value, formats it, and draws it onto the referenced PDF. The renderer is intentionally
*not* part of this spec — anyone's "proprietary code" can implement the pipeline in section 6,
in any language, against any PDF library. This repository ships one reference renderer (Java +
Apache PDFBox) as proof the spec is implementable, not as the spec itself.

## 2. Coordinate System

**Explicit and non-negotiable for v1:** `coordinateSystem` must be the literal string
`"pdf-points-top-left"`.

- **Units:** PDF points, 1/72 inch — the native unit PDF content streams use, so a renderer never
  has to convert.
- **Origin:** the **top-left** corner of the page.
- **Axes:** `x` increases rightward, `y` increases **downward**.
- **Page size:** a standard US Letter page is 612 x 792 points. `sourcePdf.pageSize` records the
  expected size so a renderer can detect a mismatched template.

This is the opposite of native PDF user-space (which is bottom-left-origin, y increasing
upward), but it matches how a human reads a page, how `pdftotext -bbox-layout` and most layout
tools already report coordinates, and how this annotation pack's own coordinates were derived
(see section 8, "coordinates are approximated"). A renderer using PDFBox's content stream API
(which *is* bottom-left-origin) does exactly one conversion at draw time: `pdfY = pageHeight -
annotationY - boxHeight`. See `docs/SPEC.md` section 7 (design decisions) for why top-left was
chosen over native bottom-left.

The field's `box` is the rectangle **within which text must fit** — not necessarily the entire
printable cell on the form. Text is drawn left-aligned, right-aligned, or centered inside this
rectangle per the field's `align`.

## 3. Data-Binding Path Syntax

A **restricted JSONPath-lite** grammar selects values out of the taxpayer data document. It
supports exactly four constructs:

```
path        := "$" segment*
segment     := "." identifier | "[" index "]"
identifier  := [a-zA-Z_][a-zA-Z0-9_]*
index       := "*" | [0-9]+
```

Examples:

| Path | Meaning |
|---|---|
| `$.taxpayer.lastName` | a single scalar, nested two levels deep |
| `$.income.w2[*].box1Wages` | every `box1Wages` across all elements of the `w2` array |
| `$.dependents[0].ssn` | the SSN of the first dependent, by fixed index |
| `$.dependents` | the whole array (used as a table's `itemsPath`, not resolved to a scalar) |

**Deliberately NOT supported**, and why:

- **Filters** (`[?(@.code=='DD')]`) and **slices** (`[1:3]`) — these let the annotation file
  encode conditional *logic*, which belongs in the proprietary application, not in a data file a
  non-engineer annotator edits. A filtered box-12-code selection is instead expressed as a
  `table` field with a fixed `itemsPath` and the renderer iterating all instances (see section 5).
- **Recursive descent** (`..`) — would make it possible to accidentally bind a field to the
  wrong value if the data shape changes anywhere in the document; every binding must name its
  full path explicitly.
- **Arithmetic or function calls inside the path** (e.g. `sum($.a, $.b)`) — aggregation is
  instead a separate, closed-vocabulary field (`aggregate`), never an expression language. This
  keeps the annotation file *inert data*, auditable by inspection, with zero code execution risk.

When a path contains a `[*]` wildcard, it resolves to a **list** of values and the field's
`value.aggregate` is required:

| `aggregate` | Behavior |
|---|---|
| `sum` | Adds all resolved numeric values. Used for e.g. Form 1040 line 1a = sum of every W-2's box 1 wages. |
| `count` | Counts resolved (non-null) values. |
| `first` | Takes the first resolved value, ignoring the rest. |
| `join` | Concatenates resolved values as strings, separated by `joinSeparator` (default `", "`). |

If a path resolves to nothing (missing data) and the binding has a `default`, the default is
used silently. If there is no `default` and the field is `required: true`, this is a **diagnostic
error**, not a silent blank. If the field is not required, a missing value renders as blank with
a diagnostic *warning*.

## 4. Field Types and Formatting

Every field has a `type` and, for most types, a `format` block. `format.kind` selects which
formatting rules apply.

### `text`

Plain string value, optionally uppercased.

```json
{
  "id": "f1040.address.city", "label": "City, town, or post office",
  "box": { "page": 1, "x": 36, "y": 168, "width": 295, "height": 12 },
  "type": "text", "required": true, "align": "left",
  "value": { "path": "$.address.city" },
  "format": { "kind": "text" },
  "overflow": "shrink"
}
```

### `currency`

IRS whole-dollar convention: round **half-up** to the nearest dollar (never truncate, never
banker's-round), insert thousands separators, and render negative amounts in **parentheses**
(not a leading minus) unless `negativeStyle` is overridden to `"minus"`.

```json
{
  "id": "f1040.line7a", "label": "7a  Capital gain or (loss)",
  "box": { "page": 1, "x": 488, "y": 692, "width": 90, "height": 10 },
  "type": "currency", "required": true,
  "value": { "path": "$.income.capitalGainOrLoss" },
  "format": { "kind": "currency", "decimalPlaces": 0, "roundingMode": "half-up", "negativeStyle": "parens" },
  "overflow": "shrink"
}
```
A value of `-1250.00` renders as `(1,250)`.

### `number`

Like `currency` minus the dollar semantics (no forced whole-dollar rounding unless configured);
used for simple counts (e.g. number of qualifying children) where no currency styling applies.

### `date`

Formats an ISO date string per `format.datePattern` (default `MM/DD/YYYY`).

### `ssn` (comb-style)

One digit per visual cell, dash-separated, matching the printed SSN/EIN boxes on IRS forms.
`format.maskPattern` describes the template (`#` = digit placeholder):

```json
{
  "id": "f1040.ssn.you", "label": "Your social security number",
  "box": { "page": 1, "x": 472, "y": 96, "width": 104, "height": 12 },
  "type": "ssn", "required": true, "align": "center",
  "value": { "path": "$.taxpayer.ssn" },
  "format": { "kind": "ssn-mask", "maskPattern": "###-##-####" },
  "overflow": "error"
}
```
Raw data is a plain 9-digit string (`"123456789"`); the renderer inserts the dashes at draw
time. Overflow is `error` by design: a 9-digit field can never need to shrink or truncate — if
it doesn't fit, something upstream is wrong, and that should halt the render, not hide it.

### `checkbox`

Renders `format.trueText` (default `"X"`) when the resolved value is truthy, `format.falseText`
(default empty) when falsy. A Yes/No pair (see `f1040.digitalAssets.yes` /
`f1040.digitalAssets.no` in the sample annotation) is simply two checkbox fields bound to the
same boolean path with inverted `trueText`/`falseText`.

### `radio-group`

A first-class "exactly one of N boxes" construct — IRS filing-status and similar mutually
exclusive groups are *not* N independent booleans the annotator must keep consistent by
convention. One `value.path` resolves to a single value; each `options[]` entry declares its own
`box` and the `matchValue` that selects it. The renderer draws a mark in **exactly one** option
box (the one whose `matchValue` equals the resolved value) and treats more-than-one or
zero-of-N as a diagnostic, since that can only mean a data or annotation bug.

```json
{
  "id": "f1040.filingStatus", "label": "Filing Status - Check only one box",
  "box": { "page": 1, "x": 36, "y": 206, "width": 460, "height": 36 },
  "type": "radio-group",
  "value": { "path": "$.filingStatus" },
  "options": [
    { "id": "single", "box": { "page": 1, "x": 99, "y": 208, "width": 10, "height": 10 }, "matchValue": "single" },
    { "id": "marriedFilingJointly", "box": { "page": 1, "x": 99, "y": 220, "width": 10, "height": 10 }, "matchValue": "marriedFilingJointly" }
  ]
}
```

### `table` (repeating, rows or columns)

See section 5 below — this is the field type the brief specifically calls out as the hard part.

## 5. Repeating Fields: `table`

A `table` field repeats a small set of sub-field templates (`columns`) once per element of an
array in the taxpayer data (`itemsPath`), up to `maxInstances` physical slots printed on the
form. Each sub-field template is positioned **relative to instance 0**; the renderer adds
`pitch` (a fixed `{dx, dy}` offset) × the instance index to every sub-field's box to find where
instance *N* goes.

**Why an `axis` of `rows` *or* `columns`:** most repeating form data (e.g. a list of W-2
withholding entries) prints as additional rows going *down* the page. But Form 1040's own
Dependents section is the opposite: four dependent slots are laid out **side by side**, each a
vertical strip of sub-fields (first name, last name, SSN, relationship, checkboxes) with the next
dependent's slot offset horizontally, not vertically. A spec that assumed "repeating always means
new rows" could not correctly describe the single most common repeating structure on the most
common form. Making the axis an explicit, declared property (not inferred from box geometry)
means a renderer never has to guess, and an annotation reviewer can see the layout intent at a
glance.

```json
{
  "id": "f1040.dependents",
  "label": "Dependents",
  "box": { "page": 1, "x": 145, "y": 310, "width": 432, "height": 110 },
  "type": "table",
  "axis": "columns",
  "itemsPath": "$.dependents",
  "maxInstances": 4,
  "pitch": { "dx": 108, "dy": 0 },
  "columns": [
    {
      "id": "f1040.dep.firstName", "label": "Dependent first name",
      "box": { "page": 1, "x": 145, "y": 312, "width": 90, "height": 10 },
      "type": "text", "align": "left",
      "value": { "path": "$.firstName" },
      "format": { "kind": "text" }, "overflow": "shrink"
    }
  ]
}
```

Each sub-field's `value.path` is evaluated **relative to the current array element**, not the
document root — i.e. inside a `table`, `$` means "the current dependent", so `$.firstName` reads
`dependents[N].firstName`.

**Instance-count handling:** if the data array has fewer elements than `maxInstances`, the
remaining slots are simply left blank (no diagnostic). If it has *more* elements than
`maxInstances` (e.g. a fifth dependent), the renderer reports an `OVERFLOW_TABLE_INSTANCES`
diagnostic naming the field and the excess count, and draws only the first `maxInstances`
in document order — it never silently drops data without telling the operator, and it never
fails the entire render over one table being oversubscribed (see section 9, future enhancement:
continuation pages).

**What this spec does *not* attempt (see section 9):** nesting one `table` inside another (e.g.
each W-2 having its own repeating list of box-12 codes). Every reference implementation surveyed
for this project stopped at one level of repetition; this spec makes the same scope cut
deliberately rather than half-implementing two-level nesting. A single level, implemented
*correctly* end-to-end (iterate `itemsPath`, compute per-instance offsets, draw each sub-field),
is worth more than a declared-but-unexecuted nested feature — which is exactly the gap found in
the weakest reference submission reviewed during this project's research phase.

## 6. Rendering Pipeline (Renderer Contract)

A conformant renderer MUST execute these steps, in this order, once for the whole document
before drawing anything:

1. **Load the annotation JSON**, which SHOULD be validated against `annotation.schema.json`
   before being handed to a renderer — this is a repository-level authoring check (see the
   README's validation command), not something the reference renderer re-checks itself at
   render time. A renderer that *does* embed schema validation directly is free to reject a
   non-conforming document at this step; the reference implementation here relies on the
   external check instead, to keep the renderer's own dependency surface small.
2. **Verify the source PDF** matches `sourcePdf` — SHA-256 of the file bytes, page count, and
   (if present) page size. A mismatch is a hard error: refuse to render rather than draw
   coordinates onto a form revision they weren't authored against.
3. **For every field** (expanding `table` fields into one resolved instance per array element,
   up to `maxInstances`):
   a. **Resolve** `value.path` against the taxpayer data.
   b. **Aggregate** if the path produced multiple values (`sum`/`count`/`first`/`join`).
   c. **Apply default / required check** — missing + has `default` → use it; missing + required
      + no default → record a diagnostic error; missing + optional → blank + warning.
   d. **Format** the resolved value per `format`.
   e. **Fit / overflow** — measure the formatted string against the box width at `fontSize`; if
      it doesn't fit, apply the field's `overflow` policy (`shrink` down to `minFontSize`,
      `truncate` with an ellipsis, or `error`).
4. **Collect every diagnostic from every field in this one pass** — do not stop at the first
   problem. This is the "plan" stage, and it produces a single, complete report.
5. **Refuse to draw anything if any diagnostic is an error-level diagnostic** (missing required
   field, SSN overflow, radio-group with zero or multiple matches, PDF hash mismatch). Print the
   full diagnostic list and exit non-zero. This mirrors how a real annotator would want to fix a
   broken data file in one review pass rather than one error at a time.
6. **Draw** — only once step 5 passes — open the real PDF, and for every resolved, formatted
   field, paint the text (or checkbox/radio mark) at its box coordinates via the renderer's PDF
   library of choice. Save to the output path.

Steps 1-4 ("plan": resolve + format + decide what would be drawn and why) are kept strictly
separate from step 6 ("draw": actually paint pixels). This is what makes the spec
renderer-agnostic in practice, not just in principle — the plan stage can be unit-tested with no
PDF library at all, and the same plan could feed a browser-canvas preview, a different PDF
library, or a non-PDF output entirely.

### 6.1 Conformance Checklist

A renderer can be checked against this list directly, independent of implementation language:

1. A renderer MUST verify `sourcePdf.sha256` against the actual PDF bytes before resolving any
   field, and MUST refuse to render on a mismatch (`PDF_HASH_MISMATCH`).
2. A renderer MUST verify `sourcePdf.pageCount` and, if present, `sourcePdf.pageSize` the same
   way (`PDF_PAGE_COUNT_MISMATCH`, `PDF_PAGE_SIZE_MISMATCH`).
3. A renderer MUST resolve every field's `value.path` against the supplied data document using
   only the grammar in section 3 — no filters, no slices, no recursive descent.
4. A renderer MUST require an `aggregate` whenever a path contains a wildcard, and MUST treat a
   wildcard path with no declared `aggregate` as an error (`MISSING_AGGREGATE`), never as
   "take the first match."
5. A renderer MUST apply `value.default` when a path resolves to nothing, MUST record an error
   for a missing `required` field with no default (`MISSING_REQUIRED_VALUE`), and MUST record a
   warning — not an error — for a missing optional field (`MISSING_OPTIONAL_VALUE`).
6. A renderer MUST apply the field's declared `overflow` policy when formatted text exceeds its
   box width, and MUST NOT silently clip text under any other policy.
7. A `radio-group` field MUST resolve to exactly one matching option; zero matches and multiple
   matches are both errors (`RADIO_GROUP_NO_MATCH`, `RADIO_GROUP_MULTIPLE_MATCHES`), never
   "pick the first."
8. A `table` field MUST expand to at most `maxInstances` drawn instances and MUST record a
   warning, not an error, when the data has more instances than slots
   (`OVERFLOW_TABLE_INSTANCES`) — the rest of the form must still render.
9. A renderer MUST collect every diagnostic across the entire document in one pass before
   drawing anything — it MUST NOT stop at the first problem found.
10. A renderer MUST NOT write any output file if any collected diagnostic is error-level.
11. A renderer SHOULD avoid including a field's raw resolved value in diagnostic messages where
    that value could be a taxpayer identifier (SSN, EIN, account numbers). The reference
    implementation does not yet fully satisfy this for `VALIDATION_FAILED` on SSN fields — see
    section 9 — and diagnostics should not be routed to a shared or persistent log sink without
    that gap being closed first.

A renderer that satisfies all eleven can read any annotation conforming to
`annotation.schema.json` and produce output indistinguishable, field for field, from the
reference implementation — this repository ships three such renderers (Java, Python, Node) as
evidence, see `docs/SPEC2.md`.

### 6.2 Diagnostic Codes

Every diagnostic a conformant renderer may emit, with its severity and what it means. A renderer
MAY add its own codes for conditions this spec doesn't anticipate, but MUST NOT repurpose one of
these codes for a different condition.

| Code | Severity | Meaning | How an annotator fixes it |
|---|---|---|---|
| `PDF_HASH_MISMATCH` | Error | The supplied PDF's SHA-256 doesn't match `sourcePdf.sha256` | Confirm you have the right PDF revision, or re-annotate against this one and update the hash |
| `PDF_PAGE_COUNT_MISMATCH` | Error | The PDF's page count doesn't match `sourcePdf.pageCount` | Same as above — wrong file or stale hash |
| `PDF_PAGE_SIZE_MISMATCH` | Error | Page 1's dimensions don't match `sourcePdf.pageSize` | Same as above |
| `MISSING_BINDING` | Error | A `required` field has no `value` block at all | Add a `value.path` to the field definition |
| `MISSING_AGGREGATE` | Error | A wildcard path (`[*]`) has no `aggregate` declared | Add `"aggregate": "sum"` (or `count`/`first`/`join`) to the binding |
| `MISSING_REQUIRED_VALUE` | Error | A `required` field's path resolved to nothing and there's no `default` | Supply the value in the data document, or add a `default` if blank is acceptable |
| `MISSING_OPTIONAL_VALUE` | Warning | A non-required field's path resolved to nothing | Informational — the field is drawn blank. Supply the value if it should appear |
| `VALIDATION_FAILED` | Error | A resolved value fails a type-specific validation rule (e.g. an SSN's digit count doesn't match its mask) | Fix the underlying data value |
| `RADIO_GROUP_NO_OPTIONS` | Error | A `radio-group` field declares zero `options` | Add at least one option to the field definition |
| `RADIO_GROUP_NO_MATCH` | Error | The resolved value matches none of the group's `option.matchValue`s | Fix the data value, or add a matching option |
| `RADIO_GROUP_MULTIPLE_MATCHES` | Error | The resolved value matches more than one option — ambiguous | Fix the annotation so `matchValue`s are mutually exclusive |
| `TABLE_INCOMPLETE` | Error | A `table` field is missing `itemsPath`, `columns`, `pitch`, or `maxInstances` | Add the missing table configuration |
| `TABLE_AXIS_PITCH_MISMATCH` | Warning | The declared `axis` doesn't match the direction `pitch` actually moves in | Align `axis` with `pitch.dx`/`pitch.dy`, or remove the (then-misleading) `axis` |
| `OVERFLOW_TABLE_INSTANCES` | Warning | The data has more instances than `maxInstances` slots | Informational — excess instances aren't drawn. Increase `maxInstances` if the form has more slots than declared |
| `OVERFLOW_SHRINK_FLOOR` | Warning | Text still doesn't fit the box even at `minFontSize` under a `shrink` policy | Shorten the data, widen the box, or lower `minFontSize` further |
| `OVERFLOW_TRUNCATED` | Warning | Text was cut short with an ellipsis under a `truncate` policy | Informational, unless the truncation loses meaning — widen the box if so |
| `OVERFLOW_ERROR` | Error | Text doesn't fit the box and the field's policy is `error` | The field's data is unexpectedly long for a box designed not to tolerate overflow (e.g. a comb-style SSN) — investigate the data |

## 7. Design Decisions

**Why PDF points with an explicit top-left origin, not normalized 0-1 coordinates.** Normalized
coordinates survive page-size changes gracefully, but every coordinate-extraction tool available
for this project (`pdftotext -bbox-layout`, PDFBox's own glyph positions) reports points directly;
converting to points and back to a fraction at authoring time, then back to points again at
render time, is two unnecessary lossy round-trips for a spec whose target (printed tax forms)
has an effectively fixed page size (US Letter) anyway. Points also let an annotator sanity-check
a coordinate by comparing it directly to what a PDF editor's ruler shows. Top-left origin (rather
than PDF's native bottom-left) was chosen specifically because every layout/extraction tool used
to derive this annotation pack's actual coordinates reports top-left already — matching it
removes a manual y-flip from the authoring workflow, at the cost of one documented, one-line flip
inside the renderer at draw time.

**Why a restricted path syntax, not full JSONPath (RFC 9535) or a general expression language.**
A tax-form annotation file is data that a non-engineer reviewer may open and edit directly. Full
JSONPath's filter expressions (`?(@.code=='DD')`) and functions are a real, if small, expression
language — accepting them means an annotation file can encode conditional logic, which blurs the
line between "declarative data" and "a program," and makes the file harder to statically audit
for what it depends on. The four-construct grammar here (member access, numeric index, wildcard,
nothing else) is expressive enough for every field actually seen on Form 1040's first page and
for the dependents/W-2 repeating structures, and it has the useful property that any path can be
read aloud and matched to a JSON Pointer-like mental model without executing anything.

**Why `table` supports a `columns` axis, not just `rows`.** Addressed in depth in section 5 —
summarized, a spec that only supports downward-repeating rows cannot correctly describe the
single most prominent repeating structure on the most common U.S. tax form (the Dependents
section, which repeats sideways). Making the axis an explicit declared field rather than
inferring it from box geometry means the annotation itself documents its own layout intent.

**Why the renderer is a separate artifact from the spec, not bundled as "the" implementation.**
The brief explicitly asks for a structure someone else can build "their proprietary code"
against. Treating the Java renderer as a reference implementation (one possible conformant
consumer) rather than as part of the spec keeps the schema and the Java POJOs the actual
contract, and keeps the rendering pipeline (section 6) expressed as an ordered list of
responsibilities rather than as a specific PDFBox API call sequence.

**Why overflow policy is per-field, not one global default.** A 9-digit SSN field can never
usefully "shrink" its way to correctness — if it doesn't fit, the mask pattern or box is wrong,
and that should halt the render (`error`). A free-text address field, by contrast, should almost
always `shrink` first and only ever `truncate` as a last resort. Collapsing these into one global
policy would force every field to tolerate the worst case of any other field's needs.

**Why SHA-256-pinning the source PDF is mandatory, not optional.** The single most valuable
pattern found across every reference implementation surveyed for this project: when the IRS
revises a form (even a whitespace-only PDF regeneration), coordinates silently point at the wrong
box with no runtime signal unless the renderer checks. Making `sourcePdf.sha256` a required field
(not a nice-to-have) turns that failure mode from "a tax form prints bank routing numbers over
someone's name" into a loud, immediate refusal to render.

**Why aggregation is a closed enum (`sum`/`count`/`first`/`join`), not an arbitrary function
call.** Keeps the same inertness guarantee as the path-syntax decision above: a reviewer can see
every possible aggregation behavior the format can express just by reading this document, with
no risk of an annotation file embedding executable logic.

## 8. Coordinates Were Approximated From Layout Analysis

The coordinates in `spec/f1040.annotation.json` were derived from `pdftotext
-bbox-layout` word-level bounding boxes against the real downloaded `forms/f1040.pdf` (2025
revision), cross-referenced by hand against `pdftotext -layout` to confirm each label's actual
line position, not guessed or fabricated. Every box's x/y was anchored to the nearest real text
label on the page (e.g. the `1a` line-number glyph, the `Yes`/`No` digital-assets labels, the
`(1) First name` dependents-table header) and the "answer area" to the right/below it was
estimated from the visible rule lines and column boundaries in the extracted text layout. This
is a sound and defensible first pass, consistent with how a real annotator would start — but it
is explicitly **not** pixel-verified against a rendered image of the page, and a production
annotation pack would still need one visual fine-tuning pass in a PDF editor (or the renderer's
own `--debug` overlay, see section 9) before being trusted for real tax filings.

## 9. Future Enhancements

- **Nested repeats (repeat-of-repeats).** Each W-2 having its own repeating list of box-12 codes
  is the natural next level of "deeply nested data" this spec does not yet attempt. The `table`
  type's `columns` could itself contain a `table`, with the inner `itemsPath` resolved relative
  to the outer instance, if a real use case demands it — deliberately deferred here in favor of
  getting one level fully correct (see section 5).
- **Conditional "print only if" fields.** A declarative (non-executable) `when` clause
  (`{"path": "...", "equals": ...}`) so a field only draws when some other value matches,
  without introducing a general expression language — e.g. only draw the HOH/QSS qualifying
  child name field when `filingStatus` is one of those two statuses.
- **Continuation pages for table overflow.** Right now, more data instances than
  `maxInstances` produce a loud diagnostic but the excess is simply not drawn. A real
  implementation should support an optional `continuationPage` pointing at a second annotation
  fragment (e.g. Schedule 8812 or a dependents continuation sheet) that absorbs the overflow.
- **Template-PDF hash pinning across *revisions*, not just exact match.** Today a hash mismatch
  is a hard stop. A `knownRevisions: [{sha256, coordinateDelta}]` list could let a renderer
  auto-correct for a confirmed-compatible IRS revision rather than only ever refuse or proceed
  blindly.
- **A visual click-to-annotate authoring tool.** Render the PDF to canvas, let a reviewer click
  and drag boxes, and emit this JSON directly — would remove the "approximated from layout
  analysis, needs visual fine-tuning" caveat in section 8 entirely.
- **SSN / routing-number / EIN checksum validation.** The `ssn` type currently only validates
  shape (9 digits) via its mask pattern; real-world data entry errors (transposed digits) are
  not caught. A lightweight checksum or known-invalid-range check (e.g. SSNs starting `000`,
  `666`, or `900-999` are never valid) would catch a class of bugs before they reach print.
- **Hybrid AcroForm + drawn-text output.** When the source PDF has native fillable fields, a
  renderer could fill those directly (more accessible, more robust to minor layout drift) and
  fall back to coordinate-based drawing only for static-rendered forms — today this spec commits
  to coordinate-based drawing exclusively.
- **Redacting taxpayer values out of diagnostic messages.** `VALIDATION_FAILED` on an `ssn` field
  currently interpolates the raw resolved value into its message (see section 6.1, item 11) so
  the diagnostic is actionable — but on a tax platform, diagnostics should assume they may reach
  a shared log sink. A `sensitive: true` flag on a field, honored by truncating or masking the
  value in any diagnostic text, would close this without losing the "what's wrong" signal.
- **Treating cross-renderer disagreement as a spec-quality signal.** This repository ships three
  independently-built renderers that currently agree on every observable output (`docs/SPEC2.md`).
  That agreement is normally read as evidence the spec is portable — but it can also be read the
  other way: if two renderers that both genuinely conform to this document ever produce different
  output from the same annotation, that is a defect in the specification's prose, not in either
  implementation. A future revision could formalize this as part of the acceptance process for
  spec changes, run as a differential check across implementations.
- **Mechanical glyph-collision detection.** Two coordinate-placement defects were found during
  development by a human visually inspecting rendered output (see `docs/SPEC2.md` section 1 and
  `docs/TEST_FIXTURES.md` section 4) — drawn text overlapping the form's own printed labels. This
  is mechanically detectable: render a sentinel value into each declared box, re-extract text
  positions, and assert no drawn glyph's bounding box intersects the blank form's own glyphs. A
  tool like this would have caught both incidents automatically rather than by eye.
- **Cross-document fact composition.** The same taxpayer fact — wages, withholding, an SSN —
  appears on a W-2, a federal 1040, and potentially a state return, each with its own annotation
  file and its own path into its own data document today. Nothing in this spec relates those
  three bindings as "the same underlying fact." Formats that solve this at scale (e.g. XBRL's
  split between a taxonomy of concepts, a presentation layer, and per-filing instance documents)
  point at the likely shape of an answer; this spec doesn't attempt it.
- **Accessibility.** Tagged-PDF / screen-reader support can't be solved at the annotation layer
  alone: the blank IRS source PDFs this spec annotates are themselves untagged, so tagging only
  the drawn overlay would produce a document where the values are accessible and their labels
  are not — arguably worse than the current untagged state. Solving this properly means starting
  from a tagged source PDF, which is outside this spec's control.

## 10. Non-Goals (v1)

To keep scope honest: this spec does not attempt e-file XML generation, scanned-form box
auto-detection, non-Latin script layout, or multi-year form migration tooling. These are named
here, not silently absent.

**Cross-line tax arithmetic is explicitly out of scope.** A real Form 1040 has lines whose value
is a formula over other lines on the same form (e.g. line 9 "total income" = the sum of lines
1z, 2b, 3b, 4b, 5b, 6b, 7a, and 8; line 11a "AGI" = line 9 minus line 10). This spec's `aggregate`
mechanism (section 4) only reaches *into the taxpayer data set* — summing an array like every
W-2's box 1 wages — it has no way to reference *another annotated field on the same form*, and
deliberately does not grow one: that would mean embedding tax-computation logic in a presentation
layer, which is exactly the responsibility this spec is trying to keep separate (see "the engine
computes, the annotation presents" framing in section 7). The expectation is that derived figures
like total income and AGI already exist as computed values in the taxpayer data set — produced by
the calling application's own tax engine — and the annotation simply binds to them directly, the
same as any other field. The worked example in `spec/f1040.annotation.json` follows this:
`data/sample-taxpayer.json` carries pre-computed `income.totalIncome` and `agi` values that line 9
and line 11a bind to directly, rather than the annotation attempting to re-derive them.
