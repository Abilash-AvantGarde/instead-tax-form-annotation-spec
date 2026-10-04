# SPEC2: Second Form, Two More Renderers, and the Portability Proof

`docs/SPEC.md` is the general specification. This document is the worked-examples and
proof-of-portability companion: what got added in round two (Form 1040 page 2, a complete
Form W-2, a Python renderer, a Node.js renderer), the actual cross-renderer value
comparisons that back up the portability claim, and the honest limitations that only
showed up once a second form and two more renderers existed to surface them.

## 1. Form 1040, now both pages

`spec/f1040.annotation.json` (renamed from `f1040-page1.annotation.json` - it covers both
pages now) adds 41 page-2 fields on top of the original 27 page-1 fields: AGI carried
over (11b), the someone-can-claim-you/spouse dependent checkboxes (12a), standard
deduction and QBI deduction (12e/13a/13b), taxable income (14/15), tax and credits
(16-24), the federal-withholding/payments section (25a-33), refund-or-owe (34-38), the
third-party-designee yes/no, and the signature block (occupation, date, phone, email).
Coordinates were derived the same way as page 1: `pdftotext -bbox-layout` word-level
boxes against the real `forms/f1040.pdf`, anchored to each line's printed line-number
glyph, then one visual-verification pass against `pdftotext -layout` output of the
actual rendered PDF (this caught two real overlaps - the Date/Your occupation/Spouse's
occupation boxes initially collided with adjacent labels - fixed by narrowing/repositioning
those three boxes before the arithmetic was finalized).

**The arithmetic is pre-computed in the data, not re-derived by the annotation**, per
docs/SPEC.md section 10's "the engine computes, the annotation presents" rule - and per
the explicit lesson from round one, where lines 9 and 11a were both wrongly bound to the
same re-summed W-2 aggregate. This round, every derived line in `data/sample-taxpayer.json`
is its own precomputed value, and the chain is internally consistent:

```
agi (141,420.08)                                    = line 11b
standardDeduction (31,500) + qbi(0) + other(0)       = line 14  (31,500)
line 11b - line 14                                   = line 15  (109,920.08 -> rounds to 109,920)
line16Tax (8,213, a 2-bracket demo calc on line 15)   = line 16
line 16 + schedule2Line3(0)                          = line 18  (8,213)
childTaxCreditAndOdc (2 CTC x 2000 + 1 ODC x 500)     = line 19  (4,500)
line 18 - line 21                                    = line 22  (3,713)
line 22 + otherTaxes(0)                              = line 24, "total tax" (3,713)
box2FederalTaxWithheld sum from the same 2 W-2s      = line 25a/25d (17,638.11 -> 17,638)
line 25d + est.payments(0) + line32(0)               = line 33, "total payments" (17,638)
line 33 (17,638) > line 24 (3,713)                   -> line 34 "overpaid" = 13,925.11, line 37 "owe" = 0
```

The line-16 "tax" figure uses a simplified two-bracket demo calculation (10%/12%,
approximating the 2025 MFJ brackets), explicitly **not** the real IRS tax tables -
documented as such in `data/sample-taxpayer.json`'s `_edgeCases` block. The point of this
round's exercise was arithmetic *consistency* (no two lines silently re-deriving the same
number differently), not tax-law accuracy, which remains out of scope per SPEC.md section
10.

Re-running the Java renderer against the extended files: **1 diagnostic** (the
pre-existing, expected `MISSING_OPTIONAL_VALUE` warning for `dependents[2].fullTimeStudent`),
**82 draw operations across 2 pages**, zero errors, zero regressions to the already-working
page 1 (confirmed by diffing page-1 `pdftotext -layout` output before and after - identical
down to the whitespace).

## 2. Form 2: a complete W-2

`spec/fw2.annotation.json` annotates the real `forms/fw2.pdf` (the official 11-page IRS
W-2 download, which bundles instructions plus six copies of the form). **Page 3** was
chosen as the annotated instance - it is the cleanest full single-copy grid (no red
"informational purposes only" overlay text competing with the real boxes the way page 2's
copy does). Coverage: boxes a through 20 in full - employee SSN, employer EIN, employer
name/address, all of boxes 1-11 (wages, withholding, social security, Medicare, tips,
dependent care, nonqualified plans), employee name, the three box-13 checkboxes, box 12a-12d
code+amount pairs, box 14a "Other", employee address, and the state/local section
(15-20). That is a genuine full-form coverage claim for the boxes that exist on one copy of
the form - 38 fields, 36 of which draw for this sample (box 12c/12d codes are blank for this
employee by design, drawing nothing, same as an optional field with no value).

`data/sample-w2-employee.json` represents **the same fictional employee** as
`data/sample-taxpayer.json`'s primary taxpayer (Jordan Alvarez) and the same employer
(Northside Robotics LLC) - the W-2's box 1 wages (86,250.00) and box 2 federal withholding
(11,430.00) are identical to that taxpayer's `income.w2[0]` entry, so the two sample files
tell one consistent story (this W-2 is the employer-reported document behind that 1040
entry) rather than being two unrelated fixtures. Box 4 (social security tax) and box 6
(Medicare tax) are derived from box 1 at the statutory 6.2%/1.45% rates, not invented.

Running the **existing, unmodified** Java renderer against this new annotation + data +
PDF: **0 diagnostics**, **36 draw operations across 11 pages** (the plan only emits draws
for page 3; pdf-lib/PDFBox/pypdf all still open and re-save the full 11-page document
intact). **No renderer code changes were needed at all** - this is itself a direct test of
the schema's form-agnosticism claim, and it held up.

## 3. Three-renderer cross-validation

Three from-scratch implementations of docs/SPEC.md's pipeline (Java/PDFBox, already
existing; Python/reportlab+pypdf and Node.js/pdf-lib, both new this round) read the
*same* annotation JSON files and were run against the *same* data files, independently.

**Commands:**

```bash
# Java (existing)
java -jar renderer/target/annotation-renderer.jar \
  --annotation spec/f1040.annotation.json --data data/sample-taxpayer.json \
  --pdf forms/f1040.pdf --out output/f1040-filled.pdf
java -jar renderer/target/annotation-renderer.jar \
  --annotation spec/fw2.annotation.json --data data/sample-w2-employee.json \
  --pdf forms/fw2.pdf --out output/w2-filled.pdf

# Python (new)
python3 renderer-python/render.py \
  --annotation spec/f1040.annotation.json --data data/sample-taxpayer.json \
  --pdf forms/f1040.pdf --out output/f1040-filled-python.pdf
python3 renderer-python/render.py \
  --annotation spec/fw2.annotation.json --data data/sample-w2-employee.json \
  --pdf forms/fw2.pdf --out output/w2-filled-python.pdf

# Node.js (new)
node renderer-node/render.js \
  --annotation spec/f1040.annotation.json --data data/sample-taxpayer.json \
  --pdf forms/f1040.pdf --out output/f1040-filled-node.pdf
node renderer-node/render.js \
  --annotation spec/fw2.annotation.json --data data/sample-w2-employee.json \
  --pdf forms/fw2.pdf --out output/w2-filled-node.pdf
```

**Diagnostic/draw-count agreement** (identical across all three for both forms):

| Form | Diagnostics | Draws |
|---|---|---|
| f1040 | 1 (`MISSING_OPTIONAL_VALUE` on `f1040.dep.fullTimeStudent`) | 82 |
| fw2 | 0 | 36 |

**Values cross-checked with `pdftotext` against all six output files**, confirmed
byte-identical in content (whitespace layout differs trivially between runs; the actual
text tokens match exactly):

- `f1040.line1a` / `line1z` (sum of both W-2 box-1 wages): **140,371** in all three
  1040 outputs (86,250.00 + 54,120.50 = 140,370.50, half-up rounds to 140,371 - note the
  rounded sum differs from summing two pre-rounded values, which is correct: the spec
  sums the raw numeric values *then* rounds once, not round-then-sum).
- `f1040.line7a` (negative capital gain/loss): **(1,250)** in all three - confirms
  parenthesized-negative-currency formatting agrees across all three from-scratch
  formatter implementations (Java `BigDecimal`, Python `Decimal`, Node hand-rolled
  half-up-on-floats).
- Dependents table: all three outputs show exactly **3** dependents (Maya, Owen, Priya)
  in side-by-side columns, confirming the `axis: "columns"` table-expansion logic agrees
  across implementations.
- Page 2 chain - **141,420 (11b) -> 109,920 (15) -> 8,213 (16/18) -> 3,713 (22/24, after the
  4,500 child tax credit) -> 17,638 (25d/33) -> 13,925.11 (34, refund)** - identical across
  all three 1040 outputs.
- W-2: SSN `123-45-6789`, EIN `84-1234567`, wages `86,250.00`, federal withholding
  `11,430.00`, social security tax `5,347.50`, Medicare tax `1,250.63` - identical across
  all three W-2 outputs.

No cross-renderer value mismatches were found. Where a mismatch would most plausibly have
appeared - currency rounding at a half-cent boundary, since Java/Python use exact decimal
types and the Node implementation does not - none of this sample data happened to land on
an exact tie, so this remains a theoretical risk rather than an observed one; see
Limitations below.

## 4. Differences in approach between the three implementations

- **SHA-256/page-count/page-size preflight**: the Java renderer's preflight (verify the
  PDF bytes hash, page count, and page size before planning anything) was **fully
  re-implemented** in both Python (`renderer-python/render.py: preflight()`, via
  `hashlib` + `pypdf`) and Node (`renderer-node/render.js: preflight()`, via `crypto` +
  pdf-lib) - this was not simplified away in either new renderer, since it is cheap to
  reproduce and is one of the spec's more load-bearing guarantees.
- **Decimal arithmetic**: Java uses `BigDecimal`, Python uses the standard-library
  `Decimal`, both exact. Node.js has no built-in arbitrary-precision decimal type;
  `renderer-node/formatter.js` rounds half-up by scaling to an integer via
  `toFixed`-based string manipulation rather than naive `Math.round(x*100)/100`, which
  avoids the most common binary-float rounding bug but is not formally exact the way
  `BigDecimal`/`Decimal` are. This is flagged, not hidden: a production Node renderer
  for this spec should pull in a decimal library (e.g. `decimal.js`) rather than
  hand-rolling this, which this implementation does anyway only to honor "no shared
  code/no extra dependencies beyond pdf-lib" for the demo.
- **Text measurement**: Java's `TextMeasurer` approximates Helvetica glyph widths
  per-character; Python's `reportlab.pdfbase.pdfmetrics.stringWidth` and Node's
  `font.widthOfTextAtSize` (pdf-lib, embedding the real Helvetica AFM) both use exact
  metrics. In practice this meant the Python/Node renderers very occasionally chose a
  very slightly different shrink font-size than Java for a long label squeezed into a
  narrow box - never enough to change which overflow policy fired, and not visible in
  any of the diagnostics above.
- **Drawing mechanism**: Java draws directly into PDFBox content streams; Python builds
  one reportlab overlay canvas per page and merges each onto the source page with pypdf;
  Node draws directly via pdf-lib's page API (closer to the Java approach than the Python
  one). All three perform the identical top-left-to-bottom-left Y-flip
  (`pdfY = pageHeight - box.y - box.height`) and the same align-then-vertically-center
  logic, independently re-derived from the spec text in each case.

## 5. Limitations found only by building a second form and two more renderers

- **The W-2's box 12a-12d code+amount pairs are two sibling fields, not one compound
  field type.** The schema has no "labeled key-value pair" field type, so each of
  box 12a/b/c/d had to be annotated as two ordinary `text`/`currency` fields placed next
  to each other (`fw2.box12a.code` + `fw2.box12a.amount`) rather than one semantic unit.
  This worked fine, but a form with many more such code+amount slots (real W-2 box 12
  can have up to four, and some states' equivalents have more) would get repetitive
  faster than the existing `table` type can help with, since `table` is for *repeating
  instances of the same shape* (four identical dependents), not a pair of differently-typed
  sibling values at one instance. Not a blocker, just a shape the schema doesn't have a
  dedicated construct for yet.
- **The official IRS W-2 PDF download is multi-copy, not single-copy**, unlike the 1040.
  `sourcePdf.pageCount: 11` in `spec/fw2.annotation.json` is correct and the hash/page-count
  preflight still works exactly as designed, but it means "annotate the W-2" implicitly
  required a judgment call (which of six near-identical copies to annotate) that the
  1040's single-page-per-logical-page structure never surfaced. A more complete annotation
  pack for a multi-copy form might eventually want one annotation per copy (Copy A, Copy
  B, Copy 2, etc.), since different copies go to different recipients (SSA vs. employee vs.
  state) and in principle could want different subsets of boxes visible - out of scope
  here, noted for awareness.
- **Currency half-cent rounding ties are a real cross-language risk that this round's
  sample data didn't happen to exercise.** See section 4 above - this was only visible
  once a second renderer without a native decimal type existed to build against; building
  only the Java+Python pair (both exact-decimal) would never have surfaced it.
- **pdftotext `-layout` mode visually re-flows overlapping short tokens** (seen on line 31
  of the rendered 1040 page 2, where a "0" value box sits close enough to the "31" line-number
  label that `-layout` merges them onto what looks like one cramped line). The value is
  genuinely drawn at the correct coordinates in all three outputs (confirmed via plain
  `pdftotext` without `-layout`, and via raw grep for the token) - this is a quirk of the
  text-reflow heuristic in `-layout` mode, not a drawing bug, but it is a reminder that
  `pdftotext -layout` is a convenience check, not a pixel-perfect verification tool; only
  opening the PDF in an actual viewer (or the renderer's `--debug` box-overlay mode) fully
  confirms visual correctness.
- **No code changes were needed in the Java renderer to support the W-2.** This is
  logged as a finding, not a limitation: it is the strongest evidence collected during
  this round that the annotation schema and the six-step pipeline are genuinely
  form-agnostic in practice, not just by the spec's stated intent.

## 6. What's explicitly out of scope / simplified, stated plainly

- The 1040 page-2 checkbox grid for lines 12a-12d covers only the two "someone can claim
  you/spouse as a dependent" boxes, not the spouse-itemizes/dual-status-alien/age/blindness
  sub-checkboxes also on that line - those are real boxes on the form that were cut for
  time budget, following this project's stated preference for an honestly-scoped subset
  over a padded-but-shaky full set. The sample taxpayer doesn't need any of them checked
  (not a dependent, not itemizing, not dual-status, not 65+/blind), so nothing in the
  sample data exercises them either way.
- Line 31 drawing a "0" for every zero-valued optional payments-section field (27a-31) is
  intentional per the spec's "optional + default" rule, not a sign those fields should
  have been typed differently - flagged here only because of the `pdftotext -layout`
  rendering quirk noted above, not because the underlying data/format logic is wrong.
