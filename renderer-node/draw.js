'use strict';
// The "draw" half of the pipeline (docs/SPEC.md section 6, step 6). Takes a finished
// plan (already verified free of errors) and paints it directly onto the pdf-lib
// PDFDocument's pages. Knows nothing about JSON/data-binding/formatting, mirroring the
// separation PdfDrawer.java keeps.

const { StandardFonts, rgb } = require('pdf-lib');

async function drawPlan(pdfDoc, draws, font) {
  const pages = pdfDoc.getPages();
  for (const d of draws) {
    const page = pages[d.box.page - 1];
    const pageHeight = page.getHeight();

    // Coordinate-system conversion: annotation boxes are top-left-origin
    // (docs/SPEC.md section 2); pdf-lib draws in PDF's native bottom-left-origin space,
    // same flip PdfDrawer.java performs: pdfY = pageHeight - box.y - box.height.
    const pdfX = d.box.x;
    const pdfY = pageHeight - d.box.y - d.box.height;

    if (!d.text) continue;

    const textWidth = font.widthOfTextAtSize(d.text, d.fontSize);
    let drawX;
    if (d.align === 'left') {
      drawX = pdfX;
    } else if (d.align === 'center') {
      drawX = pdfX + (d.box.width - textWidth) / 2;
    } else {
      drawX = pdfX + d.box.width - textWidth;
    }
    // Same baseline-centering nudge as PdfDrawer.java: center by box height, then
    // nudge up ~20% of font size to approximate descender height.
    const drawY = pdfY + (d.box.height - d.fontSize) / 2 + d.fontSize * 0.2;

    page.drawText(d.text, {
      x: drawX,
      y: drawY,
      size: d.fontSize,
      font,
      color: rgb(0, 0, 0),
    });
  }
}

module.exports = { drawPlan, StandardFonts };
