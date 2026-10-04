package com.instead.annotation.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Binds a field to a value in the nested taxpayer data set using the restricted
 * path syntax defined in docs/SPEC.md section 3.
 */
public class ValueBinding {

    /** Restricted JSONPath-lite expression, e.g. {@code $.income.w2[*].box1Wages}. */
    public String path;

    /** Required when {@link #path} contains a wildcard segment ({@code [*]}). */
    public Aggregate aggregate;

    /** Separator used when {@link #aggregate} is {@link Aggregate#join}. */
    @JsonProperty("joinSeparator")
    public String joinSeparator = ", ";

    /** Value substituted when the path resolves to nothing. */
    public Object defaultValue;

    @JsonProperty("default")
    public void setDefault(Object value) {
        this.defaultValue = value;
    }

    public enum Aggregate {
        sum, count, first, join
    }
}
