package org.example.proect.lavka.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Clock;
import java.time.ZoneId;

@Configuration
public class FolioProductSnapshotConfig {

    @Bean(name = "folioProductSnapshotClock")
    public Clock folioProductSnapshotClock(
            @Value("${lavka.folio.product-snapshot.zone:Europe/Kyiv}") String zone) {
        return Clock.system(ZoneId.of(zone));
    }

    @Bean(name = "folioProductSnapshotExecutor")
    public TaskExecutor folioProductSnapshotExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1);
        executor.setThreadNamePrefix("folio-product-snapshot-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }
}
