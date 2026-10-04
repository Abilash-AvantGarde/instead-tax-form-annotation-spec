# Instead Take-Home: Tax Form Annotation Spec

A data structure for annotating where to print values on a U.S. tax form, how to find those
values in a nested taxpayer data set, and how to format them — plus a working Java reference
renderer that proves the spec against the real 2025 IRS Form 1040.

**Start here:** [`docs/SPEC.md`](docs/SPEC.md) — the full specification (coordinate system,
data-binding grammar, every field type, the rendering pipeline contract, design decisions, and
future enhancements).

## Repository Layout

```
spec/
  annotation.schema.json          JSON Schema (2020-12) - the normative spec shape
  f1040-page1.annotation.json     Real annotation of Form 1040 page 1 (~27 fields,
                                   expanding to 44 drawn marks via the dependents table
                                   and filing-status/digital-assets radio & checkbox groups)
docs/
  SPEC.md                         Full written specification
  VIDEO_OUTLINE.md                Timed script for the <=5 min walkthrough video
data/
  sample-taxpayer.json            Realistic nested taxpayer data (2 W-2s, 3 dependents,
                                   a negative capital-loss figure, a missing optional field)
forms/
  f1040.pdf                       Real, unmodified 2025 IRS Form 1040 (never edited)
renderer/
  pom.xml                         Maven project: Apache PDFBox 3.x + Jackson
  src/main/java/com/instead/annotation/
    model/                        POJOs mirroring the JSON Schema (also a valid alternative
                                   spec representation, per the brief)
    data/PathResolver.java        Hand-rolled restricted-JSONPath resolver
    render/                       Plan stage (resolve/aggregate/format/overflow) and
                                   draw stage (PDFBox content-stream painting), kept separate
    RenderMain.java                CLI entry point
output/
  f1040-filled.pdf                Generated output (produced by the command below; not committed
                                   pre-built so you can regenerate and inspect it fresh)
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
  --annotation spec/f1040-page1.annotation.json \
  --data data/sample-taxpayer.json \
  --pdf forms/f1040.pdf \
  --out output/f1040-filled.pdf
```

Add `--debug` to also draw a red outline + field-id label around every box (useful for
coordinate fine-tuning):

```bash
java -jar renderer/target/annotation-renderer.jar \
  --annotation spec/f1040-page1.annotation.json \
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
of the sample data to exercise the default-value path) and **44 draw operations**, and
`output/f1040-filled.pdf` will contain real text you can confirm with:

```bash
pdftotext -layout output/f1040-filled.pdf - | less
```

(or open it in any PDF viewer) — line 1a shows `140,371` (the sum of both sample W-2s' box 1
wages, IRS half-up-rounded), line 7a shows `(1,250)` (parenthesized negative capital loss), the
filing-status and digital-assets boxes show `X` in the correct single box, and the dependents
table shows three dependents laid out in side-by-side columns.

## Validating the Annotation Against the Schema

Requires Python 3 with the `jsonschema` package (`pip install jsonschema` if you don't have it):

```bash
python3 -c "
import json, jsonschema
from jsonschema import Draft202012Validator
schema = json.load(open('spec/annotation.schema.json'))
doc = json.load(open('spec/f1040-page1.annotation.json'))
Draft202012Validator(schema).validate(doc)
print('valid')
"
```

## Other Documents

- [`docs/VIDEO_OUTLINE.md`](docs/VIDEO_OUTLINE.md) — timed script for the walkthrough video.
