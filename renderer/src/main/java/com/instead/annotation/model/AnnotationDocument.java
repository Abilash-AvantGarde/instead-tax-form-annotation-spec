package com.instead.annotation.model;

import java.util.List;

/**
 * Root of a tax-form annotation document (mirrors {@code spec/annotation.schema.json}).
 * See docs/SPEC.md for the complete specification.
 */
public class AnnotationDocument {
    public String formId;
    public String formTitle;
    public int taxYear;
    public String revision;
    public SourcePdf sourcePdf;
    public String coordinateSystem;
    public List<Field> fields;
}
