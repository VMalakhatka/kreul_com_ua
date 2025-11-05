package org.example.proect.lavka.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
class BootPing implements ApplicationRunner {
    @Override public void run(ApplicationArguments args) {
        log.info("[boot] lavka app started, ping log to file");
    }
}