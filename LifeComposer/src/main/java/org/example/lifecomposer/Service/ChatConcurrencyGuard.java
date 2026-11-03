package org.example.lifecomposer.Service;

import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * v0.1.2: at most one in-flight AI request (chat send/stream or profile-decision
 * continuation) per user, so concurrent turns never interleave in chat_messages.
 * Single-instance, in-memory, like {@link InMemoryMinuteRateLimiter}.
 */
@Service
public class ChatConcurrencyGuard {

    private final Set<Integer> active = ConcurrentHashMap.newKeySet();

    /** @return true when the caller now owns the user's slot and must call {@link #release}. */
    public boolean tryAcquire(Integer userId) {
        return userId != null && active.add(userId);
    }

    public void release(Integer userId) {
        if (userId != null) {
            active.remove(userId);
        }
    }

    public boolean isActive(Integer userId) {
        return userId != null && active.contains(userId);
    }
}
