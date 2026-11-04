package org.example.lifecomposer.Service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RegistrationRateLimiterTest {

    @Test
    void rejectsAfterPerIpLimit() {
        RegistrationRateLimiter limiter = new RegistrationRateLimiter();

        assertTrue(limiter.tryAcquire("10.0.0.1", 1));
        assertFalse(limiter.tryAcquire("10.0.0.1", 1));
        assertTrue(limiter.retryAfterSeconds("10.0.0.1") > 0);
        assertTrue(limiter.tryAcquire("10.0.0.2", 1));
    }

    @Test
    void retryAfterIsZeroForUnknownIp() {
        RegistrationRateLimiter limiter = new RegistrationRateLimiter();
        assertEquals(0L, limiter.retryAfterSeconds("10.9.9.9"));
        assertEquals(0L, limiter.retryAfterSeconds(null));
    }

    @Test
    void windowExpires() throws Exception {
        RegistrationRateLimiter limiter = new RegistrationRateLimiter(50);

        assertTrue(limiter.tryAcquire("10.0.0.3", 1));
        assertFalse(limiter.tryAcquire("10.0.0.3", 1));
        Thread.sleep(70);
        assertTrue(limiter.tryAcquire("10.0.0.3", 1), "窗口过期后应重新放行");
    }

    @Test
    void ipWithBlankIsRejected() {
        RegistrationRateLimiter limiter = new RegistrationRateLimiter();
        assertFalse(limiter.tryAcquire(null, 10));
        assertFalse(limiter.tryAcquire("   ", 10));
        assertFalse(limiter.tryAcquire("10.0.0.4", 0));
    }

    @Test
    void idleIpsAreEvicted() throws Exception {
        RegistrationRateLimiter limiter = new RegistrationRateLimiter(20);

        assertTrue(limiter.tryAcquire("10.1.1.1", 5));
        assertTrue(limiter.isTracked("10.1.1.1"));
        Thread.sleep(40);

        // 每次 tryAcquire 都可能触发一次清扫，300 个不同 IP 足以把空闲的挤掉
        for (int i = 0; i < 300; i++) {
            assertTrue(limiter.tryAcquire("10.2.0." + i, 1));
        }
        assertFalse(limiter.isTracked("10.1.1.1"), "空闲 IP 不应长期占着 map");
    }
}
