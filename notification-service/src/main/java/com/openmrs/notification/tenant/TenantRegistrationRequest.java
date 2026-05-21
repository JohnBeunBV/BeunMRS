package com.openmrs.notification.tenant;

public record TenantRegistrationRequest(
        String slug,
        String displayName,
        String openmrsHost,
        String openmrsUser,
        String openmrsPassword,
        String providerName,
        String providerApiKey,
        String providerExtra,
        /** IANA timezone, e.g. "Europe/Amsterdam". Defaults to "Europe/Amsterdam" when omitted. */
        String timezone
) {}
