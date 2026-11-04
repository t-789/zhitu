package org.example.lifecomposer.Service;

import org.example.lifecomposer.Repository.ChatUsageRepository;
import org.example.lifecomposer.config.AppSecurityProperties;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZonedDateTime;

/** Enforces per-user minute and Asia/Shanghai daily chat quotas. */
@Service
public class ChatQuotaService {

    private final ChatUsageRepository usageRepository;
    private final InMemoryMinuteRateLimiter minuteRateLimiter;
    private final AppSecurityProperties properties;
    private final Clock clock;

    public ChatQuotaService(ChatUsageRepository usageRepository,
                            InMemoryMinuteRateLimiter minuteRateLimiter,
                            AppSecurityProperties properties,
                            Clock clock) {
        this.usageRepository = usageRepository;
        this.minuteRateLimiter = minuteRateLimiter;
        this.properties = properties;
        this.clock = clock;
    }

    public String today() {
        return LocalDate.now(clock).toString();
    }

    /**
     * Admission ordering:
     * 1. reject if today's DB count is already full (no minute token consumed);
     * 2. consume a minute token;
     * 3. atomically increment the DB counter; if another request won the last
     *    slot, refund the minute token and reject.
     */
    public ChatQuotaDecision tryConsume(Integer userId) {
        String date = today();
        int dailyLimit = properties.getChatPerDay();
        int minuteLimit = properties.getChatPerMinute();

        int used = usageRepository.getCount(userId, date);
        if (used >= dailyLimit) {
            return dailyRejected(dailyLimit, used, minuteLimit);
        }

        if (!minuteRateLimiter.tryAcquire(userId, minuteLimit)) {
            return new ChatQuotaDecision(false, "CHAT_MINUTE_LIMIT", "聊天过于频繁，请稍后再试",
                    minuteRateLimiter.retryAfterSeconds(userId),
                    dailyLimit, used, Math.max(0, dailyLimit - used),
                    minuteLimit, 0);
        }

        if (usageRepository.tryIncrement(userId, date, dailyLimit) == 0) {
            minuteRateLimiter.refund(userId);
            int usedNow = usageRepository.getCount(userId, date);
            return dailyRejected(dailyLimit, usedNow, minuteLimit);
        }

        int usedAfter = usageRepository.getCount(userId, date);
        return new ChatQuotaDecision(true, null, null, 0L,
                dailyLimit, usedAfter, Math.max(0, dailyLimit - usedAfter),
                minuteLimit, Math.max(0, minuteLimit - 1), date);
    }

    /**
     * v0.1.2: give back one admitted request that never reached the model
     * (e.g. the stream executor was full). Undoes both the daily counter and the
     * minute token.
     */
    public void refund(Integer userId) {
        usageRepository.decrement(userId, today());
        minuteRateLimiter.refund(userId);
    }

    /**
     * Refund an admitted request on the day it was actually counted, so a request
     * that straddles midnight does not take a unit from the new day instead.
     */
    public void refund(Integer userId, ChatQuotaDecision admitted) {
        String date = admitted != null && admitted.consumedDate() != null
                ? admitted.consumedDate()
                : today();
        usageRepository.decrement(userId, date);
        minuteRateLimiter.refund(userId);
    }

    /** Admin operation: reset only the current Asia/Shanghai day. */
    public ChatQuotaDecision resetToday(Integer userId) {
        String date = today();
        usageRepository.reset(userId, date);
        minuteRateLimiter.reset(userId);
        int used = usageRepository.getCount(userId, date);
        return new ChatQuotaDecision(true, null, null, 0L,
                properties.getChatPerDay(), used,
                Math.max(0, properties.getChatPerDay() - used),
                properties.getChatPerMinute(), properties.getChatPerMinute());
    }

    private ChatQuotaDecision dailyRejected(int dailyLimit, int used, int minuteLimit) {
        return new ChatQuotaDecision(false, "CHAT_DAILY_LIMIT", "今日聊天额度已用完",
                secondsUntilTomorrow(), dailyLimit, used,
                Math.max(0, dailyLimit - used), minuteLimit, 0);
    }

    private long secondsUntilTomorrow() {
        ZonedDateTime now = ZonedDateTime.now(clock);
        ZonedDateTime tomorrow = now.toLocalDate().plusDays(1).atStartOfDay(clock.getZone());
        return Math.max(1L, Duration.between(now, tomorrow).getSeconds());
    }
}
