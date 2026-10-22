package org.example.proect.lavka.utils;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.proect.lavka.dto.CardTovExportOutDto;

import java.security.MessageDigest;
import java.util.HexFormat;

/** Hash the complete exported card, including future fields, but never its own hash. */
public final class FullSyncHash {
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY).build();

    private FullSyncHash() { }

    public static String calculate(CardTovExportOutDto card) {
        ObjectNode payload = JSON.valueToTree(card);
        payload.remove("hash");
        payload.put("_hashSchema", "full-card-v2");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(JSON.writeValueAsBytes(payload)));
        } catch (Exception e) {
            throw new IllegalStateException("FULL_SYNC_HASH_FAILED", e);
        }
    }
}
