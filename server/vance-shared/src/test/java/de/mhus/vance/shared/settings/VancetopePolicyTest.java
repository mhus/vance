package de.mhus.vance.shared.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.settings.SettingType;
import de.mhus.vance.shared.audit.AuditService;
import de.mhus.vance.shared.crypto.AesEncryptionService;
import de.mhus.vance.shared.home.HomeBootstrapService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * Pins the {@link VancetopePolicy} behaviour: the three protection layers
 * (hardcoded prefix, tenant deny list, project deny list), the cache refresh
 * after a meta-deny-key write, and the {@code force*} family's escape hatch.
 *
 * <p>This test stands alongside the existing
 * {@link AgentSettingKeyPolicyTest} and {@link SecretReferenceKeyPolicyTest}
 * — same grammar ({@link SettingKeyPatterns}), separate concerns.
 */
class VancetopePolicyTest {

    private static final String TENANT = "acme";

    private SettingRepository repository;
    private SettingService service;
    private VancetopePolicy policy;

    @BeforeEach
    void setUp() {
        repository = mock(SettingRepository.class);
        AesEncryptionService encryption = new AesEncryptionService("unit-test-master-key");
        // Two-step build: the policy needs the final service reference for its
        // cache loader, so the service gets the policy and the policy gets a
        // supplier that resolves to the same service instance once set.
        SettingService[] holder = new SettingService[1];
        org.springframework.beans.factory.ObjectProvider<SettingService> provider =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(provider.getObject()).thenAnswer(inv -> holder[0]);
        policy = new VancetopePolicy(provider);

        service = new SettingService(
                repository,
                mock(MongoTemplate.class),
                encryption,
                mock(AuditService.class),
                megadodoProvider(),
                new AgentSettingKeyPolicy(""),
                policy);
        holder[0] = service;
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @SuppressWarnings("unchecked")
    private static org.springframework.beans.factory.ObjectProvider<de.mhus.vance.shared.megadodo.MegadodoService>
            megadodoProvider() {
        org.springframework.beans.factory.ObjectProvider<de.mhus.vance.shared.megadodo.MegadodoService> p =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(p.getObject()).thenReturn(mock(de.mhus.vance.shared.megadodo.MegadodoService.class));
        return p;
    }

    private void stubDenyLists(String tenantList, String projectList) {
        // tenant list lives in _tenant project under vancetope.denied.tenant.
        when(repository.findByTenantIdAndReferenceTypeAndReferenceIdAndKey(
                        TENANT,
                        SettingService.SCOPE_PROJECT,
                        HomeBootstrapService.TENANT_PROJECT_NAME,
                        VancetopePolicy.KEY_DENIED_TENANT))
                .thenReturn(
                        tenantList == null
                                ? Optional.empty()
                                : Optional.of(SettingDocument.builder()
                                        .tenantId(TENANT)
                                        .referenceType(SettingService.SCOPE_PROJECT)
                                        .referenceId(HomeBootstrapService.TENANT_PROJECT_NAME)
                                        .key(VancetopePolicy.KEY_DENIED_TENANT)
                                        .value(tenantList)
                                        .type(SettingType.STRING)
                                        .build()));
        when(repository.findByTenantIdAndReferenceTypeAndReferenceIdAndKey(
                        TENANT,
                        SettingService.SCOPE_PROJECT,
                        HomeBootstrapService.TENANT_PROJECT_NAME,
                        VancetopePolicy.KEY_DENIED_PROJECT))
                .thenReturn(
                        projectList == null
                                ? Optional.empty()
                                : Optional.of(SettingDocument.builder()
                                        .tenantId(TENANT)
                                        .referenceType(SettingService.SCOPE_PROJECT)
                                        .referenceId(HomeBootstrapService.TENANT_PROJECT_NAME)
                                        .key(VancetopePolicy.KEY_DENIED_PROJECT)
                                        .value(projectList)
                                        .type(SettingType.STRING)
                                        .build()));
    }

    // ──────────────── hardcoded prefix ────────────────

    @Test
    void set_rejectsVancetopeKey_inTenantProject() {
        assertThatThrownBy(() -> policy.requireWritable(
                        TENANT,
                        SettingService.SCOPE_PROJECT,
                        HomeBootstrapService.TENANT_PROJECT_NAME,
                        "vancetope.foo"))
                .isInstanceOf(SecretAccessDeniedException.class)
                .hasMessageContaining("vancetope.foo")
                .hasMessageContaining("Reason")
                .hasMessageContaining("Resolution")
                .extracting(e -> ((SecretAccessDeniedException) e).getReason())
                .isEqualTo(SecretAccessDeniedException.Reason.WRITE_SYSTEM_NAMESPACE);
    }

    @Test
    void set_rejectsVancetopeKey_inUserProject() {
        assertThatThrownBy(() ->
                        policy.requireWritable(TENANT, SettingService.SCOPE_PROJECT, "user-project", "vancetope.foo"))
                .isInstanceOf(SecretAccessDeniedException.class);
    }

    @Test
    void set_allowsOrdinaryKeys() {
        stubDenyLists(null, null);
        // No exception expected.
        policy.requireWritable(TENANT, SettingService.SCOPE_PROJECT, "user-project", "chat.language");
        policy.requireWritable(
                TENANT, SettingService.SCOPE_PROJECT, HomeBootstrapService.TENANT_PROJECT_NAME, "smtp.host");
    }

    // ──────────────── tenant deny list ────────────────

    @Test
    void tenantDenyList_blocksMatchingKeysInTenantProject() {
        stubDenyLists("smtp.*,llm.*", null);

        assertThatThrownBy(() -> policy.requireWritable(
                        TENANT, SettingService.SCOPE_PROJECT, HomeBootstrapService.TENANT_PROJECT_NAME, "smtp.host"))
                .isInstanceOf(SecretAccessDeniedException.class)
                .extracting(e -> ((SecretAccessDeniedException) e).getReason())
                .isEqualTo(SecretAccessDeniedException.Reason.WRITE_TENANT_DENY_LIST);
    }

    @Test
    void tenantDenyList_doesNotBlockUserProjects() {
        stubDenyLists("smtp.*", null);

        // Same key, different scope — allowed because the tenant list only
        // applies in the _tenant project.
        policy.requireWritable(TENANT, SettingService.SCOPE_PROJECT, "user-project", "smtp.host");
    }

    // ──────────────── project deny list ────────────────

    @Test
    void projectDenyList_blocksMatchingKeysInUserProjects() {
        stubDenyLists(null, "internal.*");

        assertThatThrownBy(() ->
                        policy.requireWritable(TENANT, SettingService.SCOPE_PROJECT, "user-project", "internal.flag"))
                .isInstanceOf(SecretAccessDeniedException.class)
                .extracting(e -> ((SecretAccessDeniedException) e).getReason())
                .isEqualTo(SecretAccessDeniedException.Reason.WRITE_PROJECT_DENY_LIST);
    }

    @Test
    void projectDenyList_doesNotBlockTenantProject() {
        stubDenyLists(null, "internal.*");

        policy.requireWritable(
                TENANT, SettingService.SCOPE_PROJECT, HomeBootstrapService.TENANT_PROJECT_NAME, "internal.flag");
    }

    // ──────────────── force escape hatch ────────────────

    @Test
    void forceSet_allowsMetaDenyKey() {
        // The one thing forceSet accepts in the vancetope.* namespace: the
        // deny-list configuration keys themselves.
        policy.requireForceWritable(VancetopePolicy.KEY_DENIED_TENANT);
        policy.requireForceWritable(VancetopePolicy.KEY_DENIED_PROJECT);
        policy.requireForceWritable("vancetope.denied.custom");
    }

    @Test
    void forceSet_rejectsNonMetaSystemKey() {
        assertThatThrownBy(() -> policy.requireForceWritable("vancetope.foo"))
                .isInstanceOf(SecretAccessDeniedException.class)
                .extracting(e -> ((SecretAccessDeniedException) e).getReason())
                .isEqualTo(SecretAccessDeniedException.Reason.WRITE_FORCE_NON_META);
    }

    @Test
    void forceSet_allowsOrdinaryKeys() {
        policy.requireForceWritable("smtp.host");
        policy.requireForceWritable("ai.provider.openai.apiKey");
    }

    // ──────────────── read filter ────────────────

    @Test
    void isSystemKey_recognisesThePrefix() {
        assertThat(VancetopePolicy.isSystemKey("vancetope.foo")).isTrue();
        assertThat(VancetopePolicy.isSystemKey("vancetope.denied.tenant")).isTrue();
        assertThat(VancetopePolicy.isSystemKey("vancetope.")).isTrue();
        assertThat(VancetopePolicy.isSystemKey("smtp.host")).isFalse();
        assertThat(VancetopePolicy.isSystemKey(null)).isFalse();
        assertThat(VancetopePolicy.isSystemKey("")).isFalse();
    }

    @Test
    void isMetaDenyKey_recognisesTheSubPrefix() {
        assertThat(VancetopePolicy.isMetaDenyKey("vancetope.denied.tenant")).isTrue();
        assertThat(VancetopePolicy.isMetaDenyKey("vancetope.denied.x")).isTrue();
        assertThat(VancetopePolicy.isMetaDenyKey("vancetope.foo")).isFalse();
        assertThat(VancetopePolicy.isMetaDenyKey(null)).isFalse();
    }

    // ──────────────── cache refresh ────────────────

    @Test
    void onSettingChanged_invalidatesCacheForMetaDenyKeys() {
        stubDenyLists("first.*", null);

        // First call — loads "first.*".
        policy.tenantDenyPatterns(TENANT);
        assertThat(policy.tenantDenyPatterns(TENANT)).containsExactly("first.*");

        // Simulate a write that changed the deny list.
        stubDenyLists("second.*", null);
        // Without the notification, the cache would still return "first.*".
        assertThat(policy.tenantDenyPatterns(TENANT)).containsExactly("first.*");

        policy.onSettingChanged(TENANT, VancetopePolicy.KEY_DENIED_TENANT);

        // After refresh, the new list is loaded.
        assertThat(policy.tenantDenyPatterns(TENANT)).containsExactly("second.*");
    }

    @Test
    void onSettingChanged_ignoresNonMetaKeys() {
        stubDenyLists("first.*", null);
        policy.tenantDenyPatterns(TENANT);

        // A non-meta key change does not invalidate the cache.
        policy.onSettingChanged(TENANT, "vancetope.foo");
        policy.onSettingChanged(TENANT, "smtp.host");
        assertThat(policy.tenantDenyPatterns(TENANT)).containsExactly("first.*");
    }

    // ──────────────── per-tenant isolation ────────────────

    @Test
    void denyLists_areCachedPerTenant() {
        // Tenant A: has a deny list.
        when(repository.findByTenantIdAndReferenceTypeAndReferenceIdAndKey(
                        "tenant-a",
                        SettingService.SCOPE_PROJECT,
                        HomeBootstrapService.TENANT_PROJECT_NAME,
                        VancetopePolicy.KEY_DENIED_TENANT))
                .thenReturn(Optional.of(SettingDocument.builder()
                        .tenantId("tenant-a")
                        .referenceType(SettingService.SCOPE_PROJECT)
                        .referenceId(HomeBootstrapService.TENANT_PROJECT_NAME)
                        .key(VancetopePolicy.KEY_DENIED_TENANT)
                        .value("a.*")
                        .type(SettingType.STRING)
                        .build()));
        when(repository.findByTenantIdAndReferenceTypeAndReferenceIdAndKey(
                        "tenant-a",
                        SettingService.SCOPE_PROJECT,
                        HomeBootstrapService.TENANT_PROJECT_NAME,
                        VancetopePolicy.KEY_DENIED_PROJECT))
                .thenReturn(Optional.empty());
        // Tenant B: no deny list.
        when(repository.findByTenantIdAndReferenceTypeAndReferenceIdAndKey(
                        "tenant-b",
                        SettingService.SCOPE_PROJECT,
                        HomeBootstrapService.TENANT_PROJECT_NAME,
                        VancetopePolicy.KEY_DENIED_TENANT))
                .thenReturn(Optional.empty());
        when(repository.findByTenantIdAndReferenceTypeAndReferenceIdAndKey(
                        "tenant-b",
                        SettingService.SCOPE_PROJECT,
                        HomeBootstrapService.TENANT_PROJECT_NAME,
                        VancetopePolicy.KEY_DENIED_PROJECT))
                .thenReturn(Optional.empty());

        assertThat(policy.tenantDenyPatterns("tenant-a")).containsExactly("a.*");
        assertThat(policy.tenantDenyPatterns("tenant-b")).isEmpty();
        // Tenant A's list survives a tenant-B lookup.
        assertThat(policy.tenantDenyPatterns("tenant-a")).containsExactly("a.*");
    }

    @Test
    void refresh_clearsCacheForTheTenant() {
        stubDenyLists("first.*", null);
        policy.tenantDenyPatterns(TENANT);

        policy.refresh(TENANT);
        // Next load must consult the repository again — the mock still
        // returns "first.*", but the test pins that the cache was cleared
        // (otherwise we'd never re-query).
        assertThat(policy.tenantDenyPatterns(TENANT)).containsExactly("first.*");
    }

    // ──────────────── reading the lists (anus-side diagnostics) ────────────────

    @Test
    void tenantDenyPatterns_returnsConfiguredList() {
        stubDenyLists("quota.*,other.*", null);
        assertThat(policy.tenantDenyPatterns(TENANT)).containsExactly("quota.*", "other.*");
    }

    @Test
    void projectDenyPatterns_returnsConfiguredList() {
        stubDenyLists(null, "internal.*,restricted.*");
        assertThat(policy.projectDenyPatterns(TENANT)).containsExactly("internal.*", "restricted.*");
    }

    @Test
    void defaultLists_areEmptyWhenNoSettingExists() {
        stubDenyLists(null, null);
        assertThat(policy.tenantDenyPatterns(TENANT)).isEmpty();
        assertThat(policy.projectDenyPatterns(TENANT)).isEmpty();
    }

    // ──────────────── unhelpful input handling ────────────────

    @Test
    void denyListWhitespace_isTolerated() {
        stubDenyLists("  quota.* ,,  other.*  ", null);
        assertThat(policy.tenantDenyPatterns(TENANT)).containsExactly("quota.*", "other.*");
    }

    @Test
    void denyListExactMatches_work() {
        stubDenyLists("specific.setting", null);

        assertThatThrownBy(() -> policy.requireWritable(
                        TENANT,
                        SettingService.SCOPE_PROJECT,
                        HomeBootstrapService.TENANT_PROJECT_NAME,
                        "specific.setting"))
                .isInstanceOf(SecretAccessDeniedException.class);
    }
}
