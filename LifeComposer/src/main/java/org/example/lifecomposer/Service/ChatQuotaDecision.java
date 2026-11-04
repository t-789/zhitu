package org.example.lifecomposer.Service;

/** Result of one chat quota admission check. */
public record ChatQuotaDecision(
        boolean allowed,
        String error,
        String message,
        long retryAfterSeconds,
        int dailyLimit,
        int usedToday,
        int remainingToday,
        int minuteLimit,
        int minuteRemaining,
        /** Asia/Shanghai usage date the admitted request was counted on; null when not admitted. */
        String consumedDate
) {

    public ChatQuotaDecision(boolean allowed, String error, String message, long retryAfterSeconds,
                             int dailyLimit, int usedToday, int remainingToday,
                             int minuteLimit, int minuteRemaining) {
        this(allowed, error, message, retryAfterSeconds, dailyLimit, usedToday, remainingToday,
                minuteLimit, minuteRemaining, null);
    }
}
