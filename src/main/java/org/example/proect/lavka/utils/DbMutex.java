package org.example.proect.lavka.utils;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

@Slf4j
@Component
@RequiredArgsConstructor
public class DbMutex {

    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager txManager;

    /**
     * Выполняет body под applock (Exclusive), где владельцем замка является транзакция.
     * ВАЖНО: вся работа идёт в одной транзакции и на одном соединении.
     */
    public <T> T withAppLock(String resource, int timeoutMs, Supplier<T> body) {
        Objects.requireNonNull(resource, "resource");
        if (timeoutMs < 0) timeoutMs = 0;

        var tt = new TransactionTemplate(txManager);
        int finalTimeoutMs = timeoutMs;
        return tt.execute(status -> {
            int rc = acquireTxLock(resource, finalTimeoutMs);
            if (rc < 0) {
                // бросаем transient исключение — чтобы его перехватил Spring Retry выше по стеку
                throw new org.springframework.dao.CannotAcquireLockException(
                        "sp_getapplock failed: rc=" + rc + " resource=" + resource);
            }

            try {
                T result = body.get();
                return result;
            } finally {
                // при LockOwner='Transaction' lock и так освободится на COMMIT/ROLLBACK,
                // но явный release безопаснее.
                try {
                    jdbc.update("EXEC sp_releaseapplock @Resource=?, @LockOwner='Transaction'", resource);
                } catch (Exception e) {
                    log.warn("sp_releaseapplock failed for resource={}: {}", resource, e.toString());
                }
            }
        });
    }

    /** Удобная void-обёртка. */
    public void withAppLock(String resource, int timeoutMs, Runnable body) {
        withAppLock(resource, timeoutMs, () -> { body.run(); return null; });
    }

    /** Если нужен Callable с checked исключениями. */
    public <T> T withAppLockCall(String resource, int timeoutMs, Callable<T> body) {
        return withAppLock(resource, timeoutMs, () -> {
            try {
                return body.call();
            } catch (RuntimeException re) {
                throw re;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    /** Берём applock и возвращаем RC (>=0 — успех, <0 — ошибка/таймаут/дедлок). */
    private int acquireTxLock(String resource, int timeoutMs) {
        return jdbc.execute((Connection con) -> {  // 👈 указали явно тип
            try (PreparedStatement ps = con.prepareStatement(
                    "DECLARE @rc INT; " +
                            "EXEC @rc = sp_getapplock " +
                            "  @Resource = ?, " +
                            "  @LockMode = 'Exclusive', " +
                            "  @LockOwner = 'Transaction', " +
                            "  @LockTimeout = ?; " +
                            "SELECT @rc;")) {

                ps.setString(1, resource);
                ps.setInt(2, timeoutMs);

                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    int rc = rs.getInt(1);
                    if (rc < 0) {
                        log.warn("sp_getapplock rc={} resource={} (timeoutMs={})", rc, resource, timeoutMs);
                    } else {
                        log.debug("sp_getapplock OK rc={} resource={}", rc, resource);
                    }
                    return rc;
                }
            }
        });
    }

    /** Хелпер: удобнее именовать ресурсы консистентно. */
    public static String resource(String... parts) {
        return String.join("|", parts);
    }
}