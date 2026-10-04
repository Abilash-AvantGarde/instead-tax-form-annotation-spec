package com.instead.annotation;

/**
 * One diagnostic produced while resolving/formatting a field. The renderer collects every
 * diagnostic across the whole document in one pass (docs/SPEC.md section 6, step 4) and
 * refuses to draw anything if any diagnostic is {@link Level#ERROR}.
 */
public record Diagnostic(Level level, String fieldId, String code, String message) {

    public enum Level { WARNING, ERROR }

    public static Diagnostic warning(String fieldId, String code, String message) {
        return new Diagnostic(Level.WARNING, fieldId, code, message);
    }

    public static Diagnostic error(String fieldId, String code, String message) {
        return new Diagnostic(Level.ERROR, fieldId, code, message);
    }

    @Override
    public String toString() {
        return "[" + level + "] " + code + " (" + fieldId + "): " + message;
    }
}
