package com.instead.annotation.render;

import com.instead.annotation.model.FormatSpec;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * Applies a {@link FormatSpec} to a resolved raw value, per docs/SPEC.md section 4.
 */
public final class ValueFormatter {

    private ValueFormatter() {
    }

    public static String format(Object raw, FormatSpec format, com.instead.annotation.model.FieldType type) {
        if (raw == null) {
            return "";
        }
        FormatSpec.Kind kind = format != null ? format.kind : FormatSpec.Kind.none;
        return switch (kind) {
            case currency -> formatCurrency(raw, format);
            case number -> formatNumber(raw, format);
            case date -> formatDate(raw, format);
            case ssn_mask -> formatSsn(raw, format);
            case text -> formatText(raw, format);
            case none -> String.valueOf(raw);
        };
    }

    private static BigDecimal toBigDecimal(Object raw) {
        if (raw instanceof BigDecimal bd) {
            return bd;
        }
        if (raw instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        return new BigDecimal(String.valueOf(raw));
    }

    /** IRS whole-dollar convention: half-up rounding, thousands separators, parens for negatives. */
    private static String formatCurrency(Object raw, FormatSpec format) {
        BigDecimal value = toBigDecimal(raw);
        int places = format != null ? format.decimalPlaces : 0;
        BigDecimal rounded = value.setScale(places, RoundingMode.HALF_UP);
        boolean negative = rounded.signum() < 0;
        BigDecimal magnitude = rounded.abs();

        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.US);
        StringBuilder pattern = new StringBuilder(format == null || format.thousandsSeparator ? "#,##0" : "0");
        if (places > 0) {
            pattern.append('.');
            pattern.append("0".repeat(places));
        }
        DecimalFormat df = new DecimalFormat(pattern.toString(), symbols);
        String digits = df.format(magnitude);

        if (!negative) {
            return digits;
        }
        String style = format != null ? format.negativeStyle : "parens";
        return "minus".equals(style) ? "-" + digits : "(" + digits + ")";
    }

    private static String formatNumber(Object raw, FormatSpec format) {
        return formatCurrency(raw, format); // same numeric styling minus currency-specific semantics
    }

    private static String formatDate(Object raw, FormatSpec format) {
        // raw is expected to already be in (or convertible to) the target pattern; v1 passes
        // ISO "YYYY-MM-DD" input through a simple token substitution.
        String iso = String.valueOf(raw);
        String[] parts = iso.split("-");
        if (parts.length != 3) {
            return iso;
        }
        String pattern = format != null ? format.datePattern : "MM/DD/YYYY";
        return pattern.replace("YYYY", parts[0]).replace("MM", parts[1]).replace("DD", parts[2]);
    }

    private static String formatSsn(Object raw, FormatSpec format) {
        String digits = String.valueOf(raw).replaceAll("[^0-9]", "");
        String mask = format != null ? format.maskPattern : "###-##-####";
        StringBuilder out = new StringBuilder();
        int di = 0;
        for (char c : mask.toCharArray()) {
            if (c == '#') {
                if (di < digits.length()) {
                    out.append(digits.charAt(di++));
                } else {
                    out.append('#');
                }
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static String formatText(Object raw, FormatSpec format) {
        String s = String.valueOf(raw);
        return (format != null && format.uppercase) ? s.toUpperCase(Locale.US) : s;
    }
}
