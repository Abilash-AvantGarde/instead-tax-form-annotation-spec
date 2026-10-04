package com.instead.annotation.render;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;

import java.io.IOException;
import java.util.List;

/**
 * The "draw" half of the rendering pipeline (docs/SPEC.md section 6, step 6). Takes a
 * finished {@link RenderPlan} (already verified free of errors) and paints it onto the real
 * PDF. Deliberately knows nothing about JSON, data binding, or formatting - only geometry
 * and text.
 */
public class PdfDrawer {

    public void draw(PDDocument document, List<ResolvedDraw> draws, boolean debug) throws IOException {
        for (ResolvedDraw d : draws) {
            PDPage page = document.getPage(d.box().page() - 1);
            float pageHeight = page.getMediaBox().getHeight();

            // Coordinate-system conversion: annotation boxes are top-left-origin
            // (docs/SPEC.md section 2); PDFBox content streams are bottom-left-origin.
            float pdfX = (float) d.box().x();
            float pdfY = (float) (pageHeight - d.box().y() - d.box().height());

            try (PDPageContentStream cs = new PDPageContentStream(
                    document, page, PDPageContentStream.AppendMode.APPEND, true, true)) {

                if (debug) {
                    cs.setStrokingColor(0.8f, 0.2f, 0.2f);
                    cs.setLineWidth(0.5f);
                    cs.addRect(pdfX, pdfY, (float) d.box().width(), (float) d.box().height());
                    cs.stroke();
                    cs.beginText();
                    cs.setFont(TextMeasurer.FONT, 5);
                    cs.setNonStrokingColor(0.8f, 0.2f, 0.2f);
                    cs.newLineAtOffset(pdfX, pdfY + (float) d.box().height() + 1);
                    cs.showText(d.fieldId());
                    cs.endText();
                }

                if (d.text() == null || d.text().isEmpty()) {
                    continue;
                }

                float fontSize = (float) d.fontSize();
                float textWidth = (float) TextMeasurer.widthOf(d.text(), fontSize);
                float drawX = switch (d.align()) {
                    case "left" -> pdfX;
                    case "center" -> pdfX + ((float) d.box().width() - textWidth) / 2f;
                    default -> pdfX + (float) d.box().width() - textWidth; // right
                };
                // Vertically center the text within the box.
                float drawY = pdfY + ((float) d.box().height() - fontSize) / 2f + fontSize * 0.2f;

                cs.beginText();
                cs.setFont(TextMeasurer.FONT, fontSize);
                cs.setNonStrokingColor(0f, 0f, 0f);
                cs.newLineAtOffset(drawX, drawY);
                cs.showText(d.text());
                cs.endText();
            }
        }
    }
}
