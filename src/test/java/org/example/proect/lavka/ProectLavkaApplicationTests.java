package org.example.proect.lavka;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "wp.ping.enabled=false"
})
class ProectLavkaApplicationTests {

    @Test
    void contextLoads() {
    }

}
