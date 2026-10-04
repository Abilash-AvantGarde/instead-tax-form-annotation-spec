#!/usr/bin/env node
'use strict';
// CLI entry point for the Node.js reference renderer.
//
//   node renderer-node/render.js \
//     --annotation spec/f1040.annotation.json \
//     --data data/sample-taxpayer.json \
//     --pdf forms/f1040.pdf \
//     --out output/f1040-filled-node.pdf
//
// Independently implements the same six-step pipeline as RenderMain.java (docs/SPEC.md
// section 6): load, preflight-verify the PDF, plan every field, print all diagnostics,
// refuse to draw on any error-level diagnostic, else draw. No code shared with the Java
// or Python renderers.

const fs = require('fs');
const crypto = require('crypto');
const { PDFDocument, StandardFonts } = require('pdf-lib');

const { Diagnostic } = require('./diagnostic');
const { planFields, hasErrors } = require('./planner');
const { drawPlan } = require('./draw');

function parseArgs(argv) {
  const flags = {};
  for (let i = 0; i < argv.length; i++) {
    if (argv[i].startsWith('--')) {
      flags[argv[i].slice(2)] = argv[i + 1];
      i++;
    }
  }
  return flags;
}

function sha256Hex(buffer) {
  return crypto.createHash('sha256').update(buffer).digest('hex');
}

async function preflight(doc, pdfBytes, pdfDoc) {
  const diagnostics = [];
  const sourcePdf = doc.sourcePdf || {};
  if (sourcePdf.sha256) {
    const actual = sha256Hex(pdfBytes);
    if (actual.toLowerCase() !== sourcePdf.sha256.toLowerCase()) {
      diagnostics.push(Diagnostic.error('sourcePdf', 'PDF_HASH_MISMATCH',
        `Annotation expects SHA-256 ${sourcePdf.sha256} but the PDF hashes to ${actual}. Refusing to render against an unverified form revision.`));
    }
  }
  const pageCount = pdfDoc.getPageCount();
  if (sourcePdf.pageCount && pageCount !== sourcePdf.pageCount) {
    diagnostics.push(Diagnostic.error('sourcePdf', 'PDF_PAGE_COUNT_MISMATCH',
      `Annotation expects ${sourcePdf.pageCount} pages but PDF has ${pageCount}.`));
  }
  if (sourcePdf.pageSize && pageCount > 0) {
    const page = pdfDoc.getPage(0);
    const { width, height } = sourcePdf.pageSize;
    if (Math.abs(page.getWidth() - width) > 1 || Math.abs(page.getHeight() - height) > 1) {
      diagnostics.push(Diagnostic.error('sourcePdf', 'PDF_PAGE_SIZE_MISMATCH',
        `Annotation expects page size ${width}x${height} but page 1 is ${page.getWidth()}x${page.getHeight()}.`));
    }
  }
  return diagnostics;
}

async function main() {
  const flags = parseArgs(process.argv.slice(2));
  for (const required of ['annotation', 'data', 'pdf', 'out']) {
    if (!flags[required]) {
      console.error(`Missing required flag: --${required}`);
      process.exit(2);
    }
  }

  const doc = JSON.parse(fs.readFileSync(flags.annotation, 'utf8'));
  const data = JSON.parse(fs.readFileSync(flags.data, 'utf8'));
  console.log(`Loaded annotation '${doc.formId}' (${doc.fields.length} top-level fields).`);

  const pdfBytes = fs.readFileSync(flags.pdf);
  const pdfDoc = await PDFDocument.load(pdfBytes);

  const preflightDiagnostics = await preflight(doc, pdfBytes, pdfDoc);

  const font = await pdfDoc.embedFont(StandardFonts.Helvetica);
  const widthOf = (text, size) => font.widthOfTextAtSize(text, size);

  const plan = planFields(doc.fields, data, widthOf);
  const allDiagnostics = [...preflightDiagnostics, ...plan.diagnostics];

  console.log();
  console.log(`=== Diagnostics (${allDiagnostics.length}) ===`);
  if (!allDiagnostics.length) {
    console.log('(none)');
  } else {
    for (const d of allDiagnostics) console.log(String(d));
  }
  console.log();

  if (preflightDiagnostics.length || hasErrors(plan)) {
    console.error('Render ABORTED: one or more ERROR-level diagnostics were found. '
      + 'Fix the annotation or data and re-run. No output file was written.');
    process.exit(1);
  }

  console.log(`Plan produced ${plan.draws.length} draw operations across ${pdfDoc.getPageCount()} page(s). Drawing...`);
  await drawPlan(pdfDoc, plan.draws, font);

  const outBytes = await pdfDoc.save();
  fs.mkdirSync(require('path').dirname(flags.out), { recursive: true });
  fs.writeFileSync(flags.out, outBytes);
  console.log(`Wrote ${flags.out}`);
}

main().catch((err) => {
  console.error('ERROR:', err.message);
  process.exit(1);
});
