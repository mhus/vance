package de.mhus.vance.shared.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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
                .name("_vance").title("Vance internal").enabled(true).build();
        concurrent.setId("their-id");
        when(repository.findByName("_vance"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(concurrent));
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
}
