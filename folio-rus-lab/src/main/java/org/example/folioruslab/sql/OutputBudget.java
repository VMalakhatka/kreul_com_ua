package org.example.folioruslab.sql;

import java.nio.charset.StandardCharsets;

final class OutputBudget {

    private static final int MAXIMUM_RESULTS = 1_000;
    private static final int MAXIMUM_CELLS = 250_000;

    private final int maximumRows;
    private final long maximumBytes;
    private int rows;
    private int results;
    private int cells;
    private long bytes;

    OutputBudget(int maximumRows, long maximumBytes) {
        this.maximumRows = maximumRows;
        this.maximumBytes = maximumBytes;
    }

    void addRow() {
        if (rows >= maximumRows) {
            throw new OutputLimitExceededException("The result contains more rows than the configured limit");
        }
        rows++;
        addBytes(2);
    }

    void addResult() {
        if (results >= MAXIMUM_RESULTS) {
            throw new OutputLimitExceededException("The batch returned too many separate results");
        }
        results++;
        addBytes(32);
    }

    void addCell() {
        if (cells >= MAXIMUM_CELLS) {
            throw new OutputLimitExceededException("The result contains too many cells");
        }
        cells++;
        addBytes(4);
    }

    void addText(String value) {
        if (value != null) {
            addBytes(value.getBytes(StandardCharsets.UTF_8).length);
        }
    }

    void addBytes(long amount) {
        if (amount < 0 || bytes > maximumBytes - amount) {
            throw new OutputLimitExceededException("The result is larger than the configured byte limit");
        }
        bytes += amount;
    }

    int rows() {
        return rows;
    }

    long bytes() {
        return bytes;
    }

    long remainingBytes() {
        return maximumBytes - bytes;
    }
}
