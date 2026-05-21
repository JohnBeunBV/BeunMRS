package com.openmrs.notification.util;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.*;

/**
 * MessageHelper: mask, formatTime, commentsSuffix, locationSuffix.
 * Pure unit test — no Spring context.
 */
class MessageHelperTest {

    // ── mask ──────────────────────────────────────────────────────────────────

    @Test
    void mask_phone_keepsFirstAndLastThree() {
        assertThat(MessageHelper.mask("0612345678")).isEqualTo("061****678");
    }

    @Test
    void mask_email_keepsFirstCharAndDomain() {
        assertThat(MessageHelper.mask("betty@example.com")).isEqualTo("b****@example.com");
    }

    @Test
    void mask_null_returnsNullLiteral() {
        assertThat(MessageHelper.mask(null)).isEqualTo("<null>");
    }

    @Test
    void mask_shortValue_returnsFourStars() {
        assertThat(MessageHelper.mask("abc")).isEqualTo("****");
    }

    @Test
    void mask_emailAtFirstPosition_stillMasks() {
        assertThat(MessageHelper.mask("@domain.com")).isEqualTo("****@domain.com");
    }

    // ── formatTime ────────────────────────────────────────────────────────────

    @Test
    void formatTime_nullInstant_returnsOnbekend() {
        assertThat(MessageHelper.formatTime(null, "Europe/Amsterdam"))
                .isEqualTo("onbekend tijdstip");
    }

    @Test
    void formatTime_validInstantAndTimezone_containsYear() {
        // 2026-05-22T10:00:00Z
        Instant instant = Instant.parse("2026-05-22T10:00:00Z");
        String result   = MessageHelper.formatTime(instant, "Europe/Amsterdam");

        assertThat(result).contains("2026").contains("12:00"); // UTC+2 in summer
    }

    @Test
    void formatTime_differentTimezone_adjustsHour() {
        // Singapore is UTC+8: 10:00Z → 18:00 SGT
        Instant instant = Instant.parse("2026-05-22T10:00:00Z");
        String amsterdam = MessageHelper.formatTime(instant, "Europe/Amsterdam");
        String singapore = MessageHelper.formatTime(instant, "Asia/Singapore");

        assertThat(amsterdam).contains("12:00"); // CEST
        assertThat(singapore).contains("18:00"); // SGT
    }

    @Test
    void formatTime_invalidTimezone_fallsBackToAmsterdam() {
        Instant instant   = Instant.parse("2026-05-22T10:00:00Z");
        String withInvalid = MessageHelper.formatTime(instant, "Nergens/Onbekend");
        String withNull    = MessageHelper.formatTime(instant, null);

        // Both should produce the same Europe/Amsterdam result
        assertThat(withInvalid).isEqualTo(withNull);
    }

    // ── commentsSuffix ────────────────────────────────────────────────────────

    @Test
    void commentsSuffix_withContent_formatsCorrectly() {
        assertThat(MessageHelper.commentsSuffix("Nuchter komen"))
                .isEqualTo(" Opmerking: Nuchter komen.");
    }

    @Test
    void commentsSuffix_alreadyHasDot_doesNotDouble() {
        assertThat(MessageHelper.commentsSuffix("Paspoort meenemen."))
                .isEqualTo(" Opmerking: Paspoort meenemen.");
    }

    @Test
    void commentsSuffix_null_returnsEmpty() {
        assertThat(MessageHelper.commentsSuffix(null)).isEmpty();
    }

    @Test
    void commentsSuffix_blank_returnsEmpty() {
        assertThat(MessageHelper.commentsSuffix("   ")).isEmpty();
    }

    // ── locationSuffix ────────────────────────────────────────────────────────

    @Test
    void locationSuffix_withName_formatsCorrectly() {
        assertThat(MessageHelper.locationSuffix("Polikliniek Noord"))
                .isEqualTo(" bij Polikliniek Noord");
    }

    @Test
    void locationSuffix_null_returnsEmpty() {
        assertThat(MessageHelper.locationSuffix(null)).isEmpty();
    }

    @Test
    void locationSuffix_blank_returnsEmpty() {
        assertThat(MessageHelper.locationSuffix("  ")).isEmpty();
    }
}
