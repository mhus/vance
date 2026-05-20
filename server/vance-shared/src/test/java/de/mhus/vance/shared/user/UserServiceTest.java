package de.mhus.vance.shared.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Covers the prefix-and-flag rules introduced for service accounts:
 * {@code create} rejects anything that starts with {@code _};
 * {@code createServiceAccount} accepts {@code _xyz} but rejects
 * {@code _vance-xyz}; {@code ensureVanceServiceAccount} is the only
 * door to the {@code _vance-} sub-namespace, idempotent on second call;
 * {@code update} refuses to flip {@code loginEnabled=true} on a service
 * account.
 *
 * <p>UserRepository is mocked so the test stays in pure logic — no
 * Mongo, no Spring context.
 */
class UserServiceTest {

    private static final String TENANT = "acme";
    private UserRepository repo;
    private UserService service;

    @BeforeEach
    void setUp() {
        repo = mock(UserRepository.class);
        service = new UserService(repo);
        when(repo.save(any(UserDocument.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void create_regularUser_setsHumanDefaults() {
        when(repo.existsByTenantIdAndName(TENANT, "alice")).thenReturn(false);

        UserDocument user = service.create(TENANT, "alice", "hash", "Alice", "alice@x.test");

        assertThat(user.getName()).isEqualTo("alice");
        assertThat(user.isLoginEnabled()).isTrue();
        assertThat(user.isServiceAccount()).isFalse();
        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    void create_underscoreName_isRejected() {
        assertThatThrownBy(() -> service.create(TENANT, "_alice", null, null, null))
                .isInstanceOf(UserService.ReservedNameException.class)
                .hasMessageContaining(UserService.SERVICE_ACCOUNT_PREFIX);
        verify(repo, never()).save(any());
    }

    @Test
    void createServiceAccount_setsServiceFlagsAndDisablesLogin() {
        when(repo.existsByTenantIdAndName(TENANT, "_ci-bot")).thenReturn(false);

        UserDocument user = service.createServiceAccount(
                TENANT, "_ci-bot", null, "CI bot", null);

        assertThat(user.isServiceAccount()).isTrue();
        assertThat(user.isLoginEnabled()).isFalse();
    }

    @Test
    void createServiceAccount_rejectsNonUnderscoreName() {
        assertThatThrownBy(() -> service.createServiceAccount(TENANT, "ci-bot", null, null, null))
                .isInstanceOf(UserService.ReservedNameException.class);
    }

    @Test
    void createServiceAccount_rejectsReservedVancePrefix() {
        assertThatThrownBy(() -> service.createServiceAccount(
                TENANT, "_vance-admin", null, null, null))
                .isInstanceOf(UserService.ReservedNameException.class)
                .hasMessageContaining(UserService.RESERVED_VANCE_PREFIX);
    }

    @Test
    void ensureVanceServiceAccount_createsOnFirstCall() {
        when(repo.findByTenantIdAndName(TENANT, "_vance-admin")).thenReturn(Optional.empty());
        when(repo.existsByTenantIdAndName(TENANT, "_vance-admin")).thenReturn(false);

        UserDocument user = service.ensureVanceServiceAccount(
                TENANT, "_vance-admin", "uuid-hash", "Anus admin", null);

        assertThat(user.getName()).isEqualTo("_vance-admin");
        assertThat(user.isServiceAccount()).isTrue();
        assertThat(user.isLoginEnabled()).isFalse();
    }

    @Test
    void ensureVanceServiceAccount_isIdempotent() {
        UserDocument existing = UserDocument.builder()
                .tenantId(TENANT)
                .name("_vance-admin")
                .serviceAccount(true)
                .loginEnabled(false)
                .build();
        when(repo.findByTenantIdAndName(TENANT, "_vance-admin"))
                .thenReturn(Optional.of(existing));

        UserDocument user = service.ensureVanceServiceAccount(
                TENANT, "_vance-admin", "would-be-new-hash", null, null);

        assertThat(user).isSameAs(existing);
        verify(repo, never()).save(any());
    }

    @Test
    void ensureVanceServiceAccount_rejectsNonReservedName() {
        assertThatThrownBy(() -> service.ensureVanceServiceAccount(
                TENANT, "_ci-bot", null, null, null))
                .isInstanceOf(UserService.ReservedNameException.class);
    }

    @Test
    void update_canEnableLoginOnServiceAccount() {
        // The two flags are orthogonal: a service account starts with
        // loginEnabled=false (createServiceAccount default), but admin
        // may flip it to true post-creation so the account can drive a
        // daemon process through the standard password-login endpoint.
        UserDocument svc = UserDocument.builder()
                .tenantId(TENANT)
                .name("_ci-bot")
                .serviceAccount(true)
                .loginEnabled(false)
                .status(UserStatus.ACTIVE)
                .build();
        when(repo.findByTenantIdAndName(TENANT, "_ci-bot")).thenReturn(Optional.of(svc));
        when(repo.save(any(UserDocument.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        UserDocument updated = service.update(
                TENANT, "_ci-bot", null, null, null, /* loginEnabled */ true);

        assertThat(updated.isServiceAccount()).isTrue();
        assertThat(updated.isLoginEnabled()).isTrue();
    }

    @Test
    void setLoginEnabled_convenienceFlipsOneFlag() {
        UserDocument svc = UserDocument.builder()
                .tenantId(TENANT)
                .name("_acme-automaton")
                .serviceAccount(true)
                .loginEnabled(false)
                .status(UserStatus.ACTIVE)
                .title("Acme Automaton")
                .email("automaton@acme.invalid")
                .build();
        when(repo.findByTenantIdAndName(TENANT, "_acme-automaton")).thenReturn(Optional.of(svc));
        when(repo.save(any(UserDocument.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        UserDocument updated = service.setLoginEnabled(TENANT, "_acme-automaton", true);

        assertThat(updated.isLoginEnabled()).isTrue();
        // Other fields unchanged.
        assertThat(updated.getTitle()).isEqualTo("Acme Automaton");
        assertThat(updated.getEmail()).isEqualTo("automaton@acme.invalid");
        assertThat(updated.isServiceAccount()).isTrue();
    }

    @Test
    void update_canDisableLoginOnHumanUser() {
        UserDocument human = UserDocument.builder()
                .tenantId(TENANT)
                .name("alice")
                .loginEnabled(true)
                .status(UserStatus.ACTIVE)
                .build();
        when(repo.findByTenantIdAndName(TENANT, "alice")).thenReturn(Optional.of(human));

        UserDocument updated = service.update(
                TENANT, "alice", null, null, null, /* loginEnabled */ false);

        assertThat(updated.isLoginEnabled()).isFalse();
    }
}
