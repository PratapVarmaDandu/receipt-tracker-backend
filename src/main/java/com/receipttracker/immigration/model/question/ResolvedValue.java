package com.receipttracker.immigration.model.question;

/**
 * Result of DataResolver.resolve() — a prefill value with its provenance.
 *
 * @param value      Plaintext string value (null when unavailable).
 * @param source     Where the value came from: "profile" | "org" | "case" | "none".
 * @param verifiedAt ISO-8601 date-time when the value was last verified by staff, or null.
 */
public record ResolvedValue(String value, String source, String verifiedAt) {

    public static ResolvedValue none() {
        return new ResolvedValue(null, "none", null);
    }

    public static ResolvedValue fromProfile(String value) {
        return new ResolvedValue(value, "profile", null);
    }

    public static ResolvedValue fromOrg(String value) {
        return new ResolvedValue(value, "org", null);
    }

    public static ResolvedValue fromCase(String value) {
        return new ResolvedValue(value, "case", null);
    }

    public boolean hasValue() {
        return value != null && !value.isBlank();
    }
}
