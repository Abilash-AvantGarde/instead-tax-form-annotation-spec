package com.instead.annotation.model;

import java.util.List;

/**
 * A single annotated box on the form. Mirrors the {@code field} definition in
 * {@code spec/annotation.schema.json}. See docs/SPEC.md for the full field-type catalogue.
 */
public class Field {
    public String id;
    public String label;
    public Box box;
    public FieldType type;
    public boolean required = false;
    public ValueBinding value;
    public FormatSpec format;
    public OverflowPolicy overflow = OverflowPolicy.shrink;
    public double fontSize = 9;
    public double minFontSize = 6;
    public String align = "right";

    /** radio-group only. */
    public List<RadioOption> options;

    /** table only. */
    public String axis; // "rows" | "columns"
    public String itemsPath;
    public Integer maxInstances;
    public Pitch pitch;
    public List<Field> columns;
}
