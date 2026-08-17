package org.example.folioruslab.sql;

import org.example.folioruslab.config.LabProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ResolvedExecutionOptionsTest {

    private LabProperties properties;

    @BeforeEach
    void setUp() {
        properties = new LabProperties();
        properties.setToken("0123456789abcdef0123456789abcdef");
        properties.setDefaultTimeoutSeconds(17);
        properties.setMaximumTimeoutSeconds(60);
        properties.setDefaultMaxRows(321);
        properties.setMaximumMaxRows(20_000);
        properties.setDefaultMaxBytes(4_096);
        properties.setMaximumMaxBytes(10_485_760);
    }

    @Test
    void resolvesNullOptionsToRollbackAndConfiguredDefaults() {
        ResolvedExecutionOptions resolved = ResolvedExecutionOptions.from(
                new SqlExecutionRequest("SELECT 1", null, null, null, null, null, null),
                properties
        );

        assertAll(
                () -> assertEquals(ExecutionMode.ROLLBACK, resolved.mode()),
                () -> assertEquals(17, resolved.timeoutSeconds()),
                () -> assertEquals(321, resolved.maxRows()),
                () -> assertEquals(4_096, resolved.maxBytes())
        );
    }

    @Test
    void acceptsValuesExactlyAtConfiguredMaximums() {
        ResolvedExecutionOptions resolved = ResolvedExecutionOptions.from(
                new SqlExecutionRequest(
                        "SELECT 1",
                        ExecutionMode.COMMIT,
                        true,
                        "Paint_Rus",
                        60,
                        20_000,
                        10_485_760L
                ),
                properties
        );

        assertAll(
                () -> assertEquals(ExecutionMode.COMMIT, resolved.mode()),
                () -> assertEquals(60, resolved.timeoutSeconds()),
                () -> assertEquals(20_000, resolved.maxRows()),
                () -> assertEquals(10_485_760L, resolved.maxBytes())
        );
    }

    @Test
    void rejectsEachValueAboveItsConfiguredMaximum() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () ->
                        ResolvedExecutionOptions.from(
                                new SqlExecutionRequest(
                                        "SELECT 1", null, null, null, 61, null, null),
                                properties)),
                () -> assertThrows(IllegalArgumentException.class, () ->
                        ResolvedExecutionOptions.from(
                                new SqlExecutionRequest(
                                        "SELECT 1", null, null, null, null, 20_001, null),
                                properties)),
                () -> assertThrows(IllegalArgumentException.class, () ->
                        ResolvedExecutionOptions.from(
                                new SqlExecutionRequest(
                                        "SELECT 1", null, null, null, null, null, 10_485_761L),
                                properties))
        );
    }
}
