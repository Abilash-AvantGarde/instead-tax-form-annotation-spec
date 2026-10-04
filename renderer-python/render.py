#!/usr/bin/env python3
"""CLI entry point for the Python reference renderer.

    python3 renderer-python/render.py \
        --annotation spec/f1040.annotation.json \
        --data data/sample-taxpayer.json \
        --pdf forms/f1040.pdf \
        --out output/f1040-filled-python.pdf

Independently implements the same six-step pipeline as renderer/RenderMain.java
(docs/SPEC.md section 6): load, preflight-verify the PDF, plan (resolve/aggregate/
format/overflow) every field, print all diagnostics, refuse to draw on any error-level
diagnostic, else draw. No code or libraries are shared with the Java renderer.
"""
import argparse
import hashlib
import json
import sys

from diagnostic import Diagnostic
from planner import plan_fields
from draw import draw


def sha256_hex(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        h.update(f.read())
    return h.hexdigest()


def preflight(doc, pdf_path):
    """Same checks as RenderMain.java's preflight block (hash/page-count/page-size),
    using pypdf for page geometry instead of PDFBox. This is the one place this renderer
    keeps the full Java-equivalent preflight rather than simplifying it away - see
    docs/SPEC2.md for what else was simplified.
    """
    diagnostics = []
    source_pdf = doc.get("sourcePdf") or {}
    expected_sha = source_pdf.get("sha256")
    if expected_sha:
        actual_sha = sha256_hex(pdf_path)
        if actual_sha.lower() != expected_sha.lower():
            diagnostics.append(Diagnostic.error("sourcePdf", "PDF_HASH_MISMATCH",
                f"Annotation expects SHA-256 {expected_sha} but {pdf_path} hashes to {actual_sha}. "
                "Refusing to render against an unverified form revision."))

    from pypdf import PdfReader
    reader = PdfReader(pdf_path)
    expected_pages = source_pdf.get("pageCount")
    if expected_pages and len(reader.pages) != expected_pages:
        diagnostics.append(Diagnostic.error("sourcePdf", "PDF_PAGE_COUNT_MISMATCH",
            f"Annotation expects {expected_pages} pages but PDF has {len(reader.pages)}."))

    page_size = source_pdf.get("pageSize")
    if page_size and len(reader.pages) > 0:
        box = reader.pages[0].mediabox
        expected_w, expected_h = page_size["width"], page_size["height"]
        if abs(float(box.width) - expected_w) > 1 or abs(float(box.height) - expected_h) > 1:
            diagnostics.append(Diagnostic.error("sourcePdf", "PDF_PAGE_SIZE_MISMATCH",
                f"Annotation expects page size {expected_w}x{expected_h} but page 1 is {box.width}x{box.height}."))
    return diagnostics


def run(args):
    with open(args.annotation) as f:
        doc = json.load(f)
    with open(args.data) as f:
        data = json.load(f)

    print(f"Loaded annotation '{doc['formId']}' ({len(doc['fields'])} top-level fields).")

    preflight_diagnostics = preflight(doc, args.pdf)

    plan = plan_fields(doc["fields"], data)
    all_diagnostics = preflight_diagnostics + plan.diagnostics

    print()
    print(f"=== Diagnostics ({len(all_diagnostics)}) ===")
    if not all_diagnostics:
        print("(none)")
    else:
        for d in all_diagnostics:
            print(d)
    print()

    has_errors = any(d.level == "ERROR" for d in all_diagnostics)
    if has_errors:
        print("Render ABORTED: one or more ERROR-level diagnostics were found. "
              "Fix the annotation or data and re-run. No output file was written.", file=sys.stderr)
        sys.exit(1)

    print(f"Plan produced {len(plan.draws)} draw operations. Drawing...")
    draw(args.pdf, plan.draws, args.out)
    print(f"Wrote {args.out}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--annotation", required=True)
    parser.add_argument("--data", required=True)
    parser.add_argument("--pdf", required=True)
    parser.add_argument("--out", required=True)
    args = parser.parse_args()

    try:
        run(args)
    except SystemExit:
        raise
    except Exception as e:
        print(f"ERROR: {e}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
