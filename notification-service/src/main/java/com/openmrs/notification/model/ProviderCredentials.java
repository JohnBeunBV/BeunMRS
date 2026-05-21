package com.openmrs.notification.model;

/**
 * Runtime credentials for a messaging provider, decrypted from the tenant record.
 * apiKey  — primary credential (X-API-KEY for SwiftSend/AsyncFlow; clientId for SecurePost; username for LegacyLink)
 * extra   — secondary credential (null for SwiftSend/AsyncFlow; clientSecret for SecurePost; password for LegacyLink)
 */
public record ProviderCredentials(String apiKey, String extra) {

    public static ProviderCredentials of(String apiKey) {
        return new ProviderCredentials(apiKey, null);
    }

    public static ProviderCredentials of(String apiKey, String extra) {
        return new ProviderCredentials(apiKey, extra);
    }
}
