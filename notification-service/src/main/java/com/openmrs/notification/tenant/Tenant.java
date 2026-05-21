package com.openmrs.notification.tenant;

import java.time.Instant;
import java.util.UUID;

public class Tenant {

    private UUID    id;
    private String  slug;
    private String  displayName;
    private String  apiKeyHash;
    private String  apiKeyEnc;
    private String  openmrsHost;
    private String  openmrsUser;
    private String  openmrsPasswordEnc;
    private String  providerName;
    private String  providerApiKeyEnc;
    private String  providerExtraEnc;
    private boolean active;
    private Instant createdAt;

    public UUID    getId()                  { return id; }
    public void    setId(UUID id)           { this.id = id; }

    public String  getSlug()                { return slug; }
    public void    setSlug(String slug)     { this.slug = slug; }

    public String  getDisplayName()                      { return displayName; }
    public void    setDisplayName(String displayName)    { this.displayName = displayName; }

    public String  getApiKeyHash()                       { return apiKeyHash; }
    public void    setApiKeyHash(String apiKeyHash)      { this.apiKeyHash = apiKeyHash; }

    public String  getApiKeyEnc()                        { return apiKeyEnc; }
    public void    setApiKeyEnc(String apiKeyEnc)        { this.apiKeyEnc = apiKeyEnc; }

    public String  getOpenmrsHost()                      { return openmrsHost; }
    public void    setOpenmrsHost(String openmrsHost)    { this.openmrsHost = openmrsHost; }

    public String  getOpenmrsUser()                      { return openmrsUser; }
    public void    setOpenmrsUser(String openmrsUser)    { this.openmrsUser = openmrsUser; }

    public String  getOpenmrsPasswordEnc()                           { return openmrsPasswordEnc; }
    public void    setOpenmrsPasswordEnc(String openmrsPasswordEnc) { this.openmrsPasswordEnc = openmrsPasswordEnc; }

    public String  getProviderName()                         { return providerName; }
    public void    setProviderName(String providerName)      { this.providerName = providerName; }

    public String  getProviderApiKeyEnc()                            { return providerApiKeyEnc; }
    public void    setProviderApiKeyEnc(String providerApiKeyEnc)   { this.providerApiKeyEnc = providerApiKeyEnc; }

    public String  getProviderExtraEnc()                             { return providerExtraEnc; }
    public void    setProviderExtraEnc(String providerExtraEnc)     { this.providerExtraEnc = providerExtraEnc; }

    public boolean isActive()                  { return active; }
    public void    setActive(boolean active)   { this.active = active; }

    public Instant getCreatedAt()                        { return createdAt; }
    public void    setCreatedAt(Instant createdAt)       { this.createdAt = createdAt; }
}
