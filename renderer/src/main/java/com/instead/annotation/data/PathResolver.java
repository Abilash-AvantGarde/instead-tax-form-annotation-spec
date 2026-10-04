package com.instead.annotation.data;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hand-rolled resolver for the restricted JSONPath-lite grammar defined in docs/SPEC.md
 * section 3:
 *
 * <pre>
 *   path        := "$" segment*
 *   segment     := "." identifier | "[" index "]"
 *   identifier  := [a-zA-Z_][a-zA-Z0-9_]*
 *   index       := "*" | [0-9]+
 * </pre>
 *
 * No external JSONPath library is used, deliberately: the grammar is a small enough closed
 * set that a ~60-line hand-rolled walker is easier to audit than pulling in a general-purpose
 * implementation that supports far more than this spec allows.
 */
public final class PathResolver {

    private static final Pattern TOKEN =
            Pattern.compile("\\.([a-zA-Z_][a-zA-Z0-9_]*)|\\[(\\*|[0-9]+)]");

    private PathResolver() {
    }

    /**
     * Resolves {@code path} against {@code root}, returning every matching leaf value.
     * A path with no wildcard resolves to at most one value. A path with a {@code [*]}
     * segment may resolve to zero or more.
     */
    public static List<JsonNode> resolveAll(JsonNode root, String path) {
        List<JsonNode> current = new ArrayList<>();
        current.add(root);

        if (!path.startsWith("$")) {
            throw new IllegalArgumentException("Path must start with '$': " + path);
        }
        String rest = path.substring(1);
        Matcher m = TOKEN.matcher(rest);
        int pos = 0;
        while (pos < rest.length()) {
            if (!m.find(pos) || m.start() != pos) {
                throw new IllegalArgumentException("Invalid path syntax at offset " + pos + " in: " + path);
            }
            List<JsonNode> next = new ArrayList<>();
            if (m.group(1) != null) {
                String field = m.group(1);
                for (JsonNode node : current) {
                    if (node != null && node.has(field)) {
                        next.add(node.get(field));
                    }
                }
            } else {
                String idx = m.group(2);
                for (JsonNode node : current) {
                    if (node == null || !node.isArray()) {
                        continue;
                    }
                    if ("*".equals(idx)) {
                        node.forEach(next::add);
                    } else {
                        int i = Integer.parseInt(idx);
                        if (i < node.size()) {
                            next.add(node.get(i));
                        }
                    }
                }
            }
            current = next;
            pos = m.end();
        }
        return current;
    }

    /** Convenience for a path expected to resolve to zero or one value. */
    public static JsonNode resolveOne(JsonNode root, String path) {
        List<JsonNode> all = resolveAll(root, path);
        return all.isEmpty() ? null : all.get(0);
    }
}
