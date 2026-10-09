package org.example.proect.lavka.service.folio;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FolioAssemblyGraphTest {
    private FolioAssemblyGraph.Recipe r(boolean complex, String id, String p, String c, String k) {
        return new FolioAssemblyGraph.Recipe(complex ? "ALL_RAZBORKA_SLOJ" : "ALL_RAZBORKA", id, p, c, new BigDecimal(k));
    }
    @Test void complexChildRecipeReplacesAllSimpleParentsAndPreservesOtherComponents() {
        var graph = FolioAssemblyGraph.build(List.of("P", "C"), List.of(
                r(false,"1","P","C","2"), r(false,"2","OLD","C","5"),
                r(true,"3","P","C","0.25"), r(true,"4","OTHER-SUPPLIER","C","0.1"),
                r(true,"5","C","GRANDCHILD","0.5"), r(true,"6","OTHER-SUPPLIER","UNRELATED","1")),
                Map.of("P",Set.of(1),"C",Set.of(11),"GRANDCHILD",Set.of(12)));
        assertEquals(Set.of("P","C","GRANDCHILD"), new HashSet<>(graph.nodes().stream().map(FolioAssemblyGraph.Node::sku).toList()));
        assertEquals(3, graph.edges().size());
        assertTrue(graph.edges().stream().allMatch(e -> e.source().equals("ALL_RAZBORKA_SLOJ")));
        assertTrue(graph.edges().stream().anyMatch(e -> e.parent().equals("OTHER-SUPPLIER")));
        assertTrue(graph.nodes().stream().allMatch(n -> n.issues().isEmpty()));
    }
    @Test void invalidComplexMustNotFallBackToSimpleOrLoseConnectivity() {
        var graph = FolioAssemblyGraph.build(List.of("P"), List.of(r(false,"1","P","C","4"),r(true,"2","P","C","0")), Map.of("P",Set.of(1),"C",Set.of(3)));
        assertEquals(1, graph.edges().size()); assertNull(graph.edges().get(0).factor());
        assertTrue(graph.nodes().stream().filter(n -> n.sku().equals("C")).findFirst().orElseThrow().issues().contains("ASSEMBLY_INVALID_RECIPE"));
    }
    @Test void simpleUsesYieldAndRevisionIncludesRoles() {
        var recipe = List.of(r(false,"1","P","C","4"));
        var graph = FolioAssemblyGraph.build(List.of(" p "), recipe, Map.of("P",Set.of(1),"C",Set.of(3)));
        assertEquals(0, new BigDecimal("0.25").compareTo(graph.edges().get(0).factor()));
        assertNotEquals(graph.revision(), FolioAssemblyGraph.build(List.of("P"), recipe, Map.of("P",Set.of(1,2),"C",Set.of(3))).revision());
    }
    @Test void flagsMissingRecipesSelfReferencesAndDuplicatesNeedReview() {
        var graph = FolioAssemblyGraph.build(List.of("P","MISSING"), List.of(r(true,"1","P","P","1"),r(true,"2","P","P","1")), Map.of("P",Set.of(11),"MISSING",Set.of(9)));
        var parent = graph.nodes().stream().filter(n -> n.sku().equals("P")).findFirst().orElseThrow();
        assertTrue(parent.issues().containsAll(List.of("ASSEMBLY_INVALID_RECIPE","ASSEMBLY_DUPLICATE_COMPONENT")));
        assertTrue(graph.nodes().stream().filter(n -> n.sku().equals("MISSING")).findFirst().orElseThrow().issues().contains("ASSEMBLY_RECIPE_REQUIRED"));
    }
}
