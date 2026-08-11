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
 * The reference-readability gate: {@code getReferenceSecret*} is the read path
 * behind an authored {@code {{secret:…}}} reference (tool documents, compose
 * manifests, scripts — all agent-writable), so it hands out
 * {@link SettingType#HIDDEN} values and refuses {@link SettingType#PASSWORD}
 * ones. {@code getDecryptedPassword}, used by compiled callers with a fixed key,
 * keeps reading both — that asymmetry is the whole point and is asserted here.
 */
class SettingServiceReferenceSecretTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = SettingService.SCOPE_PROJECT;
    private static final String TP = SettingService.SCOPE_THINK_PROCESS;
    private static final String PROJ = "instant-hole";
    private static final String PROCESS = "p-1";
    private static final String USER = "wile.coyote";
    private static final String TENANT_PROJ = HomeBootstrapService.TENANT_PROJECT_NAME;
    private static final String KEY = "api.key";

    private SettingRepository repository;
    private AesEncryptionService encryption;
    private SettingService service;

    @BeforeEach
    void setUp() {
        repository = mock(SettingRepository.class);
        encryption = new AesEncryptionService("unit-test-master-key");
        service = new SettingService(repository, mock(MongoTemplate.class),
                encryption, mock(AuditService.class));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    // ─────── single scope ───────

    @Test
    void hidden_setting_resolves_through_a_reference() {
        stub(PROJECT, PROJ, KEY, secret(SettingType.HIDDEN, "s3cr3t"));

        assertThat(service.getReferenceSecret(TENANT, PROJECT, PROJ, KEY)).isEqualTo("s3cr3t");
    }

    @Test
    void password_setting_is_refused_with_a_named_failure() {
        stub(PROJECT, PROJ, KEY, secret(SettingType.PASSWORD, "s3cr3t"));

        assertThatThrownBy(() -> service.getReferenceSecret(TENANT, PROJECT, PROJ, KEY))
                .isInstanceOf(SecretAccessDeniedException.class)
                .hasMessageContaining(KEY)
                .hasMessageContaining("HIDDEN");
    }

    @Test
    void compiled_read_path_still_reads_the_same_password_setting() {
        stub(PROJECT, PROJ, KEY, secret(SettingType.PASSWORD, "s3cr3t"));

        // The gate is on the reference path only — a provider API key must stay
        // readable for ChatBehaviorBuilder & friends.
        assertThat(service.getDecryptedPassword(TENANT, PROJECT, PROJ, KEY)).isEqualTo("s3cr3t");
    }

    @Test
    void missing_setting_returns_null_rather_than_throwing() {
        stub(PROJECT, PROJ, KEY, null);

        assertThat(service.getReferenceSecret(TENANT, PROJECT, PROJ, KEY)).isNull();
    }

    @Test
    void plain_typed_setting_returns_null() {
        stub(PROJECT, PROJ, KEY, doc(SettingType.STRING, "not-a-secret"));

        assertThat(service.getReferenceSecret(TENANT, PROJECT, PROJ, KEY)).isNull();
    }

    // ─────── cascade ───────

    @Test
    void cascade_returns_the_innermost_hidden_layer() {
        stub(TP, PROCESS, KEY, secret(SettingType.HIDDEN, "from-process"));
        stub(PROJECT, PROJ, KEY, secret(SettingType.HIDDEN, "from-project"));

        assertThat(service.getReferenceSecretCascade(TENANT, PROJ, PROCESS, KEY))
                .isEqualTo("from-process");
    }

    @Test
    void cascade_walks_outward_past_a_layer_that_does_not_carry_the_key() {
        stub(TP, PROCESS, KEY, null);
        stub(PROJECT, PROJ, KEY, null);
        stub(PROJECT, TENANT_PROJ, KEY, secret(SettingType.HIDDEN, "from-tenant"));

        assertThat(service.getReferenceSecretCascade(TENANT, PROJ, PROCESS, KEY))
                .isEqualTo("from-tenant");
    }

    @Test
    void cascade_stops_at_a_password_layer_instead_of_falling_through_to_an_outer_hidden() {
        // "Innermost wins" is the cascade's contract. If the inner layer binds the
        // key as PASSWORD, that IS the configured value — silently resolving the
        // outer HIDDEN would hand out a different secret than the one configured.
        stub(TP, PROCESS, KEY, null);
        stub(PROJECT, PROJ, KEY, secret(SettingType.PASSWORD, "inner-real-secret"));
        stub(PROJECT, TENANT_PROJ, KEY, secret(SettingType.HIDDEN, "outer-shared"));

        assertThatThrownBy(() -> service.getReferenceSecretCascade(TENANT, PROJ, PROCESS, KEY))
                .isInstanceOf(SecretAccessDeniedException.class);
    }

    @Test
    void cascade_skips_a_plain_typed_inner_layer_and_keeps_walking() {
        stub(TP, PROCESS, KEY, null);
        stub(PROJECT, PROJ, KEY, doc(SettingType.STRING, "plain"));
        stub(PROJECT, TENANT_PROJ, KEY, secret(SettingType.HIDDEN, "from-tenant"));

        assertThat(service.getReferenceSecretCascade(TENANT, PROJ, PROCESS, KEY))
                .isEqualTo("from-tenant");
    }

    // ─────── user scope ───────

    @Test
    void user_scope_reads_the_hub_project() {
        stub(PROJECT, HomeBootstrapService.HUB_PROJECT_NAME_PREFIX + USER, KEY,
                secret(SettingType.HIDDEN, "user-secret"));

        assertThat(service.getReferenceUserSecret(TENANT, USER, KEY)).isEqualTo("user-secret");
    }

    @Test
    void user_scope_refuses_a_password_setting() {
        stub(PROJECT, HomeBootstrapService.HUB_PROJECT_NAME_PREFIX + USER, KEY,
                secret(SettingType.PASSWORD, "user-secret"));

        assertThatThrownBy(() -> service.getReferenceUserSecret(TENANT, USER, KEY))
                .isInstanceOf(SecretAccessDeniedException.class);
    }

    @Test
    void user_scope_without_user_id_returns_null() {
        assertThat(service.getReferenceUserSecret(TENANT, null, KEY)).isNull();
        assertThat(service.getReferenceUserSecret(TENANT, "  ", KEY)).isNull();
    }

    // ─────── helpers ───────

    private SettingDocument secret(SettingType type, String plaintext) {
        return doc(type, encryption.encrypt(plaintext));
    }

    private SettingDocument doc(SettingType type, @org.jspecify.annotations.Nullable String value) {
        SettingDocument d = new SettingDocument();
        d.setTenantId(TENANT);
        d.setKey(KEY);
        d.setType(type);
        d.setValue(value);
        return d;
    }

    private void stub(String referenceType, String referenceId, String key,
            @org.jspecify.annotations.Nullable SettingDocument doc) {
        when(repository.findByTenantIdAndReferenceTypeAndReferenceIdAndKey(
                TENANT, referenceType, referenceId, key))
                .thenReturn(Optional.ofNullable(doc));
    }
}
