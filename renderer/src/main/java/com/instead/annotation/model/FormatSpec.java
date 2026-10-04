package com.instead.annotation.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Formatting rules applied to a resolved raw value before it is drawn. Which fields are
 * meaningful depends on the owning field's {@code type} (see docs/SPEC.md section 4).
 */
public class FormatSpec {

    public enum Kind {
        currency, number, date, ssn_mask, text, none;

        @JsonCreator
        public static Kind fromJson(String value) {
            return Kind.valueOf(value.replace('-', '_'));
        }

        @JsonValue
        public String toJson() {
            return name().replace('_', '-');
        }
    }

    public Kind kind = Kind.none;
    public int decimalPlaces = 0;
    public String roundingMode = "half-up";
    public String negativeStyle = "parens"; // parens | minus
    public boolean thousandsSeparator = true;
    public String datePattern = "MM/DD/YYYY";
    public String maskPattern = "###-##-####";
    public boolean uppercase = false;
    public String trueText = "X";
    public String falseText = "";
}
