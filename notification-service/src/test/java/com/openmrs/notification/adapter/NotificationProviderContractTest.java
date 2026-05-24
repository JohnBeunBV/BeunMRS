package com.openmrs.notification.adapter;

import com.openmrs.notification.model.AppointmentEvent;
import com.openmrs.notification.model.NotificationChannel;
import com.openmrs.notification.model.NotificationResult;
import com.openmrs.notification.model.ProviderCredentials;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.mockito.Mockito;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.stereotype.Component;

import java.lang.reflect.Constructor;
import java.lang.reflect.Parameter;
import java.util.*;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract test that validates the {@link NotificationProvider} extension point
 * for every implementation discovered on the classpath.
 *
 * <p>Proves architectural uitbreidbaarheid (NFR-12 + opdracht "toekomstbestendig"):
 * adding a new provider is genuinely plug-and-play — the new class is auto-discovered
 * by Spring, its providerName must be unique, and production providers must match
 * the database CHECK constraint in <code>tenants.provider_name</code>.</p>
 *
 * <p>This is the test the testrapport's hoofdstuk 4 ("Uitbreidbaarheid") was missing:
 * a guard that fails the build if a future developer breaks the contract — instead
 * of relying on the prose claim that the demo would work.</p>
 */
class NotificationProviderContractTest {

    /** Allowed production provider names per <code>00_schema.sql</code> CHECK constraint. */
    private static final Set<String> ALLOWED_DB_PROVIDER_NAMES =
            Set.of("SwiftSend", "SecurePost", "LegacyLink", "AsyncFlow");

    /** Names that exist in code but are NOT allowed as a tenant provider (test/dev only). */
    private static final Set<String> NON_PRODUCTION_PROVIDER_NAMES =
            Set.of("mock-messaging");

    private static final String ADAPTER_BASE_PACKAGE = "com.openmrs.notification.adapter";

    // ── Discovery ────────────────────────────────────────────────────────────

    private static List<Class<?>> discoverProviderClasses() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(NotificationProvider.class));

        List<Class<?>> classes = new ArrayList<>();
        for (BeanDefinition def : scanner.findCandidateComponents(ADAPTER_BASE_PACKAGE)) {
            Class<?> c = loadClass(def.getBeanClassName());
            if (!c.isInterface()) classes.add(c);
        }
        return classes;
    }

    private static Class<?> loadClass(String name) {
        try { return Class.forName(name); }
        catch (ClassNotFoundException e) { throw new IllegalStateException(e); }
    }

    // ── Tests ────────────────────────────────────────────────────────────────

    @Test
    void allRequiredProductionProvidersArePresent() {
        Set<String> discoveredNames = discoverProviderClasses().stream()
                .map(this::instantiateAndGetName)
                .collect(java.util.stream.Collectors.toSet());

        // NFR-3: SwiftSend, SecurePost, LegacyLink, AsyncFlow must all be present
        assertThat(discoveredNames)
                .as("All four required production providers must be discoverable via Spring component scan")
                .containsAll(ALLOWED_DB_PROVIDER_NAMES);
    }

    @Test
    void allProvidersAreSpringComponents() {
        // Implicit promise of "add one class, zero other changes": new providers
        // must be @Component so Spring picks them up without an XML/config edit.
        for (Class<?> providerClass : discoverProviderClasses()) {
            assertThat(providerClass.isAnnotationPresent(Component.class))
                    .as("%s must be annotated with @Component to be auto-discovered",
                            providerClass.getSimpleName())
                    .isTrue();
        }
    }

    @Test
    void providerNamesAreUnique() {
        List<String> names = discoverProviderClasses().stream()
                .map(this::instantiateAndGetName)
                .toList();

        // Duplicate names would silently shadow each other in NotificationDispatcher's
        // .filter(p -> p.providerName().equalsIgnoreCase(target)).findFirst()
        assertThat(names)
                .as("Provider names must be unique — otherwise dispatcher routing is ambiguous")
                .doesNotHaveDuplicates();
    }

    @Test
    void productionProviderNamesMatchDatabaseCheckConstraint() {
        for (Class<?> providerClass : discoverProviderClasses()) {
            String name = instantiateAndGetName(providerClass);
            if (NON_PRODUCTION_PROVIDER_NAMES.contains(name)) continue;

            assertThat(ALLOWED_DB_PROVIDER_NAMES)
                    .as("Provider %s reports name '%s' but the tenants.provider_name "
                      + "CHECK constraint in 00_schema.sql does not allow it. "
                      + "Either update the schema or rename the provider.",
                            providerClass.getSimpleName(), name)
                    .contains(name);
        }
    }

    @TestFactory
    Stream<DynamicTest> everyProviderHandlesNullPhoneGracefully() {
        // The 'patient has no phone' edge case is part of the contract: providers
        // must NEVER throw on a missing phone — they return a failure NotificationResult
        // so the dispatcher can log and move on. This guards against a new provider
        // forgetting the null-check and bringing the consumer thread down.
        return discoverProviderClasses().stream().map(providerClass ->
                DynamicTest.dynamicTest(providerClass.getSimpleName() + ".send(nullPhone) returns failure", () -> {
                    NotificationProvider provider = (NotificationProvider) instantiate(providerClass);
                    AppointmentEvent event = new AppointmentEvent();
                    event.setAppointmentUuid("appt-contract-1");
                    event.setPatientUuid("patient-contract-1");
                    event.setEventType(AppointmentEvent.EventType.SCHEDULED);
                    event.setPatientPhone(null);

                    NotificationResult result = provider.send(event, new ProviderCredentials("k", null));

                    assertThat(result).isNotNull();
                    assertThat(result.isSuccess())
                            .as("%s must return failure (not throw) when patientPhone is null",
                                    providerClass.getSimpleName())
                            .isFalse();
                })
        );
    }

    @Test
    void everyProviderDeclaresChannel() {
        for (Class<?> providerClass : discoverProviderClasses()) {
            NotificationProvider provider = (NotificationProvider) instantiate(providerClass);
            NotificationChannel channel = provider.channel();
            assertThat(channel)
                    .as("%s.channel() must not be null", providerClass.getSimpleName())
                    .isNotNull();
        }
    }

    // ── Reflection helpers ───────────────────────────────────────────────────

    private String instantiateAndGetName(Class<?> providerClass) {
        NotificationProvider p = (NotificationProvider) instantiate(providerClass);
        String name = p.providerName();
        assertThat(name)
                .as("%s.providerName() must not be null or blank", providerClass.getSimpleName())
                .isNotNull().isNotBlank();
        return name;
    }

    /**
     * Instantiates a provider via its first constructor, supplying Mockito mocks
     * (or sensible defaults) for each parameter. This works because the contract
     * methods we exercise (providerName/channel/send-with-null-phone) never actually
     * touch the injected RestTemplate / JdbcTemplate.
     */
    private Object instantiate(Class<?> providerClass) {
        Constructor<?> ctor = pickConstructor(providerClass);
        ctor.setAccessible(true);
        Parameter[] params = ctor.getParameters();
        Object[] args = new Object[params.length];
        for (int i = 0; i < params.length; i++) {
            args[i] = mockArg(params[i].getType());
        }
        try {
            return ctor.newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(
                    "Could not instantiate " + providerClass.getSimpleName()
                  + " via reflection — contract test expects the constructor to be "
                  + "injectable with mocks (this is what Spring does at runtime).", e);
        }
    }

    private Constructor<?> pickConstructor(Class<?> providerClass) {
        // Prefer the constructor with the most parameters (Spring's @Autowired choice).
        return Arrays.stream(providerClass.getDeclaredConstructors())
                .max(Comparator.comparingInt(Constructor::getParameterCount))
                .orElseThrow(() -> new AssertionError(
                        "No constructors on " + providerClass.getName()));
    }

    private Object mockArg(Class<?> type) {
        if (type == String.class)  return "test-value";
        if (type == boolean.class) return Boolean.TRUE;
        if (type == int.class)     return 0;
        if (type == long.class)    return 0L;
        if (type.isPrimitive())    return 0;
        // For everything else (RestTemplate, JdbcTemplate, TenantService, ...) → Mockito mock
        return Mockito.mock(type);
    }
}
