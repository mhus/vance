package de.mhus.vance.shared.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.settings.SettingType;
import de.mhus.vance.shared.audit.AuditService;
import de.mhus.vance.shared.crypto.AesEncryptionService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * Pins the {@link SettingType#encrypted()} contract across <em>every</em> enum
 * constant rather than the two we happen to know today.
 *
 * <p>Rationale (see {@code planning/setting-type-hidden.md} §4.1): the
 * PASSWORD/HIDDEN split turned ~19 {@code == SettingType.PASSWORD} comparisons
 * into {@code type.encrypted()} calls. A single missed one in a write path would
 * let an encrypted value be persisted in cleartext — a worse failure than the
 * leak the split closes. Iterating the enum means a future third encrypted type
 * is covered by these tests the moment it is added to {@code encrypted()}.
 */
class SettingTypeEncryptedContractTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = SettingService.SCOPE_PROJECT;
    private static final String REF = "proj";

    private SettingRepository repository;
    private SettingService service;

    @BeforeEach
    void setUp() {
        repository = mock(SettingRepository.class);
        service = new SettingService(repository, mock(MongoTemplate.class),
                new AesEncryptionService("unit-test-master-key"), mock(AuditService.class));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    // ──────────────── write path: encrypted types never reach set() ────────────────

    @ParameterizedTest
    @EnumSource(SettingType.class)
    void set_rejectsEveryEncryptedType(SettingType type) {
        if (!type.encrypted()) return;

        assertThatThrownBy(() -> service.set(
                TENANT, PROJECT, REF, "some.key", "value", type, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("setEncryptedSecret");
    }

    @ParameterizedTest
    @EnumSource(SettingType.class)
    void set_acceptsEveryPlainType(SettingType type) {
        if (type.encrypted()) return;

        SettingDocument saved = service.set(
                TENANT, PROJECT, REF, "some.key", "value", type, null);

        assertThat(saved.getType()).isEqualTo(type);
        assertThat(saved.getValue()).isEqualTo("value");
    }

    @ParameterizedTest
    @EnumSource(SettingType.class)
    void setEncryptedSecret_rejectsEveryPlainType(SettingType type) {
        if (type.encrypted()) return;

        assertThatThrownBy(() -> service.setEncryptedSecret(
                TENANT, PROJECT, REF, "some.key", "value", type))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires an encrypted type");
    }

    @ParameterizedTest
    @EnumSource(SettingType.class)
    void setEncryptedSecret_storesCiphertextAndRoundTripsForEveryEncryptedType(SettingType type) {
        if (!type.encrypted()) return;

        SettingDocument saved = service.setEncryptedSecret(
                TENANT, PROJECT, REF, "api.key", "s3cr3t", type);

        assertThat(saved.getType()).isEqualTo(type);
        assertThat(saved.getValue()).isNotNull().isNotEqualTo("s3cr3t");

        stubFind(saved);
        assertThat(service.getDecryptedPassword(TENANT, PROJECT, REF, "api.key"))
                .isEqualTo("s3cr3t");
    }

    // ──────────────── read paths: no encrypted type leaks as a string ────────────────

    @ParameterizedTest
    @EnumSource(SettingType.class)
    void getStringValue_refusesEveryEncryptedType(SettingType type) {
        if (!type.encrypted()) return;

        stubFind(service.setEncryptedSecret(
                TENANT, PROJECT, REF, "api.key", "s3cr3t", type));

        assertThat(service.getStringValue(TENANT, PROJECT, REF, "api.key")).isNull();
    }

    @ParameterizedTest
    @EnumSource(SettingType.class)
    void prefixRead_skipsEveryEncryptedType(SettingType type) {
        if (!type.encrypted()) return;

        SettingDocument secret = service.setEncryptedSecret(
                TENANT, PROJECT, REF, "ai.provider.x.apiKey", "s3cr3t", type);
        SettingDocument plain = service.set(
                TENANT, PROJECT, REF, "ai.provider.x.baseUrl", "https://x", SettingType.STRING, null);
        when(repository.findByTenantIdAndReferenceTypeAndReferenceId(TENANT, PROJECT, REF))
                .thenReturn(List.of(secret, plain));
        when(repository.findByTenantIdAndReferenceTypeAndReferenceId(
                anyString(), anyString(), anyString()))
                .thenReturn(List.of(secret, plain));

        assertThat(service.findByPrefixCascade(TENANT, REF, null, "ai.provider."))
                .containsOnlyKeys("ai.provider.x.baseUrl");
    }

    // ──────────────── the reference-resolution axis ────────────────

    @Test
    void password_is_the_only_type_a_secret_reference_may_not_resolve() {
        for (SettingType type : SettingType.values()) {
            assertThat(type.referenceReadable())
                    .as("%s referenceReadable", type)
                    .isEqualTo(type != SettingType.PASSWORD);
        }
    }

    @Test
    void hidden_is_encrypted_at_rest_but_reference_readable() {
        assertThat(SettingType.HIDDEN.encrypted()).isTrue();
        assertThat(SettingType.HIDDEN.referenceReadable()).isTrue();
        assertThat(SettingType.PASSWORD.encrypted()).isTrue();
        assertThat(SettingType.PASSWORD.referenceReadable()).isFalse();
    }

    private void stubFind(SettingDocument doc) {
        when(repository.findByTenantIdAndReferenceTypeAndReferenceIdAndKey(
                anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.of(doc));
    }
}
