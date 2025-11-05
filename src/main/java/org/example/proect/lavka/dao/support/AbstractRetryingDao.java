package org.example.proect.lavka.dao.support;

import lombok.extern.slf4j.Slf4j;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.RetryListener;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.policy.ExceptionClassifierRetryPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;

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
                    (RetryCallback<T, RuntimeException>) context -> action.get(),
                    context -> { // RecoveryCallback — вызывается после исчерпания попыток
                        Throwable last = context.getLastThrowable();
                        log.error("[sync.errors] recover after retries op={} attempts={} cause={}",
                                opName, context.getRetryCount(),
                                last != null ? last.getMessage() : "unknown", last);
                        // Ничего не маскируем — пробрасываем дальше, чтобы верхний слой понял, что это ошибка.
                        if (last instanceof RuntimeException re) throw re;
                        throw new RuntimeException(last);
                    }
            );
        } catch (RuntimeException ex) {
            // Доп. страхующая запись (обычно уже залогировано в RecoveryCallback)
            log.error("[sync.errors] op={} failed beyond retries: {}", opName, ex.getMessage(), ex);
            throw ex;
        }
    }

    /** Вариант для Runnable */
    protected void withRetry(String opName, Runnable runnable) {
        withRetry(opName, () -> { runnable.run(); return null; });
    }

    /** База по умолчанию: maxAttempts=6, backoff 0.5s → 1s → 2s → 4s → 8s (cap 10s) */
    private static RetryTemplate retryTemplateDefaults() {
        RetryTemplate rt = new RetryTemplate();

        // 1) Политика исключений: ретраим только временные/сетевые JDBC-исключения
        Map<Class<? extends Throwable>, Boolean> retryables = Map.of(
                org.springframework.dao.DataAccessResourceFailureException.class, true,
                org.springframework.dao.CannotAcquireLockException.class, true,
                org.springframework.dao.QueryTimeoutException.class, true,
                org.springframework.dao.TransientDataAccessResourceException.class, true,
                org.springframework.dao.ConcurrencyFailureException.class, true,
                java.net.SocketException.class, true,
                java.net.SocketTimeoutException.class, true
        );
        SimpleRetryPolicy simple = new SimpleRetryPolicy(6, retryables, true); // maxAttempts=6
        ExceptionClassifierRetryPolicy classifier = new ExceptionClassifierRetryPolicy();
        classifier.setPolicyMap(Map.of(Throwable.class, simple));
        rt.setRetryPolicy(classifier);

        // 2) Экспоненциальная задержка
        ExponentialBackOffPolicy backoff = new ExponentialBackOffPolicy();
        backoff.setInitialInterval(500);   // 0.5s
        backoff.setMultiplier(2.0);        // x2
        backoff.setMaxInterval(10_000);    // cap 10s
        rt.setBackOffPolicy(backoff);

        // 3) Листенер логирования попыток
        rt.registerListener(new RetryListener() {
            @Override public <T, E extends Throwable> boolean open(RetryContext ctx,
                                                                   RetryCallback<T, E> cb) { return true; }
            @Override public <T, E extends Throwable> void close(RetryContext ctx,
                                                                 RetryCallback<T, E> cb, Throwable t) {}
            @Override public <T, E extends Throwable> void onError(RetryContext ctx,
                                                                   RetryCallback<T, E> cb, Throwable t) {
                log.warn("🔁 Retry attempt #{} for {} due to {}",
                        ctx.getRetryCount(), ctx.getAttribute(RetryContext.NAME),
                        t != null ? t.getClass().getSimpleName() + ": " + t.getMessage() : "unknown");
            }
        });

        return rt;
    }
}