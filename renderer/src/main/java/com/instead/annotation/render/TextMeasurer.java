package com.instead.annotation.render;

import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.font.PDType1Font;

import java.io.IOException;

/**
 * Measures text width using the same font the draw stage uses (Helvetica), so the plan
 * stage's overflow decisions (shrink/truncate/error) match what will actually be painted.
 */
public final class TextMeasurer {

    public static final PDFont FONT = new PDType1Font(Standard14Fonts.FontName.HELVETICA);

    private TextMeasurer() {
    }

    public static double widthOf(String text, double fontSize) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        try {
            return FONT.getStringWidth(text) / 1000.0 * fontSize;
        } catch (IOException e) {
            // Standard-14 fonts never throw in practice; fall back to a rough estimate.
            return text.length() * fontSize * 0.5;
        }
    }
}
