package com.openmrs.notification.tenant;

public record TenantRegistrationRequest(
        String slug,
        String displayName,
        String openmrsHost,
        String openmrsUser,
        String openmrsPassword,
        String providerName,
        String providerApiKey,
        String providerExtra
) {}
