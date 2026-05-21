package com.openmrs.notification.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * 6j — AesEncryptionService: encrypt/decrypt round-trip + null-handling.
 * No Spring context needed — pure unit test.
 */
class AesEncryptionServiceTest {

    private final AesEncryptionService aes = new AesEncryptionService(null); // uses dev fallback key

    @Test
    void roundTrip_plaintext() {
        String plain = "geheimWachtwoord123!";
        String encrypted = aes.encrypt(plain);

        assertThat(encrypted).isNotNull().isNotEqualTo(plain);
        assertThat(aes.decrypt(encrypted)).isEqualTo(plain);
    }

    @Test
    void encryptProducesDifferentCiphertextEachTime() {
        // GCM uses a random IV → same plaintext → different ciphertext
        String plain = "zelfde tekst";
        String enc1  = aes.encrypt(plain);
        String enc2  = aes.encrypt(plain);

        assertThat(enc1).isNotEqualTo(enc2);
        assertThat(aes.decrypt(enc1)).isEqualTo(plain);
        assertThat(aes.decrypt(enc2)).isEqualTo(plain);
    }

    @Test
    void encryptNull_returnsNull() {
        assertThat(aes.encrypt(null)).isNull();
    }

    @Test
    void decryptNull_returnsNull() {
        assertThat(aes.decrypt(null)).isNull();
    }

    @Test
    void roundTrip_unicodeAndSpecialChars() {
        String plain = "مرحبا 你好 Héllo <>&\"";
        assertThat(aes.decrypt(aes.encrypt(plain))).isEqualTo(plain);
    }

    @Test
    void decryptTamperedCiphertext_throwsException() {
        String encrypted = aes.encrypt("geheim");
        String tampered  = encrypted.substring(0, encrypted.length() - 4) + "XXXX";

        assertThatThrownBy(() -> aes.decrypt(tampered))
                .isInstanceOf(RuntimeException.class);
    }
}
