package com.openmrs.notification.tenant;

import java.util.UUID;

public record TenantRegistrationResponse(
        UUID   tenantId,
        String slug,
        String displayName,
        String apiKey
) {}
