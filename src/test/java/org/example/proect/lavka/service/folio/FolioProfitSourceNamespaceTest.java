package org.example.proect.lavka.service.folio;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class FolioProfitSourceNamespaceTest {
    @ParameterizedTest @ValueSource(strings={
            "jdbc:jtds:sqlserver://example.invalid/Paint_Ua",
            "jdbc:jtds:sqlserver://example.invalid:1433/Paint_Ua;loginTimeout=10",
            "jdbc:jtds:sqlserver://example.invalid:1433;databaseName=Paint_Ua",
            "jdbc:jtds:sqlserver://example.invalid;instance=TEST;databaseName=Paint_Ua;loginTimeout=10;",
            "JDBC:JTDS:SQLSERVER://example.invalid;DATABASENAME=Paint_Ua",
            "jdbc:jtds:sqlserver://example.invalid/Paint_Ua;databaseName=Paint_Ua",
            "jdbc:jtds:sqlserver://example.invalid;databaseName=Paint_Ua;databaseNAME=Paint_Ua",
            "jdbc:jtds:sqlserver://example.invalid;password=[test;databaseName=Paint_Rus];databaseName=Paint_Ua",
            "jdbc:jtds:sqlserver://example.invalid/Paint_Ua;password=[test/with:delimiters]"
    })
    void productionPropertyAndLegacyPathResolveToSameNamespaceWithoutConnection(String url) {
        assertThat(FolioProfitHistoryService.sourceFromUrl(url)).isEqualTo("Paint_Ua");
    }
    @ParameterizedTest @ValueSource(strings={
            "jdbc:jtds:sqlserver://example.invalid/Paint_Rus;test=true",
            "jdbc:jtds:sqlserver://example.invalid;databaseName=Paint_Rus"
    })
    void testDatabaseNeverFallsBackToProduction(String url) {
        assertThat(FolioProfitHistoryService.sourceFromUrl(url)).isEqualTo("Paint_Rus");
    }
    @ParameterizedTest @NullSource @ValueSource(strings={
            "", "unknown", "jdbc:mysql://example.invalid/Paint_Ua",
            "jdbc:jtds:sybase://example.invalid/Paint_Ua",
            "jdbc:jtds:sqlserver://example.invalid", "jdbc:jtds:sqlserver://example.invalid/",
            "jdbc:jtds:sqlserver://example.invalid;databaseName=",
            "jdbc:jtds:sqlserver://example.invalid;databaseName",
            "jdbc:jtds:sqlserver://example.invalid;databaseName=Paint_Ua;databaseName=Paint_Rus",
            "jdbc:jtds:sqlserver://example.invalid/Paint_Ua;databaseName=Paint_Rus",
            "jdbc:jtds:sqlserver://example.invalid/Paint_Ua;databaseName=paint_ua",
            "jdbc:jtds:sqlserver://example.invalid/Paint_Ua;databaseName=",
            "jdbc:jtds:sqlserver://example.invalid;databaseName=Paint_Ua/other",
            "jdbc:jtds:sqlserver://example.invalid;databaseName=Paint_Ua?option=true",
            "jdbc:jtds:sqlserver://example.invalid/Paint_Ua?option=true",
            "jdbc:jtds:sqlserver://example.invalid;databaseName= Paint_Ua",
            "jdbc:jtds:sqlserver://example.invalid;databaseName=[Paint_Ua]",
            "jdbc:jtds:sqlserver://example.invalid;databaseName=Paint_Ua;password=[unclosed",
            "jdbc:jtds:sqlserver://example.invalid;password=[test;databaseName=Paint_Ua]",
            "jdbc:jtds:sqlserver://example.invalid;password=test/Paint_Ua",
            "jdbc:jtds:sqlserver://example.invalid;password=test/ignored;databaseName=Paint_Ua",
            "jdbc:jtds:sqlserver://example.invalid:not-a-port;databaseName=Paint_Ua",
            "jdbc:jtds:sqlserver:///Paint_Ua"
    })
    void missingUnsafeConflictingOrDriverDisagreeingNamespaceFailsClosedWithoutLeakingUrl(String url) {
        assertThatThrownBy(()->FolioProfitHistoryService.sourceFromUrl(url))
                .isInstanceOfSatisfying(FolioAccountValidationException.class,e->{
                    assertThat(e.getCode()).isEqualTo("PROFIT_SOURCE_NAMESPACE_UNAVAILABLE");
                    assertThat(e.getCause()).isNull();
                    assertThat(e.getMessage()).doesNotContain("example.invalid","password=","jdbc:");
                });
    }
}
