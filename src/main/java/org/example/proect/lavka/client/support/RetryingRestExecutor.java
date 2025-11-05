package org.example.proect.lavka.client.support;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;

import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.Map;
import java.util.function.Supplier;

@Slf4j
@Component
@RequiredArgsConstructor
public class RetryingRestExecutor {

    private final RetryTemplate safeRt = buildSafeTemplate();     // для GET/PUT/идемпотентных POST
    private final RetryTemplate unsafeRt = buildUnsafeTemplate(); // для «опасных» POST (минимум попыток)

    public <T> T execSafe(String op, Supplier<T> call) {
        return safeRt.execute(ctx -> call.get(), recover(op));
    }

    public <T> T execUnsafe(String op, Supplier<T> call) {
        return unsafeRt.execute(ctx -> call.get(), recover(op));
    }

    private static RetryTemplate buildSafeTemplate() {
        RetryTemplate rt = new RetryTemplate();
        rt.setRetryPolicy(new SimpleRetryPolicy(
                6,
                Map.of(
                        ResourceAccessException.class, true,
                        HttpServerErrorException.class, true,
                        SocketTimeoutException.class, true,
                        SocketException.class, true,
                        HttpStatusCodeException.class, true // будем фильтровать внутри backoff listener’ом
                ),
                true
        ));
        ExponentialBackOffPolicy backoff = new ExponentialBackOffPolicy();
        backoff.setInitialInterval(500);
        backoff.setMultiplier(2.0);
        backoff.setMaxInterval(10_000);
        rt.setBackOffPolicy(backoff);
        rt.registerListener(new RetryListenerAdapter());
        return rt;
    }

    private static RetryTemplate buildUnsafeTemplate() {
        RetryTemplate rt = new RetryTemplate();
        rt.setRetryPolicy(new SimpleRetryPolicy(
                2,
                Map.of(
                        ResourceAccessException.class, true,
                        HttpServerErrorException.class, true,
                        SocketTimeoutException.class, true,
                        SocketException.class, true,
                        HttpStatusCodeException.class, true
                ),
                true
        ));
        ExponentialBackOffPolicy backoff = new ExponentialBackOffPolicy();
        backoff.setInitialInterval(800);
        backoff.setMultiplier(2.0);
        backoff.setMaxInterval(5_000);
        rt.setBackOffPolicy(backoff);
        rt.registerListener(new RetryListenerAdapter());
        return rt;
    }

    private <T> org.springframework.retry.RecoveryCallback<T> recover(String op) {
        return ctx -> {
            Throwable last = ctx.getLastThrowable();
            log.error("[sync.errors] http recover op={} attempts={} cause={}",
                    op, ctx.getRetryCount(),
                    last != null ? last.toString() : "unknown", last);
            // Ничего не маскируем — пробрасываем, чтобы наверху приняли бизнес-решение.
            if (last instanceof RuntimeException re) throw re;
            throw new RuntimeException(last);
        };
    }

    static class RetryListenerAdapter implements org.springframework.retry.RetryListener {
        @Override public <T, E extends Throwable> void onError(RetryContext c, RetryCallback<T, E> cb, Throwable t) {
            if (t instanceof HttpStatusCodeException sc) {
                int code = sc.getRawStatusCode();
                // Ретраим только 429/5xx/граничные сетевые, остальное — сразу фейлится по policy
                if (!(code == 429 || code == 502 || code == 503 || code == 504)) {
                    // сбросим оставшиеся попытки (не retryable код)
                    c.setExhaustedOnly();
                }
            }
            // Лог для наблюдения:
            log.warn("🔁 HTTP retry #{} op={} cause={}",
                    c.getRetryCount(),
                    c.getAttribute(RetryContext.NAME), t == null ? "unknown" : t.toString());
        }
    }
}