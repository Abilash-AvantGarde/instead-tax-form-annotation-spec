package com.instead.annotation.render;

import com.instead.annotation.model.Box;

/**
 * One concrete "paint this text at this box" instruction produced by the plan stage
 * (docs/SPEC.md section 6, steps 1-4), consumed by the draw stage (step 6).
 */
public record ResolvedDraw(String fieldId, Box box, String text, double fontSize, String align) {
}
