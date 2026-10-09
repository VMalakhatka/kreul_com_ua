package org.example.proect.lavka.service.folio;

import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Supplier-independent recipe closure. No orders or stock mutations. */
public final class FolioAssemblyGraph {
    public record Recipe(String source, String id, String parent, String child, BigDecimal coefficient) {}
    public record Edge(String parent, String child, BigDecimal factor, String source, String rowId) {}
    public record Node(String sku, boolean manufactured, List<String> issues) {}
    public record Graph(int version, String revision, List<Node> nodes, List<Edge> edges) {}
    public static String key(String value) { return value == null ? "" : value.trim().toUpperCase(Locale.ROOT); }

    public static Graph build(List<String> roots, List<Recipe> recipes, Map<String, Set<Integer>> roles) {
        Set<String> complexChildren = new HashSet<>();
        for (Recipe r : recipes) if (r.source().equals("ALL_RAZBORKA_SLOJ")) complexChildren.add(key(r.child()));
        Map<String, List<Recipe>> consumers = new LinkedHashMap<>(), components = new LinkedHashMap<>();
        for (Recipe r : recipes) {
            if (r.source().equals("ALL_RAZBORKA") && complexChildren.contains(key(r.child()))) continue;
            consumers.computeIfAbsent(key(r.parent()), k -> new ArrayList<>()).add(r);
            components.computeIfAbsent(key(r.child()), k -> new ArrayList<>()).add(r);
        }
        Set<String> reached = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        roots.forEach(s -> { if (reached.add(key(s))) queue.add(key(s)); });
        while (!queue.isEmpty()) {
            for (Recipe r : consumers.getOrDefault(queue.remove(), List.of())) {
                String child = key(r.child());
                if (!child.isEmpty() && reached.add(child)) queue.add(child);
                if (reached.size() > 10000) throw new IllegalArgumentException("Assembly closure exceeds 10000 SKU");
            }
        }
        List<Node> nodes = new ArrayList<>(); List<Edge> edges = new ArrayList<>();
        for (String sku : reached) {
            Set<String> issues = new LinkedHashSet<>();
            Set<Integer> flags = roles.getOrDefault(sku, Set.of());
            if (flags.size() != 1 || flags.stream().anyMatch(f -> f < 0 || f > 12)) issues.add("ASSEMBLY_ROLE_REQUIRED");
            List<Recipe> recipe = components.getOrDefault(sku, List.of());
            if (recipe.size() > 1 && recipe.get(0).source().equals("ALL_RAZBORKA")) issues.add("ASSEMBLY_DUPLICATE_COMPONENT");
            boolean manufactured = !recipe.isEmpty() || flags.stream().anyMatch(f -> Set.of(3,9,11,12).contains(f));
            if (manufactured && recipe.isEmpty()) issues.add("ASSEMBLY_RECIPE_REQUIRED");
            Set<String> parents = new HashSet<>();
            for (Recipe r : recipe) {
                String parent = key(r.parent());
                if (parent.isEmpty() || parent.equals(sku) || r.coefficient() == null || r.coefficient().signum() <= 0)
                    issues.add("ASSEMBLY_INVALID_RECIPE");
                if (!parents.add(parent)) issues.add("ASSEMBLY_DUPLICATE_COMPONENT");
                BigDecimal factor = r.coefficient() == null || r.coefficient().signum() <= 0 ? null
                        : r.source().equals("ALL_RAZBORKA") ? BigDecimal.ONE.divide(r.coefficient(), MathContext.DECIMAL128) : r.coefficient();
                // Include other suppliers' components for explanation, not as purchasing roots.
                edges.add(new Edge(parent, sku, factor, r.source(), r.id()));
            }
            for (Recipe r : consumers.getOrDefault(sku, List.of()))
                if (key(r.child()).isEmpty()) issues.add("ASSEMBLY_INVALID_RECIPE");
            nodes.add(new Node(sku, manufactured, List.copyOf(issues)));
        }
        // Stable revision also captures role changes, suppressed recipes do not affect demand.
        nodes.sort(Comparator.comparing(Node::sku));
        edges.sort(Comparator.comparing(Edge::child).thenComparing(Edge::parent).thenComparing(Edge::rowId));
        try {
            String source = nodes.toString() + edges.toString();
            String revision = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8)));
            return new Graph(1, revision, nodes, edges);
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
