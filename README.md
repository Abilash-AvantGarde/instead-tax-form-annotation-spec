# Instead Take-Home: Tax Form Annotation Spec

A data structure for annotating where to print values on a U.S. tax form, how to find those
values in a nested taxpayer data set, and how to format them — plus three independent
reference renderers (Java, Python, Node.js) that prove the spec against the real 2025 IRS
Form 1040 (both pages) and a complete Form W-2.

**Start here:** [`docs/SPEC.md`](docs/SPEC.md) — the full specification (coordinate system,
data-binding grammar, every field type, the rendering pipeline contract, design decisions, and
future enhancements). [`docs/SPEC2.md`](docs/SPEC2.md) — the worked-examples/portability-proof
companion covering Form 1040 page 2, the W-2, and the three-renderer cross-validation.

## Repository Layout

```
spec/
  annotation.schema.json          JSON Schema (2020-12) - the normative spec shape
  f1040.annotation.json           Real annotation of Form 1040, both pages (68 fields,
                                   expanding to 82 drawn marks via the dependents table,
                                   filing-status/digital-assets/designee radio & checkbox
                                   groups, and the page-2 tax/payments/refund/signature lines)
  fw2.annotation.json             Real annotation of the full Form W-2 (boxes a-20, 38 fields)
docs/
  SPEC.md                         Full written specification
  SPEC2.md                        Worked examples: page-2 1040 arithmetic, the W-2, and the
                                   Java/Python/Node cross-renderer value comparison
  VIDEO_OUTLINE.md                Timed script for the <=5 min walkthrough video
data/
  sample-taxpayer.json            Realistic nested taxpayer data (2 W-2s, 3 dependents,
                                   a negative capital-loss figure, a missing optional field,
                                   precomputed page-2 tax/payments/refund figures)
  sample-w2-employee.json         Realistic nested W-2 data for one employee (same fictional
                                   person/employer as the 1040 sample's first W-2)
forms/
  f1040.pdf                       Real, unmodified 2025 IRS Form 1040 (never edited)
  fw2.pdf                         Real, unmodified official IRS Form W-2 (never edited)
renderer/
  pom.xml                         Maven project: Apache PDFBox 3.x + Jackson
  src/main/java/com/instead/annotation/
    model/                        POJOs mirroring the JSON Schema (also a valid alternative
                                   spec representation, per the brief)
    data/PathResolver.java        Hand-rolled restricted-JSONPath resolver
    render/                       Plan stage (resolve/aggregate/format/overflow) and
                                   draw stage (PDFBox content-stream painting), kept separate
    RenderMain.java                CLI entry point
renderer-python/
  render.py                       CLI entry point; path_resolver.py, formatter.py,
                                   planner.py, draw.py implement the same pipeline
                                   independently (reportlab + pypdf, no shared code)
  requirements.txt
renderer-node/
  render.js                       CLI entry point; pathResolver.js, formatter.js,
                                   planner.js, draw.js implement the same pipeline
                                   independently (pdf-lib, no shared code)
  package.json
output/
  f1040-filled.pdf                 Java-rendered 1040 (produced by the command below)
  f1040-filled-python.pdf          Python-rendered 1040
  f1040-filled-node.pdf            Node-rendered 1040
  w2-filled.pdf / w2-filled-python.pdf / w2-filled-node.pdf   Same, for the W-2
```

## Prerequisites

Requires **Java 21+** and **Maven**. Check what you have:

```bash
java -version
mvn -version
```

If either is missing, on macOS:

```bash
brew install openjdk@21 maven
```

On Ubuntu/Debian:

```bash
sudo apt install openjdk-21-jdk maven
```

(Confirmed working with Java 21 / Maven 3.9.15.)

## Clone and Build

```bash
git clone https://github.com/Abilash-AvantGarde/instead-tax-form-annotation-spec.git
cd instead-tax-form-annotation-spec

cd renderer
mvn clean package
cd ..

java -jar renderer/target/annotation-renderer.jar \
  --annotation spec/f1040.annotation.json \
  --data data/sample-taxpayer.json \
  --pdf forms/f1040.pdf \
  --out output/f1040-filled.pdf
```

Add `--debug` to also draw a red outline + field-id label around every box (useful for
coordinate fine-tuning):

```bash
java -jar renderer/target/annotation-renderer.jar \
  --annotation spec/f1040.annotation.json \
  --data data/sample-taxpayer.json \
  --pdf forms/f1040.pdf \
  --out output/f1040-filled-debug.pdf \
  --debug
```

The renderer prints every diagnostic it collects (missing required fields, overflow warnings,
table-instance overflow) before drawing anything, and refuses to write an output file at all if
any diagnostic is error-level — see `docs/SPEC.md` section 6 for the full pipeline contract.

Running the first command above against the bundled sample data produces **one expected warning**
(`MISSING_OPTIONAL_VALUE` for a dependent's omitted `fullTimeStudent` field, deliberately left out
of the sample data to exercise the default-value path) and **82 draw operations across both
pages**, and `output/f1040-filled.pdf` will contain real text you can confirm with:

```bash
pdftotext -layout output/f1040-filled.pdf - | less
```

(or open it in any PDF viewer) — line 1a shows `140,371` (the sum of both sample W-2s' box 1
wages, IRS half-up-rounded), line 7a shows `(1,250)` (parenthesized negative capital loss), the
filing-status and digital-assets boxes show `X` in the correct single box, the dependents table
shows three dependents laid out in side-by-side columns, and page 2 shows the full tax/payments/
refund chain (line 24 total tax `3,713`, line 33 total payments `17,638`, line 34 refund `13,925`).
See `docs/SPEC2.md` for the full worked arithmetic.

To render the W-2 instead:

```bash
java -jar renderer/target/annotation-renderer.jar \
  --annotation spec/fw2.annotation.json \
  --data data/sample-w2-employee.json \
  --pdf forms/fw2.pdf \
  --out output/w2-filled.pdf
```

This produces **0 diagnostics** and **36 draw operations** — and requires zero code changes to
the Java renderer, which is itself the proof that the schema/pipeline are genuinely
form-agnostic (see `docs/SPEC2.md` section 2).

## Python Renderer (independent implementation)

Requires **Python 3** with `reportlab` and `pypdf`:

```bash
pip install -r renderer-python/requirements.txt
```

```bash
python3 renderer-python/render.py \
  --annotation spec/f1040.annotation.json \
  --data data/sample-taxpayer.json \
  --pdf forms/f1040.pdf \
  --out output/f1040-filled-python.pdf

python3 renderer-python/render.py \
  --annotation spec/fw2.annotation.json \
  --data data/sample-w2-employee.json \
  --pdf forms/fw2.pdf \
  --out output/w2-filled-python.pdf
```

Same diagnostic counts as the Java renderer (1 warning / 82 draws for the 1040, 0 / 36 for the
W-2) and the same resolved values — see `docs/SPEC2.md` for the full cross-check.

## Node.js Renderer (independent implementation)

Requires **Node.js 18+**:

```bash
cd renderer-node
npm install
cd ..
```

```bash
node renderer-node/render.js \
  --annotation spec/f1040.annotation.json \
  --data data/sample-taxpayer.json \
  --pdf forms/f1040.pdf \
  --out output/f1040-filled-node.pdf

node renderer-node/render.js \
  --annotation spec/fw2.annotation.json \
  --data data/sample-w2-employee.json \
  --pdf forms/fw2.pdf \
  --out output/w2-filled-node.pdf
```

Same diagnostic counts and resolved values as the Java and Python renderers — again, see
`docs/SPEC2.md` for the full three-way comparison.

## Validating the Annotation Against the Schema

Requires Python 3 with the `jsonschema` package (`pip install jsonschema` if you don't have it):

```bash
python3 -c "
import json, jsonschema
from jsonschema import Draft202012Validator
schema = json.load(open('spec/annotation.schema.json'))
for path in ['spec/f1040.annotation.json', 'spec/fw2.annotation.json']:
    doc = json.load(open(path))
    Draft202012Validator(schema).validate(doc)
    print(path, 'valid')
"
```

## Other Documents

- [`docs/SPEC2.md`](docs/SPEC2.md) — worked examples: Form 1040 page 2 arithmetic, the
  Form W-2 annotation, and the full Java/Python/Node cross-renderer value comparison.
- [`docs/VIDEO_OUTLINE.md`](docs/VIDEO_OUTLINE.md) — timed script for the walkthrough video.
