package com.openmrs.notification.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM encryption for credentials stored in the database.
 *
 * Key is loaded from DB_ENCRYPTION_KEY env var (base64-encoded 32 bytes).
 * In development, a hardcoded fallback key is used — never use this in production.
 *
 * Format: Base64(IV[12] + ciphertext + GCM-tag[16])
 */
@Service
public class AesEncryptionService {

    private static final String ALGORITHM    = "AES/GCM/NoPadding";
    private static final int    IV_LENGTH    = 12;
    private static final int    TAG_BITS     = 128;

    private final byte[] keyBytes;

    public AesEncryptionService(
            @Value("${encryption.key:#{null}}") String base64Key) {
        if (base64Key != null && !base64Key.isBlank()) {
            this.keyBytes = Base64.getDecoder().decode(base64Key);
        } else {
            // Dev fallback — 32 ASCII bytes = 256-bit key
            this.keyBytes = "BeunMRS-DevKey01BeunMRS-DevKey01"
                    .getBytes(StandardCharsets.UTF_8);
        }
    }

    public String encrypt(String plaintext) {
        if (plaintext == null) return null;
        try {
            byte[] iv = new byte[IV_LENGTH];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(keyBytes, "AES"),
                    new GCMParameterSpec(TAG_BITS, iv));

            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            // Prepend IV so decrypt knows it
            byte[] combined = new byte[IV_LENGTH + ciphertext.length];
            System.arraycopy(iv,         0, combined, 0,         IV_LENGTH);
            System.arraycopy(ciphertext, 0, combined, IV_LENGTH, ciphertext.length);

            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new RuntimeException("Encryption failed", e);
        }
    }

    public String decrypt(String base64Ciphertext) {
        if (base64Ciphertext == null) return null;
        try {
            byte[] combined    = Base64.getDecoder().decode(base64Ciphertext);
            byte[] iv          = new byte[IV_LENGTH];
            byte[] ciphertext  = new byte[combined.length - IV_LENGTH];
            System.arraycopy(combined, 0,         iv,         0, IV_LENGTH);
            System.arraycopy(combined, IV_LENGTH, ciphertext, 0, ciphertext.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(keyBytes, "AES"),
                    new GCMParameterSpec(TAG_BITS, iv));

            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Decryption failed", e);
        }
    }
}
