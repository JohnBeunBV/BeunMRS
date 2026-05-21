package com.openmrs.notification.util;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Shared utilities for all notification provider adapters.
 *
 * formatTime      — converts an Instant to a human-readable Dutch string,
 *                   using the tenant-configured IANA timezone (NFR-13).
 * mask            — masks phone numbers and e-mail addresses for safe logging.
 * commentsSuffix  — formats the optional OpenMRS appointment comments field
 *                   as an appendable sentence.
 * locationSuffix  — returns " bij {locationName}" or "" when absent.
 */
public final class MessageHelper {

    /** Fallback timezone when none is configured for the tenant. */
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Europe/Amsterdam");

    private static final Locale NL = Locale.of("nl", "NL");

    private MessageHelper() { /* utility class */ }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Formats an Instant using the tenant's IANA timezone (from AppointmentEvent.getTimezone()).
     * Falls back to Europe/Amsterdam when timezone is null or invalid.
     *
     * Example output: "maandag 24 januari 2026 om 14:30"
     */
    public static String formatTime(Instant instant, String timezone) {
        if (instant == null) return "onbekend tijdstip";
        ZoneId zone = parseZone(timezone);
        return DateTimeFormatter
                .ofPattern("EEEE d MMMM yyyy 'om' HH:mm", NL)
                .withZone(zone)
                .format(instant);
    }

    /**
     * Convenience overload using the Europe/Amsterdam fallback.
     * Prefer {@link #formatTime(Instant, String)} when a tenant timezone is available.
     */
    public static String formatTime(Instant instant) {
        return formatTime(instant, null);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private static ZoneId parseZone(String timezone) {
        if (timezone == null || timezone.isBlank()) return DEFAULT_ZONE;
        try {
            return ZoneId.of(timezone);
        } catch (Exception e) {
            return DEFAULT_ZONE;
        }
    }

    /**
     * Masks a phone number or e-mail address for safe log output.
     *
     * Examples:
     *   "0612345678"          → "061****678"
     *   "betty@example.com"   → "b****@example.com"
     *   null                  → "<null>"
     */
    public static String mask(String value) {
        if (value == null) return "<null>";
        if (value.contains("@")) {
            int at = value.indexOf('@');
            if (at <= 1) return "****" + value.substring(at);
            return value.charAt(0) + "****" + value.substring(at);
        }
        // Phone / generic: keep first 3 + last 3 characters
        if (value.length() <= 6) return "****";
        return value.substring(0, 3) + "****" + value.substring(value.length() - 3);
    }

    /**
     * Converts the OpenMRS appointment comments into an appendable suffix.
     * Returns an empty string when comments is null or blank.
     *
     * Example: " Opmerking: nuchter komen."
     */
    public static String commentsSuffix(String comments) {
        if (comments == null || comments.isBlank()) return "";
        String trimmed = comments.trim();
        return " Opmerking: " + trimmed + (trimmed.endsWith(".") ? "" : ".");
    }

    /**
     * Returns " bij {locationName}" or "" when the location is unknown.
     * Used to optionally append the clinic name to confirmation messages.
     */
    public static String locationSuffix(String locationName) {
        if (locationName == null || locationName.isBlank()) return "";
        return " bij " + locationName;
    }
}