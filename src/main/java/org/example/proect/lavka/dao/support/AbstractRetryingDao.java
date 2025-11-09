package org.example.proect.lavka.dao.support;

import lombok.extern.slf4j.Slf4j;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.RetryListener;
import org.springframework.retry.RetryPolicy;
import org.springframework.retry.backoff.ExponentialRandomBackOffPolicy;
import org.springframework.retry.policy.ExceptionClassifierRetryPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

@Slf4j
public abstract class AbstractRetryingDao {

    private final RetryTemplate retryTemplate;

    protected AbstractRetryingDao() {
        this(retryTemplateDefaults());
    }

    protected AbstractRetryingDao(RetryTemplate retryTemplate) {
        this.retryTemplate = retryTemplate;
    }

    /** Выполнить действие с ретраями и вернуть результат */
    protected <T> T withRetry(String opName, Supplier<T> action) {
        try {
            return retryTemplate.execute(
                    (RetryCallback<T, RuntimeException>) ctx -> {
                        ctx.setAttribute(RetryContext.NAME, opName);
                        return action.get();
                    },
                    ctx -> {
                        Throwable last = ctx.getLastThrowable();
                        log.error("[sync.errors] recover after retries op={} attempts={} cause={}",
                                opName, ctx.getRetryCount(),
                                last != null ? last.getMessage() : "unknown", last);
                        if (last instanceof RuntimeException re) throw re;
                        throw new RuntimeException(last);
                    }
            );
        } catch (RuntimeException ex) {
            log.error("[sync.errors] op={} failed beyond retries: {}", opName, ex.getMessage(), ex);
            throw ex;
        }
    }

    /** Вариант для Runnable */
    protected void withRetry(String opName, Runnable runnable) {
        withRetry(opName, () -> { runnable.run(); return null; });
    }

    // -------------------- internal --------------------

    private static RetryTemplate retryTemplateDefaults() {
        RetryTemplate rt = new RetryTemplate();

        // 1) Кого ретраим (только временные/сетевые/коннектные)
        Map<Class<? extends Throwable>, Boolean> retryables = new HashMap<>();
        // Spring DAO
        retryables.put(org.springframework.dao.DataAccessResourceFailureException.class, true);
        retryables.put(org.springframework.dao.CannotAcquireLockException.class, true);
        retryables.put(org.springframework.dao.QueryTimeoutException.class, true);
        retryables.put(org.springframework.dao.TransientDataAccessResourceException.class, true);
        retryables.put(org.springframework.dao.ConcurrencyFailureException.class, true);
        // JDBC generic
        retryables.put(java.sql.SQLTransientException.class, true);
        retryables.put(java.sql.SQLTransientConnectionException.class, true);
        retryables.put(java.sql.SQLRecoverableException.class, true);
        retryables.put(java.sql.SQLNonTransientConnectionException.class, true);
        // Сеть
        retryables.put(java.net.SocketException.class, true);
        retryables.put(java.net.SocketTimeoutException.class, true);
        // Опциональные (если есть на classpath)
        addIfPresent(retryables, "com.mysql.cj.jdbc.exceptions.CommunicationsException");
        addIfPresent(retryables, "com.mysql.cj.exceptions.CJCommunicationsException");
        addIfPresent(retryables, "org.mariadb.jdbc.client.socket.impl.AbortedConnectionException");

// -------- кастомная динамическая политика --------
        RetryPolicy dynamicPolicy = new org.springframework.retry.policy.NeverRetryPolicy() {
            @Override public boolean canRetry(RetryContext ctx) {
                Throwable last = ctx.getLastThrowable();
                if (last == null) return true; // первая попытка

                // 1) по классам (стандартные транзиентные)
                if (last instanceof org.springframework.dao.DataAccessResourceFailureException
                        || last instanceof org.springframework.dao.CannotAcquireLockException
                        || last instanceof org.springframework.dao.QueryTimeoutException
                        || last instanceof org.springframework.dao.TransientDataAccessResourceException
                        || last instanceof org.springframework.dao.ConcurrencyFailureException
                        || last instanceof java.net.SocketException
                        || last instanceof java.net.SocketTimeoutException)
                    return true;

                // 2) по SQLState
                return isRetryableSql(last);
            }
        };

        rt.setRetryPolicy(dynamicPolicy);

        // 2) Экспоненциальный бэк-офф с джиттером
        ExponentialRandomBackOffPolicy backoff = new ExponentialRandomBackOffPolicy();
        backoff.setInitialInterval(500);   // 0.5s
        backoff.setMultiplier(2.0);        // 0.5 → 1 → 2 → 4 → 8 …
        backoff.setMaxInterval(10_000);    // cap 10s
        rt.setBackOffPolicy(backoff);

        // 3) Листенер попыток
        rt.registerListener(new RetryListener() {
            @Override public <T, E extends Throwable> void onError(
                    RetryContext ctx, RetryCallback<T, E> cb, Throwable t) {
                log.warn("🔁 retry #{} for {} due to {}",
                        ctx.getRetryCount(),
                        ctx.getAttribute(RetryContext.NAME),
                        t != null ? (t.getClass().getSimpleName() + ": " + t.getMessage()) : "unknown");
            }
        });

        return rt;
    }

    @SuppressWarnings("unchecked")
    private static void addIfPresent(Map<Class<? extends Throwable>, Boolean> map, String className) {
        try {
            Class<?> c = Class.forName(className);
            if (Throwable.class.isAssignableFrom(c)) {
                map.put((Class<? extends Throwable>) c, true);
            }
        } catch (ClassNotFoundException ignore) {}
    }

    private static boolean isRetryableSql(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            if (cur instanceof java.sql.SQLException ex) {
                String s = ex.getSQLState();
                if (s != null) {
                    if (s.startsWith("08")) return true;  // connection errors
                    if (s.equals("40001")) return true;   // deadlock
                    if (s.equals("HYT00") || s.equals("HYT01")) return true; // timeout
                }
            }
            cur = cur.getCause();
        }
        return false;
    }
}