package org.example.proect.lavka.utils;


import lombok.extern.slf4j.Slf4j;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.RetryListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class RetryLoggingListener implements RetryListener {

    @Override
    public <T, E extends Throwable> boolean open(RetryContext context, RetryCallback<T, E> callback) {
        // разрешаем выполнение ретраев
        return true;
    }

    @Override
    public <T, E extends Throwable> void onError(
            RetryContext context,
            RetryCallback<T, E> callback,
            Throwable throwable) {

        int attempt = context.getRetryCount() + 1;
        log.warn("🔁 Retry attempt #{} for {} due to {}: {}",
                attempt,
                context.getAttribute(RetryContext.NAME) != null
                        ? context.getAttribute(RetryContext.NAME)
                        : callback.getClass().getSimpleName(),
                throwable.getClass().getSimpleName(),
                throwable.getMessage());
    }

    @Override
    public <T, E extends Throwable> void close(
            RetryContext context,
            RetryCallback<T, E> callback,
            Throwable throwable) {
        if (throwable == null) {
            log.debug("✅ Retry sequence completed successfully for {}",
                    context.getAttribute(RetryContext.NAME));
        } else {
            log.error("❌ Retry sequence ended with failure after {} attempts: {}",
                    context.getRetryCount() + 1,
                    throwable.getMessage());
        }
    }
}