package org.example.proect.lavka.utils;

import java.util.Map;

public final class LogCtx {
    private LogCtx() {}
    public static final ThreadLocal<Map<Long,String>> ID2SKU = new ThreadLocal<>();
}