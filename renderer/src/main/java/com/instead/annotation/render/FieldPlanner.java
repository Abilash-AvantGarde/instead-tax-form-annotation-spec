package com.instead.annotation.render;

import com.fasterxml.jackson.databind.JsonNode;
import com.instead.annotation.Diagnostic;
import com.instead.annotation.data.PathResolver;
import com.instead.annotation.model.Box;
import com.instead.annotation.model.Field;
import com.instead.annotation.model.FieldType;
import com.instead.annotation.model.RadioOption;
import com.instead.annotation.model.ValueBinding;

import java.math.BigDecimal;
import java.util.List;

/**
 * The "plan" half of the rendering pipeline (docs/SPEC.md section 6, steps 3-4): resolves
 * each field's value binding against the taxpayer data, aggregates, applies defaults/required
 * checks, formats, and figures out overflow handling — without touching a PDF at all. This is
 * what keeps the pipeline renderer-agnostic and independently testable.
 */
public class FieldPlanner {

    private final RenderPlan plan = new RenderPlan();

    public RenderPlan plan(List<Field> fields, JsonNode data) {
        for (Field field : fields) {
            planField(field, data, 0, 0);
        }
        return plan;
    }

    private void planField(Field field, JsonNode scope, double dx, double dy) {
        switch (field.type) {
            case table -> planTable(field, scope);
            case radio_group -> planRadioGroup(field, scope);
            case checkbox -> planCheckbox(field, scope, dx, dy);
            default -> planScalar(field, scope, dx, dy);
        }
    }

    private void planScalar(Field field, JsonNode scope, double dx, double dy) {
        Resolved resolved = resolve(field, scope);
        if (resolved == null) {
            return; // diagnostic already recorded; nothing to draw
        }
        String text = ValueFormatter.format(resolved.value, field.format, field.type);
        Box box = offset(field.box, dx, dy);
        double fontSize = fitFontSize(field, text, box);
        if (fontSize < 0) {
            return; // overflow=error already recorded
        }
        if (field.overflow == com.instead.annotation.model.OverflowPolicy.truncate) {
            text = truncateToFit(text, field, box);
        }
        plan.draws.add(new ResolvedDraw(field.id, box, text, fontSize, field.align));
    }

    private void planCheckbox(Field field, JsonNode scope, double dx, double dy) {
        Resolved resolved = resolve(field, scope);
        boolean truthy = resolved != null && isTruthy(resolved.value);
        String mark = truthy
                ? (field.format != null ? field.format.trueText : "X")
                : (field.format != null ? field.format.falseText : "");
        if (mark == null || mark.isEmpty()) {
            return;
        }
        Box box = offset(field.box, dx, dy);
        plan.draws.add(new ResolvedDraw(field.id, box, mark, field.fontSize, "center"));
    }

    private void planRadioGroup(Field field, JsonNode scope) {
        if (field.options == null || field.options.isEmpty()) {
            plan.diagnostics.add(Diagnostic.error(field.id, "RADIO_GROUP_NO_OPTIONS",
                    "radio-group field has no options declared."));
            return;
        }
        JsonNode node = field.value != null ? PathResolver.resolveOne(scope, field.value.path) : null;
        if (node == null || node.isNull()) {
            if (field.required) {
                plan.diagnostics.add(Diagnostic.error(field.id, "MISSING_REQUIRED_VALUE",
                        "Required radio-group value not found at path " + (field.value != null ? field.value.path : "<none>")));
            }
            return;
        }
        Object resolvedValue = asJavaValue(node);
        long matches = field.options.stream().filter(o -> valuesEqual(o.matchValue, resolvedValue)).count();
        if (matches == 0) {
            plan.diagnostics.add(Diagnostic.error(field.id, "RADIO_GROUP_NO_MATCH",
                    "No option.matchValue equals resolved value '" + resolvedValue + "'."));
            return;
        }
        if (matches > 1) {
            plan.diagnostics.add(Diagnostic.error(field.id, "RADIO_GROUP_MULTIPLE_MATCHES",
                    "More than one option.matchValue equals resolved value '" + resolvedValue + "' - ambiguous."));
            return;
        }
        for (RadioOption option : field.options) {
            if (valuesEqual(option.matchValue, resolvedValue)) {
                plan.draws.add(new ResolvedDraw(field.id + "." + option.id, option.box,
                        option.mark != null ? option.mark : "X", field.fontSize, "center"));
            }
        }
    }

    private void planTable(Field field, JsonNode scope) {
        if (field.itemsPath == null || field.columns == null || field.pitch == null || field.maxInstances == null) {
            plan.diagnostics.add(Diagnostic.error(field.id, "TABLE_INCOMPLETE",
                    "table field is missing itemsPath/columns/pitch/maxInstances."));
            return;
        }
        List<JsonNode> items = PathResolver.resolveAll(scope, field.itemsPath);
        // itemsPath without a wildcard still resolves to a single array node; unwrap it.
        if (items.size() == 1 && items.get(0) != null && items.get(0).isArray()) {
            JsonNode arrayNode = items.get(0);
            items = new java.util.ArrayList<>();
            arrayNode.forEach(items::add);
        }

        int drawCount = Math.min(items.size(), field.maxInstances);
        for (int i = 0; i < drawCount; i++) {
            JsonNode instanceScope = items.get(i);
            double dx = field.pitch.dx * i;
            double dy = field.pitch.dy * i;
            for (Field column : field.columns) {
                planField(column, instanceScope, dx, dy);
            }
        }

        if (items.size() > field.maxInstances) {
            int excess = items.size() - field.maxInstances;
            plan.diagnostics.add(Diagnostic.warning(field.id, "OVERFLOW_TABLE_INSTANCES",
                    "Data has " + items.size() + " instances but only " + field.maxInstances +
                            " slots are printed on the form; " + excess + " instance(s) were not drawn."));
        }
    }

    // ---- value resolution -------------------------------------------------------------

    private record Resolved(Object value) {
    }

    private Resolved resolve(Field field, JsonNode scope) {
        ValueBinding binding = field.value;
        if (binding == null) {
            if (field.required) {
                plan.diagnostics.add(Diagnostic.error(field.id, "MISSING_BINDING",
                        "Field has no value binding but is required."));
            }
            return null;
        }
        boolean hasWildcard = binding.path.contains("[*]");
        List<JsonNode> nodes = PathResolver.resolveAll(scope, binding.path);

        Object value;
        if (hasWildcard) {
            if (binding.aggregate == null) {
                plan.diagnostics.add(Diagnostic.error(field.id, "MISSING_AGGREGATE",
                        "Path '" + binding.path + "' contains a wildcard but no aggregate was declared."));
                return null;
            }
            value = aggregate(nodes, binding);
        } else {
            JsonNode node = nodes.isEmpty() ? null : nodes.get(0);
            value = (node == null || node.isNull()) ? null : asJavaValue(node);
        }

        if (value == null) {
            if (binding.defaultValue != null) {
                value = binding.defaultValue;
            } else if (field.required) {
                plan.diagnostics.add(Diagnostic.error(field.id, "MISSING_REQUIRED_VALUE",
                        "Required value not found at path '" + binding.path + "' and no default was declared."));
                return null;
            } else {
                plan.diagnostics.add(Diagnostic.warning(field.id, "MISSING_OPTIONAL_VALUE",
                        "Optional value not found at path '" + binding.path + "'; left blank."));
                return null;
            }
        }
        return new Resolved(value);
    }

    private Object aggregate(List<JsonNode> nodes, ValueBinding binding) {
        switch (binding.aggregate) {
            case sum -> {
                BigDecimal total = BigDecimal.ZERO;
                for (JsonNode n : nodes) {
                    if (n != null && n.isNumber()) {
                        total = total.add(n.decimalValue());
                    }
                }
                return total;
            }
            case count -> {
                return BigDecimal.valueOf(nodes.stream().filter(n -> n != null && !n.isNull()).count());
            }
            case first -> {
                return nodes.isEmpty() ? null : asJavaValue(nodes.get(0));
            }
            case join -> {
                String sep = binding.joinSeparator != null ? binding.joinSeparator : ", ";
                StringBuilder sb = new StringBuilder();
                boolean firstItem = true;
                for (JsonNode n : nodes) {
                    if (n == null || n.isNull()) continue;
                    if (!firstItem) sb.append(sep);
                    sb.append(asJavaValue(n));
                    firstItem = false;
                }
                return sb.toString();
            }
            default -> {
                return null;
            }
        }
    }

    private static Object asJavaValue(JsonNode node) {
        if (node.isNumber()) return node.decimalValue();
        if (node.isBoolean()) return node.booleanValue();
        if (node.isNull()) return null;
        return node.asText();
    }

    private static boolean isTruthy(Object value) {
        if (value instanceof Boolean b) return b;
        if (value instanceof BigDecimal d) return d.signum() != 0;
        if (value instanceof String s) return !s.isBlank() && !"false".equalsIgnoreCase(s);
        return value != null;
    }

    private static boolean valuesEqual(Object a, Object b) {
        if (a == null || b == null) return a == b;
        if (a instanceof Number && b instanceof Number) {
            return new BigDecimal(a.toString()).compareTo(new BigDecimal(b.toString())) == 0;
        }
        return String.valueOf(a).equals(String.valueOf(b));
    }

    // ---- overflow / geometry ------------------------------------------------------------

    private Box offset(Box box, double dx, double dy) {
        if (dx == 0 && dy == 0) return box;
        return new Box(box.page(), box.x() + dx, box.y() + dy, box.width(), box.height());
    }

    /** Returns the font size to draw at, or -1 if overflow=error and the text doesn't fit. */
    private double fitFontSize(Field field, String text, Box box) {
        double size = field.fontSize;
        double width = TextMeasurer.widthOf(text, size);
        if (width <= box.width() || text.isEmpty()) {
            return size;
        }
        switch (field.overflow) {
            case shrink -> {
                double shrunk = size;
                while (shrunk > field.minFontSize && TextMeasurer.widthOf(text, shrunk) > box.width()) {
                    shrunk -= 0.5;
                }
                if (TextMeasurer.widthOf(text, shrunk) > box.width()) {
                    plan.diagnostics.add(Diagnostic.warning(field.id, "OVERFLOW_SHRINK_FLOOR",
                            "Text '" + text + "' still does not fit box width " + box.width() +
                                    " at minFontSize " + field.minFontSize + "; drawing at floor size anyway."));
                }
                return shrunk;
            }
            case truncate -> {
                return size; // actual truncation happens in truncateToFit()
            }
            case error -> {
                plan.diagnostics.add(Diagnostic.error(field.id, "OVERFLOW_ERROR",
                        "Text '" + text + "' (width " + String.format("%.1f", width) +
                                "pt) does not fit box width " + box.width() + "pt and overflow policy is 'error'."));
                return -1;
            }
        }
        return size;
    }

    private String truncateToFit(String text, Field field, Box box) {
        if (TextMeasurer.widthOf(text, field.fontSize) <= box.width()) {
            return text;
        }
        String ellipsis = "...";
        StringBuilder sb = new StringBuilder(text);
        while (sb.length() > 0 && TextMeasurer.widthOf(sb + ellipsis, field.fontSize) > box.width()) {
            sb.deleteCharAt(sb.length() - 1);
        }
        plan.diagnostics.add(Diagnostic.warning(field.id, "OVERFLOW_TRUNCATED",
                "Text truncated to fit box width " + box.width() + "pt."));
        return sb + ellipsis;
    }
}
