package org.example.lifecomposer.dto;

/**
 * Response envelope used only by {@code /api/auth/*}: {@code {code, message, data}}.
 * Other endpoints keep their own formats until the v1 unification.
 */
public record ApiEnvelope<T>(int code, String message, T data) {

    public static <T> ApiEnvelope<T> ok(T data) {
        return new ApiEnvelope<>(200, "success", data);
    }

    public static <T> ApiEnvelope<T> of(int code, String message) {
        return new ApiEnvelope<>(code, message, null);
    }
}
