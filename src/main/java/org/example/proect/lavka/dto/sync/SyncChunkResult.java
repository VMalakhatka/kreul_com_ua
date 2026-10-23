package org.example.proect.lavka.dto.sync;

import java.util.List;

public record SyncChunkResult(int processed, int created, int updated, int drafted,
                              String nextAfter, boolean exhausted, List<String> errors) {}
