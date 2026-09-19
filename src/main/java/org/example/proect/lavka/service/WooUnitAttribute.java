package org.example.proect.lavka.service;

import java.util.*;

/** Merge only the unit into a complete REST attribute collection; do not mutate the read snapshot. */
final class WooUnitAttribute {
    private WooUnitAttribute() {}
    static boolean hasValue(List<Map<String, Object>> attributes, long id) {
        return attributes.stream().filter(a -> matches(a, id))
                .anyMatch(a -> a.get("options") instanceof List<?> options
                        && options.stream().anyMatch(v -> v instanceof String s && !s.isBlank()));
    }
    static List<Map<String, Object>> merge(List<Map<String, Object>> attributes, long id, String unit) {
        var result = new ArrayList<Map<String, Object>>();
        boolean found = false;
        int position = -1;
        for (var attribute : attributes) {
            var copy = new LinkedHashMap<>(attribute);
            if (attribute.get("position") instanceof Number n) position = Math.max(position, n.intValue());
            if (matches(attribute, id)) {
                if (found) throw new IllegalStateException("WOO_UNIT_ATTRIBUTE_DUPLICATED");
                copy.put("options", List.of(unit));
                found = true;
            }
            result.add(copy);
        }
        if (!found) result.add(new LinkedHashMap<>(Map.of("id", id, "options", List.of(unit),
                "position", position + 1, "visible", true, "variation", false)));
        return result;
    }
    private static boolean matches(Map<String, Object> attribute, long id) {
        return attribute.get("id") instanceof Number n && n.longValue() == id;
    }
}
