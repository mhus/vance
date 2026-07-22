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
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Focused coverage for the secret-handling invariant of SettingService
 * (code-review F4): PASSWORD values must never be persisted via the generic
 * {@code set()} (which would store them in the clear) and the encrypted
 * round-trip must return the original plaintext.
 */
class SettingServiceTest {

    private SettingRepository repository;
    private SettingService service;

    @BeforeEach
    void setUp() {
        repository = mock(SettingRepository.class);
        AuditService audit = mock(AuditService.class);
        AesEncryptionService encryption = new AesEncryptionService("unit-test-master-key");
        service = new SettingService(repository, encryption, audit);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void set_rejectsPasswordType() {
        assertThatThrownBy(() -> service.set(
                "acme", SettingService.SCOPE_PROJECT, "proj", "api.key",
                "some-ciphertext", SettingType.PASSWORD, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("setEncryptedPassword");
    }

    @Test
    void set_allowsStringType() {
        SettingDocument saved = service.set(
                "acme", SettingService.SCOPE_PROJECT, "proj", "ui.theme",
                "dark", SettingType.STRING, null);

        assertThat(saved.getValue()).isEqualTo("dark");
        assertThat(saved.getType()).isEqualTo(SettingType.STRING);
    }

    @Test
    void setEncryptedPassword_storesCiphertextAndRoundTrips() {
        SettingDocument saved = service.setEncryptedPassword(
                "acme", SettingService.SCOPE_PROJECT, "proj", "api.key", "s3cr3t");

        // Persisted value is not the plaintext.
        assertThat(saved.getType()).isEqualTo(SettingType.PASSWORD);
        assertThat(saved.getValue()).isNotNull().isNotEqualTo("s3cr3t");

        // Reading it back decrypts to the original.
        when(repository.findByTenantIdAndReferenceTypeAndReferenceIdAndKey(
                anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.of(saved));

        String plain = service.getDecryptedPassword(
                "acme", SettingService.SCOPE_PROJECT, "proj", "api.key");
        assertThat(plain).isEqualTo("s3cr3t");
    }
}
