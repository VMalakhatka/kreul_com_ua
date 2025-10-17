package org.example.proect.lavka.utils;


/*
*
* 🧩 ② Ограничение параллелизма тяжёлых задач (Semaphore)
Иногда полезно не запускать 10 потоков синхронизации одновременно.
*
* Пример использования в сервисе:
@Service
@RequiredArgsConstructor
public class PriceSyncService {

    private final HeavyTaskLimiter limiter;
    private final PriceService priceService;

    public void syncAllPrices() {
        limiter.runSafely("price-sync", () -> {
            priceService.resolve(...);
            return null;
        });
    }
}
* */


import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

@Slf4j
@Component
public class HeavyTaskLimiter {

    // пример: максимум 2 тяжёлых задачи одновременно
    private final Semaphore semaphore = new Semaphore(2);

    public <T> T runSafely(String name, Supplier<T> action) {
        try {
            semaphore.acquire();
            log.debug("🚦 Start heavy task: {}", name);
            return action.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted waiting for heavy task slot", e);
        } finally {
            semaphore.release();
            log.debug("✅ Finish heavy task: {}", name);
        }
    }
}