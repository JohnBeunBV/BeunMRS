package com.openmrs.notification.model;

/**
 * Result returned by every provider adapter after a send attempt.
 */
public class NotificationResult {

    private final boolean success;
    private final String providerMessageId;
    private final String errorMessage;

    private NotificationResult(boolean success, String providerMessageId, String errorMessage) {
        this.success = success;
        this.providerMessageId = providerMessageId;
        this.errorMessage = errorMessage;
    }

    public static NotificationResult ok(String providerMessageId) {
        return new NotificationResult(true, providerMessageId, null);
    }

    public static NotificationResult failure(String errorMessage) {
        return new NotificationResult(false, null, errorMessage);
    }

    public boolean isSuccess() { return success; }
    public String getProviderMessageId() { return providerMessageId; }
    public String getErrorMessage() { return errorMessage; }
}
