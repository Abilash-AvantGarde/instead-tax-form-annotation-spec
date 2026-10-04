package com.instead.annotation.model;

/** Pins an annotation document to the exact PDF it was authored against. */
public class SourcePdf {
    public String fileName;
    public String sha256;
    public int pageCount;
    public PageSize pageSize;

    public static class PageSize {
        public double width;
        public double height;
    }
}
