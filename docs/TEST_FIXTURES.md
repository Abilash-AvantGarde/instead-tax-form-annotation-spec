# Adversarial Test Fixtures

Independent validation pass. Four new data fixtures were added alongside the two clean
samples (`data/sample-taxpayer.json`, `data/sample-w2-employee.json`, both left unmodified)
to test the annotation pipeline on messy, broken, and minimal data rather than only on the
happy path.

Every fixture was run against **all three renderers** — Java (PDFBox), Python (reportlab +
pypdf), Node (pdf-lib) — using the commands in `README.md`. For each fixture the three
outputs were compared by `pdftotext -layout` and diffed against each other.

Method note: "text layer identical" below means `diff` of the three renderers'
`pdftotext -layout` output returned zero differences — not that the numbers merely
looked similar.

---

## 1. `data/sample-taxpayer-overflow.json`

**What it tests.** The dependents table declares `maxInstances: 4`. This fixture supplies
**5 dependents**. Table-instance overflow must degrade gracefully: warn, draw what fits,
and still produce a usable return — not error out and not silently drop the extra row.

**Expected.** One `OVERFLOW_TABLE_INSTANCES` warning naming the dropped count; 4 dependents
drawn; output file still written; exit 0.

**Actual.**

| Renderer | Exit | Output written | Diagnostics | Draws |
|---|---|---|---|---|
| Java   | 0 | yes | 2 warnings | 89 | 
| Python | 0 | yes | 2 warnings | 89 |
| Node   | 0 | yes | 2 warnings | 89 |

All three emitted the identical diagnostic:

```
[WARNING] OVERFLOW_TABLE_INSTANCES (f1040.dependents): Data has 5 instances but only 4 slots
are printed on the form; 1 instance(s) were not drawn.
```

Text layer verified: dependents 1–4 (Maya, Owen, Priya, Rafael) are drawn in four distinct
non-overlapping columns; the 5th (Serena) appears nowhere in the output. The second warning
is the pre-existing expected `MISSING_OPTIONAL_VALUE` carried over from the base sample.

**Result: PASS on all three renderers.** Text layers byte-identical across renderers.

---

## 2. `data/sample-taxpayer-invalid.json`

**What it tests.** Two separate invalidities at once:
- `filingStatus` = `"marriedFilingJointlyButSeparate"`, which matches **no** `matchValue`
  in the filing-status radio group.
- `taxpayer.ssn` = `"12345"` — five digits where nine are required.

**Expected.** Error-level diagnostic, no output file written, non-zero exit — for both
problems.

**Actual.**

| Renderer | Exit | Output written | Diagnostics |
|---|---|---|---|
| Java   | 1 | **no** | 2 ERRORs + 1 warning |
| Python | 1 | **no** | 2 ERRORs + 1 warning |
| Node   | 1 | **no** | 2 ERRORs + 1 warning |

All three emit both errors in the same pass (the pipeline's "collect everything, then
decide" discipline — see `docs/SPEC.md` section 6) and all three refuse to write output.

**Result: PASS on both halves.**

With the fix below applied, the bad filing status and the malformed SSN are now **both**
reported in the same pass, on all three renderers:

```
[ERROR] RADIO_GROUP_NO_MATCH (f1040.filingStatus): No option.matchValue equals resolved
value 'marriedFilingJointlyButSeparate'.
[ERROR] VALIDATION_FAILED (f1040.ssn.you): Value '12345' does not have the digit count
required by mask '###-##-####'.
```

### Fixed: malformed SSNs are now validated

Originally, all three renderers accepted a 5-digit SSN with no diagnostic and drew it as
`123-45-####` (the `ssn-mask` formatter pads a short digit string with the literal mask
character for every missing digit; a too-long SSN was silently truncated the same way). All
three renderers now validate the resolved digit count against the mask's placeholder count
*before* formatting, and emit `VALIDATION_FAILED` as an error-level diagnostic if they don't
match — a non-matching SSN field is never drawn. A blank/absent optional SSN (e.g. an empty
spouse SSN on a single-filer return) is exempted from this check, since that's the normal
`default: ""` path, not a data-quality problem — see `sample-taxpayer-minimal.json` below,
which specifically exercises that exemption.

Fixed identically, independently, in all three renderers (`FieldPlanner.java`,
`planner.py`, `planner.js`) — same diagnostic code, same message shape.

---

## 3. `data/sample-taxpayer-minimal.json`

**What it tests.** The sparse end of the input space: a single filer named Dana Reyes, **no
spouse object at all**, **zero dependents**, one W-2, and every optional income, adjustment,
credit, and payment line either zero or entirely absent from the JSON. This exercises the
`default` path on a large number of fields simultaneously and checks that absent objects
(`spouse`, `thirdPartyDesignee` sub-fields) do not cause resolution failures or leak stale
values.

**Expected.** No errors. Optional-value defaults applied cleanly. Output written. Empty
dependents table draws nothing rather than drawing blank rows or crashing on an empty array.

**Actual.**

| Renderer | Exit | Output written | Diagnostics | Draws |
|---|---|---|---|---|
| Java   | 0 | yes | **0** | 63 |
| Python | 0 | yes | **0** | 63 |
| Node   | 0 | yes | **0** | 63 |

Zero diagnostics on all three — the `default` declarations in the annotation cover every
absent field, so nothing is reported as missing. Draw count drops from 82 to 63, consistent
with the absent spouse (3 fields), the empty dependents table (expanding to 0 instead of 19
marks), and absent optional lines.

Text layer verified: name/SSN/address correct, line 1a/1z/9/11a all `42,000`, line 12e
`15,750` (the single-filer standard deduction, correctly distinct from the joint `31,500`
in the base sample), line 24 `2,950`, no spouse row drawn, no dependent rows drawn, no
stale values anywhere.

**Result: PASS on all three renderers.** Text layers byte-identical across renderers.

---

## 4. `data/sample-w2-edge.json`

**What it tests.** Several W-2-specific edge cases stacked into one record:
- **Zero-wage, zero-withholding** employee (boxes 1–6 all `0.00`) — tests that a legitimate
  zero renders as `0.00` and is not suppressed as falsy.
- A **180-character employer name/address** against a 288pt box — tests the `shrink`
  overflow policy and its minimum-font-size floor.
- **All four box-12 slots** populated with real codes (`AA`, `BB`, `EE`, `FF`).
- **All three box-13 checkboxes** true simultaneously.
- A populated employee **suffix** (`JR`).
- Non-zero **local** figures (boxes 18/19/20) where the clean sample has zeros.

**Expected.** One overflow warning for the long employer name, drawn at the floor size
rather than clipped or dropped. Everything else renders. No errors.

**Actual.**

| Renderer | Exit | Output written | Diagnostics | Draws |
|---|---|---|---|---|
| Java   | 0 | yes | 1 warning | 38 |
| Python | 0 | yes | 1 warning | 38 |
| Node   | 0 | yes | 1 warning | 38 |

All three emitted the identical `OVERFLOW_SHRINK_FLOOR` warning naming the field, the full
offending string, the box width (288) and the floor size (6), and all three then drew the
text at the floor size rather than silently clipping it — the documented policy.

Draw count rises 36 → 38 because box 12c/12d codes and the suffix now have non-empty values.
Zero currency values render as `0.00`, confirming zero is not being treated as absent.

**Result: PASS on all three renderers.** Text layers byte-identical across renderers.

Note: an earlier validation pass found the box-12 code cells, the employee SSN, and the
employee address overlapping printed form text at their original coordinates. All three
were re-measured against the real PDF layout and corrected; this fixture's four populated
box-12 codes (`AA`, `BB`, `EE`, `FF`) now render clear of the vertical "Code" label.

---

## Cross-renderer consistency summary

The strongest single result of this pass: across **all four adversarial fixtures plus the
two clean samples**, the three independently-written renderers agreed on every observable —
exit code, diagnostic count, diagnostic severity, diagnostic text, draw count, refusal-to-
render decision, and the full extracted text layer (byte-identical `pdftotext -layout`
output in every case).

| Fixture | Java | Python | Node | Agreement |
|---|---|---|---|---|
| `sample-taxpayer.json` (clean) | 1 warn / 82 draws | 1 warn / 82 | 1 warn / 82 | identical |
| `sample-w2-employee.json` (clean) | 0 / 36 | 0 / 36 | 0 / 36 | identical |
| `sample-taxpayer-overflow.json` | 2 warn / 89 | 2 warn / 89 | 2 warn / 89 | identical |
| `sample-taxpayer-invalid.json` | 2 err, abort | 2 err, abort | 2 err, abort | identical |
| `sample-taxpayer-minimal.json` | 0 / 63 | 0 / 63 | 0 / 63 | identical |
| `sample-w2-edge.json` | 1 warn / 38 | 1 warn / 38 | 1 warn / 38 | identical |

Agreement on messy and refused input is a materially stronger portability claim than
agreement on clean input alone, because the error and overflow paths are where
independent implementations most easily diverge.

The one gap the fixtures originally surfaced — silent acceptance of a malformed SSN,
reproduced identically by all three renderers — has since been fixed identically in all
three (see fixture 2 above): SSN digit count is now validated against the mask before
formatting, with a blank/absent optional SSN correctly exempted (verified by fixture 3).

---

## Tamper / preflight check

Separately from the data fixtures, the SHA-256 preflight was tested by appending a comment
byte to a copy of `forms/f1040.pdf` (the real form was not modified):

| Renderer | Behavior |
|---|---|
| Java   | `[ERROR] PDF_HASH_MISMATCH`, aborted, no output, exit 1 |
| Python | `[ERROR] PDF_HASH_MISMATCH`, aborted, no output, exit 1 |
| Node   | `[ERROR] PDF_HASH_MISMATCH`, aborted, no output, exit 1 |

All three independently compute the digest and all three refuse to render against an
unverified form revision. **PASS.**
