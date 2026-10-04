package com.instead.annotation.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** The kind of annotated box, per docs/SPEC.md section 4-5. */
public enum FieldType {
    text, currency, number, date, ssn, checkbox, radio_group, table;

    @JsonCreator
    public static FieldType fromJson(String value) {
        return FieldType.valueOf(value.replace('-', '_'));
    }

    @JsonValue
    public String toJson() {
        return name().replace('_', '-');
    }
}
