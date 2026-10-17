package org.example.proect.lavka.dao.wp;

import org.example.proect.lavka.dto.folio.FolioProductAnalyticsQueryRequest.GroupBranch;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class FolioGroupBranchesSqlTest {
    @Test void separatePathsCannotProduceCrossBranchMatchesAndExclusionsWin() {
        var clauses = new ArrayList<String>(); var params = new MapSqlParameterSource();
        FolioProductAnalyticsDao.addGroupBranches(clauses, params, List.of(
                new GroupBranch("INCLUDE", List.of("Textile", "Opaque")),
                new GroupBranch("INCLUDE", List.of("Paper", "White")),
                new GroupBranch("EXCLUDE", List.of("Textile", "Opaque", "Kreul' OR 1=1"))));
        assertThat(clauses).containsExactly(
                "((COALESCE(c.group_level_1_code,'')=:branch_0_0 AND COALESCE(c.group_level_2_code,'')=:branch_0_1) OR (COALESCE(c.group_level_1_code,'')=:branch_1_0 AND COALESCE(c.group_level_2_code,'')=:branch_1_1))",
                "NOT ((COALESCE(c.group_level_1_code,'')=:branch_2_0 AND COALESCE(c.group_level_2_code,'')=:branch_2_1 AND COALESCE(c.group_level_3_code,'')=:branch_2_2))");
        assertThat(params.getValue("branch_2_2")).isEqualTo("Kreul' OR 1=1");
        assertThat(String.join(" AND ", clauses)).doesNotContain("Kreul");
    }
    @Test void excludeOnlyIncludesOtherBranchesAndNullLevels() {
        var clauses = new ArrayList<String>(); var params = new MapSqlParameterSource();
        FolioProductAnalyticsDao.addGroupBranches(clauses, params, List.of(new GroupBranch("EXCLUDE", List.of("Textile"))));
        assertThat(clauses).hasSize(1);assertThat(clauses.get(0)).startsWith("NOT (").contains("COALESCE");
        clauses.clear();FolioProductAnalyticsDao.addGroupBranches(clauses,params,List.of());assertThat(clauses).isEmpty();
    }
}
