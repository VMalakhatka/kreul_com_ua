package org.example.proect.lavka.utils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.Objects;

public final class SrcHash {

    private static final HexFormat HEX = HexFormat.of();
    public static final String VERSION = "v1"; // для меты _lts_src_hash_v1

    private SrcHash() {}

    public static String hashCard(
            String sku, String name, String edinIzmer, String globalUniqueId,
            Double weight, Double length, Double width, Double height,
            Integer status, Double vesEdinic, String description,
            String razmIzmer, String grDescr, Long groupId
    ) {
        // строгая канонизация: null -> "", trim, unicode NFC, единый формат чисел
        String s = String.join("\n",
                N(sku), N(name), N(edinIzmer), N(globalUniqueId),
                ND(weight), ND(length), ND(width), ND(height),
                NI(status), ND(vesEdinic), N(description),
                N(razmIzmer), N(grDescr),
                String.valueOf(Objects.requireNonNullElse(groupId, 0L))
        );
        return sha256(s);
    }

    private static String N(String v) {
        if (v == null) return "";
        // убираем \r, нормализуем пробелы, NFC для устойчивости
        String n = v.replace("\r", "").trim().replaceAll("[ \\t\\x0B\\f]+", " ");
        return Normalizer.normalize(n, Normalizer.Form.NFC);
    }
    private static String ND(Double d) {
        if (d == null) return "";
        // фиксируем представление: max 6 знаков после запятой, без локалей
        BigDecimal bd = BigDecimal.valueOf(d).stripTrailingZeros();
        bd = bd.scale() < 0 ? bd.setScale(0) : bd;
        if (bd.scale() > 6) bd = bd.setScale(6, java.math.RoundingMode.HALF_UP);
        return bd.toPlainString();
    }
    private static String NI(Integer i) {
        return i == null ? "" : Integer.toString(i);
    }
    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] out = md.digest(s.getBytes(StandardCharsets.UTF_8));
            return HEX.formatHex(out);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}