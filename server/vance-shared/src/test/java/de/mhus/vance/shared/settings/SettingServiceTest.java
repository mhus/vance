package de.mhus.vance.shared.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.settings.SettingType;
import de.mhus.vance.shared.audit.AuditService;
import de.mhus.vance.shared.crypto.AesEncryptionService;
import de.mhus.vance.shared.home.HomeBootstrapService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Secret-handling coverage for SettingService (code-review F4 + test-gap):
 * PASSWORD values must never be persisted via the generic {@code set()}
 * (which would store them in the clear), the encrypted round-trip must return
 * the original plaintext, plaintext must never leak through the string
 * getters or bulk reads, and the password cascade must resolve inner-first
 * and fail closed on decrypt errors. Uses the real {@link AesEncryptionService}
 * so the crypto round-trip and a genuine decrypt failure are exercised.
 */
class SettingServiceTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = SettingService.SCOPE_PROJECT;
    private static final String TP = SettingService.SCOPE_THINK_PROCESS;
    private static final String TENANT_PROJ = HomeBootstrapService.TENANT_PROJECT_NAME;

    private SettingRepository repository;
    private org.springframework.data.mongodb.core.MongoTemplate mongoTemplate;
    private AesEncryptionService encryption;
    private AuditService audit;
    private SettingService service;

    @BeforeEach
    void setUp() {
        repository = mock(SettingRepository.class);
        mongoTemplate = mock(org.springframework.data.mongodb.core.MongoTemplate.class);
        audit = mock(AuditService.class);
        encryption = new AesEncryptionService("unit-test-master-key");
        service = new SettingService(repository, mongoTemplate, encryption, audit);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    // ──────────────── write path ────────────────

    @Test
    void set_rejectsPasswordType() {
        assertThatThrownBy(() -> service.set(
                TENANT, PROJECT, "proj", "api.key",
                "some-ciphertext", SettingType.PASSWORD, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("setEncryptedPassword");
    }

    @Test
    void set_allowsStringType() {
        SettingDocument saved = service.set(
                TENANT, PROJECT, "proj", "ui.theme",
                "dark", SettingType.STRING, null);

        assertThat(saved.getValue()).isEqualTo("dark");
        assertThat(saved.getType()).isEqualTo(SettingType.STRING);
    }

    @Test
    void setEncryptedPassword_storesCiphertextAndRoundTrips() {
        SettingDocument saved = service.setEncryptedPassword(
                TENANT, PROJECT, "proj", "api.key", "s3cr3t");

        // Persisted value is not the plaintext.
        assertThat(saved.getType()).isEqualTo(SettingType.PASSWORD);
        assertThat(saved.getValue()).isNotNull().isNotEqualTo("s3cr3t");

        // Reading it back decrypts to the original.
        when(repository.findByTenantIdAndReferenceTypeAndReferenceIdAndKey(
                anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.of(saved));

        String plain = service.getDecryptedPassword(
                TENANT, PROJECT, "proj", "api.key");
        assertThat(plain).isEqualTo("s3cr3t");
    }

    // ──────────────── refuse-read guard ────────────────

    @Test
    void getStringValue_refusesPasswordSetting_returnsNull() {
        stubFind(PROJECT, "proj", "api.key", passwordDoc("api.key", "s3cr3t"));

        // Never expose an encrypted secret through the plain string getter.
        assertThat(service.getStringValue(TENANT, PROJECT, "proj", "api.key")).isNull();
    }

    @Test
    void getStringValue_returnsPlainStringSetting() {
        stubFind(PROJECT, "proj", "ui.lang", doc("ui.lang", "de", SettingType.STRING));

        assertThat(service.getStringValue(TENANT, PROJECT, "proj", "ui.lang")).isEqualTo("de");
    }

    // ──────────────── decrypt paths ────────────────

    @Test
    void getDecryptedPassword_missing_returnsNull() {
        stubFind(PROJECT, "proj", "api.key", null);

        assertThat(service.getDecryptedPassword(TENANT, PROJECT, "proj", "api.key")).isNull();
    }

    @Test
    void getDecryptedPassword_wrongType_returnsNull() {
        stubFind(PROJECT, "proj", "api.key", doc("api.key", "de", SettingType.STRING));

        assertThat(service.getDecryptedPassword(TENANT, PROJECT, "proj", "api.key")).isNull();
    }

    @Test
    void getDecryptedPassword_corruptCiphertext_failsClosedToNull() {
        // A PASSWORD whose stored value is not valid ciphertext — decrypt throws
        // internally and the service must return null, never the raw ciphertext.
        stubFind(PROJECT, "proj", "api.key", doc("api.key", "not-real-ciphertext", SettingType.PASSWORD));

        assertThat(service.getDecryptedPassword(TENANT, PROJECT, "proj", "api.key")).isNull();
    }

    // ──────────────── cascade resolution ────────────────

    @Test
    void passwordCascade_prefersThinkProcessOverProjectOverTenant() {
        stubFind(TP, "tp-1", "api.key", passwordDoc("api.key", "inner"));
        stubFind(PROJECT, "proj-1", "api.key", passwordDoc("api.key", "mid"));
        stubFind(PROJECT, TENANT_PROJ, "api.key", passwordDoc("api.key", "outer"));

        assertThat(service.getDecryptedPasswordCascade(TENANT, "proj-1", "tp-1", "api.key"))
                .isEqualTo("inner");
    }

    @Test
    void passwordCascade_fallsThroughToTenantWhenInnerLayersMiss() {
        stubFind(TP, "tp-1", "api.key", null);
        stubFind(PROJECT, "proj-1", "api.key", null);
        stubFind(PROJECT, TENANT_PROJ, "api.key", passwordDoc("api.key", "outer"));

        assertThat(service.getDecryptedPasswordCascade(TENANT, "proj-1", "tp-1", "api.key"))
                .isEqualTo("outer");
    }

    // ──────────────── atomic prefix delete ────────────────

    @Test
    void deleteByPrefix_issuesSingleAtomicRemove_withAnchoredLiteralPrefix() {
        when(mongoTemplate.remove(any(org.springframework.data.mongodb.core.query.Query.class),
                org.mockito.ArgumentMatchers.eq(SettingDocument.class)))
                .thenReturn(com.mongodb.client.result.DeleteResult.acknowledged(3));

        long deleted = service.deleteByPrefix(TENANT, PROJECT, "p-1", "oauth.slack.");

        assertThat(deleted).isEqualTo(3);
        org.mockito.ArgumentCaptor<org.springframework.data.mongodb.core.query.Query> captor =
                org.mockito.ArgumentCaptor.forClass(
                        org.springframework.data.mongodb.core.query.Query.class);
        verify(mongoTemplate).remove(captor.capture(),
                org.mockito.ArgumentMatchers.eq(SettingDocument.class));
        org.bson.Document q = captor.getValue().getQueryObject();
        assertThat(q).containsEntry("tenantId", TENANT)
                .containsEntry("referenceType", PROJECT)
                .containsEntry("referenceId", "p-1")
                .containsKey("key");
        // The dot in the prefix must be quoted (literal), not a regex wildcard.
        assertThat(q.toString()).contains("\\Qoauth.slack.\\E");
        // Exactly one round-trip — no per-key scan-then-delete.
        verify(repository, org.mockito.Mockito.never())
                .deleteByTenantIdAndReferenceTypeAndReferenceIdAndKey(any(), any(), any(), any());
    }

    @Test
    void deleteByPrefix_blankPrefix_isNoOp() {
        assertThat(service.deleteByPrefix(TENANT, PROJECT, "p-1", "")).isZero();
        verify(mongoTemplate, org.mockito.Mockito.never()).remove(
                any(org.springframework.data.mongodb.core.query.Query.class),
                org.mockito.ArgumentMatchers.eq(SettingDocument.class));
    }

    // ──────────────── bulk read skips secrets ────────────────

    @Test
    void findByPrefixCascade_skipsPasswordEntries() {
        SettingDocument plain = doc("provider.name", "openai", SettingType.STRING);
        SettingDocument secret = passwordDoc("provider.key", "s3cr3t");
        when(repository.findByTenantIdAndReferenceTypeAndReferenceId(
                TENANT, PROJECT, TENANT_PROJ)).thenReturn(List.of(plain, secret));

        var merged = service.findByPrefixCascade(TENANT, null, null, "provider.");

        assertThat(merged).containsEntry("provider.name", "openai");
        assertThat(merged).doesNotContainKey("provider.key");
    }

    // ──────────────── helpers ────────────────

    private SettingDocument doc(String key, String value, SettingType type) {
        return SettingDocument.builder()
                .tenantId(TENANT).key(key).value(value).type(type).build();
    }

    /** A PASSWORD document holding the real ciphertext of {@code plaintext}. */
    private SettingDocument passwordDoc(String key, String plaintext) {
        return doc(key, encryption.encrypt(plaintext), SettingType.PASSWORD);
    }

    private void stubFind(String refType, String refId, String key, SettingDocument doc) {
        when(repository.findByTenantIdAndReferenceTypeAndReferenceIdAndKey(
                TENANT, refType, refId, key))
                .thenReturn(Optional.ofNullable(doc));
    }
}
