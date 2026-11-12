package de.mhus.vance.shared.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.keystore.KeyService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

/**
 * Concurrency of the unconditional boot path: {@code ensure} runs from a
 * {@code @PostConstruct} on every brain start, so two pods coming up against
 * the same fresh database must both survive it.
 */
class TenantServiceTest {

    private final TenantRepository repository = mock(TenantRepository.class);
    private final KeyService keyService = mock(KeyService.class);
    private final TenantService service = new TenantService(repository, keyService);

    @Test
    void ensure_losingTheInsertRace_adoptsTheOtherPodsTenant() {
        TenantDocument concurrent = TenantDocument.builder()
                .name("_vance")
                .title("Vance internal")
                .enabled(true)
                .build();
        concurrent.setId("their-id");
        when(repository.findByName("_vance")).thenReturn(Optional.empty()).thenReturn(Optional.of(concurrent));
        when(repository.save(any())).thenThrow(new DuplicateKeyException("name index"));
        when(keyService.hasSigningKey(any(), any())).thenReturn(true);

        TenantDocument result = service.ensure("_vance", "Vance internal");

        assertThat(result.getId())
                .as("a tenant that exists now satisfies 'ensure' — failing the boot "
                        + "here would put the losing pod into a crash loop")
                .isEqualTo("their-id");
    }

    @Test
    void ensure_aFailedInsertWithNoRowBehindIt_keepsItsException() {
        when(repository.findByName("acme")).thenReturn(Optional.empty());
        when(repository.save(any())).thenThrow(new DuplicateKeyException("something else"));

        assertThatThrownBy(() -> service.ensure("acme", "Acme"))
                .as("only a row that is actually there proves it was a lost race")
                .isInstanceOf(DuplicateKeyException.class);
    }

    private static TenantDocument tenant(String name, boolean enabled) {
        TenantDocument doc =
                TenantDocument.builder().name(name).enabled(enabled).build();
        doc.setId(name + "-id");
        return doc;
    }

    @Test
    void delete_aDisabledTenant_removesItsRow() {
        TenantDocument disabled = tenant("748498812", false);
        when(repository.findByName("748498812")).thenReturn(Optional.of(disabled));

        service.delete("748498812");

        verify(repository).delete(disabled);
    }

    @Test
    void delete_anEnabledTenant_isRefusedAndNothingIsRemoved() {
        when(repository.findByName("748498812")).thenReturn(Optional.of(tenant("748498812", true)));

        assertThatThrownBy(() -> service.delete("748498812"))
                .isInstanceOf(TenantService.TenantStillEnabledException.class);

        verify(repository, never()).delete(any(TenantDocument.class));
    }

    @Test
    void delete_theSystemTenantAndAnyUnderscoreName_isNeverRemoved() {
        // Not even looked up: the refusal does not depend on what the row says.
        assertThatThrownBy(() -> service.delete("_vance")).isInstanceOf(TenantService.TenantProtectedException.class);
        assertThatThrownBy(() -> service.delete("_anything"))
                .isInstanceOf(TenantService.TenantProtectedException.class);

        verify(repository, never()).findByName(any());
        verify(repository, never()).delete(any(TenantDocument.class));
    }

    @Test
    void delete_anUnknownTenant_isNotFound() {
        when(repository.findByName("999999999")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete("999999999")).isInstanceOf(TenantService.TenantNotFoundException.class);
    }
}
