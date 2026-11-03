package org.example.lifecomposer;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Must run on the test profile: without it the context boots against the
 * production datasource (LifeComposer/data.db) and runs DatabaseInitializer
 * migrations on it.
 */
@SpringBootTest
@ActiveProfiles("test")
class LifeComposerApplicationTests {

    @Test
    void contextLoads() {
    }

}
