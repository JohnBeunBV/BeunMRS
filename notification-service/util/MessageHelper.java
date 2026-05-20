package com.openmrs.notification.util;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Shared utilities for all notification provider adapters.
 *
 * formatTime      — converts an Instant to a human-readable Dutch string
 *                   in the Europe/Amsterdam timezone (incl. DST handling).
 * mask            — masks phone numbers and e-mail addresses for safe logging.
 * commentsSuffix  — formats the optional OpenMRS appointment comments field
 *                   as an appendable sentence.
 * location        — returns " bij {locationName}" or "" when absent.
 */
public final class MessageHelper {

    /** Configured timezone for all patient-facing timestamps. */
    private static final ZoneId ZONE = ZoneId.of("Europe/Amsterdam");

    /**
     * "maandag 24 januari 2026 om 14:30"
     */
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter
            .ofPattern("EEEE d MMMM yyyy 'om' HH:mm", Locale.of("nl", "NL"))
            .withZone(ZONE);

    private MessageHelper() { /* utility class */ }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Formats an Instant for display in patient messages.
     * Returns "onbekend tijdstip" for null inputs.
     *
     * Example output: "maandag 24 januari 2026 om 14:30"
     */
    public static String formatTime(Instant instant) {
        if (instant == null) return "onbekend tijdstip";
        return FORMATTER.format(instant);
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