package org.example.proect.lavka.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Clock;
import java.time.ZoneId;

@Configuration
public class FolioBalanceSnapshotConfig {

    @Bean(name = "folioBalanceClock")
    public Clock folioBalanceClock(
            @Value("${lavka.folio.balance-snapshot.zone:Europe/Kyiv}") String zone) {
        return Clock.system(ZoneId.of(zone));
    }

    @Bean(name = "folioBalanceSnapshotExecutor")
    public TaskExecutor folioBalanceSnapshotExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1);
        executor.setThreadNamePrefix("folio-balance-snapshot-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }
}
