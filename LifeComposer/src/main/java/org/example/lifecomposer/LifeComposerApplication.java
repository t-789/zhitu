package org.example.lifecomposer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class LifeComposerApplication {

    /**
     * Timestamps are stored as UTC text (v0.1.3). sqlite-jdbc formats and parses them in the
     * JVM default zone, so the default zone is pinned to UTC; all business zones
     * (chat quota, display times) are passed explicitly where needed.
     * Must run before the DataSource is created (also used by the import CLI).
     */
    public static void useUtcStorageZone() {
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
    }

    public static void main(String[] args) {
        useUtcStorageZone();
        // FIX: Do not keep a global static JDBC Connection in application code.
        // Let Spring DataSource manage connection lifecycle and thread safety.
        SpringApplication.run(LifeComposerApplication.class, args);
    }
}
