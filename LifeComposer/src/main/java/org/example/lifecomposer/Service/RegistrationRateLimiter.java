package org.example.lifecomposer.Service;

import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-IP registration rate limiter (sliding window, single instance).
 *
 * <p>v0.1.4 hardening:</p>
 * <ul>
 *   <li>empty windows are evicted again — before this, every IP that ever tried to
 *       register kept one map entry for the lifetime of the process (slow leak, and
 *       a cheap way to grow memory from the outside);</li>
 *   <li>the window length is injectable, so tests can prove expiry instead of
 *       sleeping for a minute;</li>
 *   <li>{@link #retryAfterSeconds(String)} is what the controller puts into the
 *       {@code Retry-After} header of a 429, so a client knows when to come back.</li>
 * </ul>
 *
 * <p>Keyed on the client address as seen by the application; behind a reverse proxy the
 * proxy address is the bucket, so the limit becomes global. See {@code API_GUIDE.md}
 * §2.10 (反向代理与客户端地址) for the deployment note.</p>
 */
@Component
public class RegistrationRateLimiter {

    private static final long DEFAULT_WINDOW_MILLIS = 60_000L;
    /** Sweep idle IPs roughly every this many calls (cheap amortised cleanup). */
    private static final int SWEEP_EVERY = 256;

    private final long windowMillis;
    private final Map<String, Deque<Long>> windows = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicInteger calls =
            new java.util.concurrent.atomic.AtomicInteger();

    public RegistrationRateLimiter() {
        this(DEFAULT_WINDOW_MILLIS);
    }

    RegistrationRateLimiter(long windowMillis) {
        this.windowMillis = Math.max(1L, windowMillis);
    }

    public boolean tryAcquire(String clientIp, int limit) {
        if (clientIp == null || clientIp.isBlank() || limit <= 0) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (calls.incrementAndGet() % SWEEP_EVERY == 0) {
            sweepIdle(now);
        }
        String key = clientIp.trim();
        Deque<Long> window = windows.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        synchronized (window) {
            prune(window, now);
            if (window.size() >= limit) {
                return false;
            }
            window.addLast(now);
            return true;
        }
    }

    /** Seconds until the oldest hit leaves the window; 0 when the IP is not tracked. */
    public long retryAfterSeconds(String clientIp) {
        if (clientIp == null || clientIp.isBlank()) {
            return 0L;
        }
        Deque<Long> window = windows.get(clientIp.trim());
        if (window == null) {
            return 0L;
        }
        synchronized (window) {
            long now = System.currentTimeMillis();
            prune(window, now);
            Long oldest = window.peekFirst();
            if (oldest == null) {
                return 0L;
            }
            long remaining = windowMillis - (now - oldest);
            return Math.max(1L, (remaining + 999) / 1000);
        }
    }

    public void reset() {
        windows.clear();
    }

    /** Number of IPs currently tracked; package-private so tests can assert eviction. */
    int trackedIps() {
        return windows.size();
    }

    /** Whether this address still holds a window; package-private for the eviction test. */
    boolean isTracked(String clientIp) {
        return clientIp != null && windows.containsKey(clientIp.trim());
    }

    private void prune(Deque<Long> window, long now) {
        while (!window.isEmpty() && now - window.peekFirst() >= windowMillis) {
            window.pollFirst();
        }
    }

    /** Drop IPs whose window is empty again, so the map does not grow without bound. */
    private void sweepIdle(long now) {
        for (Map.Entry<String, Deque<Long>> entry : windows.entrySet()) {
            Deque<Long> window = entry.getValue();
            synchronized (window) {
                prune(window, now);
                if (window.isEmpty()) {
                    windows.remove(entry.getKey(), window);
                }
            }
        }
    }
}
