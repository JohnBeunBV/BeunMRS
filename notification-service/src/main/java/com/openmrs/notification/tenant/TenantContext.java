package com.openmrs.notification.tenant;

/**
 * Thread-local holder for the current tenant.
 *
 * Set by TenantApiKeyFilter for HTTP requests, and explicitly by the
 * scheduled pollers for each tenant iteration.
 * Always call clear() in a finally block to prevent context leaks.
 */
public final class TenantContext {

    private static final ThreadLocal<Tenant> HOLDER = new ThreadLocal<>();

    private TenantContext() {}

    public static void set(Tenant tenant) { HOLDER.set(tenant); }
    public static Tenant get()            { return HOLDER.get(); }
    public static void clear()            { HOLDER.remove(); }
}
