package org.example.folioruslab.sql;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OutputBudgetTest {

    @Test
    void acceptsExactlyTheConfiguredRowLimitAndRejectsTheNextRow() {
        OutputBudget budget = new OutputBudget(2, 100);

        budget.addRow();
        budget.addRow();

        assertEquals(2, budget.rows());
        assertThrows(OutputLimitExceededException.class, budget::addRow);
    }

    @Test
    void acceptsExactlyTheConfiguredByteLimitAndRejectsTheNextByte() {
        OutputBudget budget = new OutputBudget(10, 5);

        budget.addBytes(2);
        budget.addBytes(3);

        assertEquals(5, budget.bytes());
        assertThrows(OutputLimitExceededException.class, () -> budget.addBytes(1));
        assertEquals(5, budget.bytes());
    }

    @Test
    void countsTextAsUtf8Bytes() {
        OutputBudget budget = new OutputBudget(10, 6);

        budget.addText("Ф");
        budget.addText("olio");

        assertEquals(6, budget.bytes());
        assertThrows(OutputLimitExceededException.class, () -> budget.addText("!"));
    }

    @Test
    void nullTextConsumesNoBytes() {
        OutputBudget budget = new OutputBudget(10, 0);

        budget.addText(null);

        assertEquals(0, budget.bytes());
    }

    @Test
    void zeroLimitsRejectAnyOutput() {
        OutputBudget budget = new OutputBudget(0, 0);

        assertThrows(OutputLimitExceededException.class, budget::addRow);
        assertThrows(OutputLimitExceededException.class, () -> budget.addBytes(1));
    }

    @Test
    void rejectsNegativeByteAccounting() {
        OutputBudget budget = new OutputBudget(10, 100);

        assertThrows(OutputLimitExceededException.class, () -> budget.addBytes(-1));
        assertEquals(0, budget.bytes());
    }
}
