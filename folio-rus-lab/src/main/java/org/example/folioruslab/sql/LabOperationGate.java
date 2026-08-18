package org.example.folioruslab.sql;

import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;

@Component
public final class LabOperationGate {

    private final Semaphore semaphore = new Semaphore(1, true);

    public boolean tryAcquire() {
        return semaphore.tryAcquire();
    }

    public void release() {
        semaphore.release();
    }
}
