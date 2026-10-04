package com.instead.annotation.render;

import com.instead.annotation.Diagnostic;

import java.util.ArrayList;
import java.util.List;

/** Output of the "plan" stage: everything that would be drawn, plus every diagnostic found. */
public class RenderPlan {
    public final List<ResolvedDraw> draws = new ArrayList<>();
    public final List<Diagnostic> diagnostics = new ArrayList<>();

    public boolean hasErrors() {
        return diagnostics.stream().anyMatch(d -> d.level() == Diagnostic.Level.ERROR);
    }
}
