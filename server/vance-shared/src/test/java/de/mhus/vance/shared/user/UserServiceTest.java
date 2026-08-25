package de.mhus.vance.shared.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

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
    private MongoTemplate mongoTemplate;
    private UserLifecycleListener listener;
    private UserService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repo = mock(UserRepository.class);
        mongoTemplate = mock(MongoTemplate.class);
        listener = mock(UserLifecycleListener.class);
        ObjectProvider<UserLifecycleListener> listenerProvider = mock(ObjectProvider.class);
        // orderedStream() is a default method; a plain mock would return null
        // and the lifecycle tests below would pass without the wiring existing.
        // Fresh stream per call — the service iterates it more than once.
        when(listenerProvider.orderedStream()).thenAnswer(inv -> Stream.of(listener));
        service = new UserService(repo, mongoTemplate, listenerProvider,
                mock(de.mhus.vance.shared.megadodo.MegadodoService.class));
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

    // ──────────────────── Brute-force lockout ────────────────────

    @Test
    void isLocked_futureLockedUntil_isTrue() {
        UserDocument u = UserDocument.builder()
                .lockedUntil(Instant.now().plusSeconds(60)).build();
        assertThat(service.isLocked(u)).isTrue();
    }

    @Test
    void isLocked_pastLockedUntil_isFalse() {
        UserDocument u = UserDocument.builder()
                .lockedUntil(Instant.now().minusSeconds(60)).build();
        assertThat(service.isLocked(u)).isFalse();
    }

    @Test
    void isLocked_noLockedUntil_isFalse() {
        assertThat(service.isLocked(UserDocument.builder().build())).isFalse();
    }

    @Test
    void recordFailedLogin_belowThreshold_incrementsOnly_doesNotLock() {
        // findAndModify returns the post-increment doc with a sub-threshold count.
        UserDocument afterInc = UserDocument.builder()
                .tenantId(TENANT).name("alice")
                .failedLoginAttempts(2).build();
        when(mongoTemplate.findAndModify(
                any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), eq(UserDocument.class)))
                .thenReturn(afterInc);

        service.recordFailedLogin(TENANT, "alice");

        // No second (lock) write.
        verify(mongoTemplate, never()).updateFirst(
                any(Query.class), any(Update.class), eq(UserDocument.class));
    }

    @Test
    void recordFailedLogin_atThreshold_locksAndResetsCounter() {
        UserDocument afterInc = UserDocument.builder()
                .tenantId(TENANT).name("alice")
                .failedLoginAttempts(UserService.MAX_FAILED_LOGIN_ATTEMPTS).build();
        when(mongoTemplate.findAndModify(
                any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), eq(UserDocument.class)))
                .thenReturn(afterInc);

        service.recordFailedLogin(TENANT, "alice");

        ArgumentCaptor<Update> lockUpdate = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).updateFirst(
                any(Query.class), lockUpdate.capture(), eq(UserDocument.class));
        // Lock write sets both lockedUntil and resets the counter.
        org.bson.Document set = lockUpdate.getValue().getUpdateObject().get("$set", org.bson.Document.class);
        assertThat(set).containsKeys("lockedUntil", "failedLoginAttempts");
        assertThat(set.get("failedLoginAttempts")).isEqualTo(0);
    }

    @Test
    void recordFailedLogin_unknownUser_isNoOp() {
        when(mongoTemplate.findAndModify(
                any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), eq(UserDocument.class)))
                .thenReturn(null);

        service.recordFailedLogin(TENANT, "ghost");

        verify(mongoTemplate, never()).updateFirst(
                any(Query.class), any(Update.class), eq(UserDocument.class));
    }

    @Test
    void createServiceAccount_systemOwnerName_isRejected() {
        // The placeholder owner of server-owned system sessions resolves to
        // "no user" and thereby to SecurityContext.SYSTEM — a real account
        // with that name would inherit the system trust boundary.
        assertThatThrownBy(() -> service.createServiceAccount(
                TENANT, de.mhus.vance.shared.session.SessionService.SYSTEM_OWNER,
                null, null, null))
                .isInstanceOf(UserService.ReservedNameException.class)
                .hasMessageContaining("reserved");
        verify(repo, never()).save(any());
    }

    @Test
    void delete_notifiesTheLifecycleListener_beforeRemovingTheDocument() {
        // Grants key on the username, not the Mongo id. Left behind, they are
        // inherited by the next account created under the same name — realistic
        // for service-account schemes (_daemon-prod-01) and reused human logins.
        // The listener runs first so a failing grant store aborts the delete
        // rather than orphaning the grant.
        UserDocument admin = UserDocument.builder().tenantId(TENANT).name("marvin.acme").build();
        when(repo.findByTenantIdAndName(TENANT, "marvin.acme")).thenReturn(Optional.of(admin));

        service.delete(TENANT, "marvin.acme");

        InOrder order = inOrder(listener, repo);
        order.verify(listener).onUserDeleted(TENANT, "marvin.acme");
        order.verify(repo).delete(admin);
    }

    @Test
    void delete_whenAListenerFails_leavesTheDocumentInPlace() {
        // Fail-closed: a grant whose subject is gone is worse than an account
        // that could not be deleted, so the exception must not be swallowed.
        UserDocument admin = UserDocument.builder().tenantId(TENANT).name("marvin.acme").build();
        when(repo.findByTenantIdAndName(TENANT, "marvin.acme")).thenReturn(Optional.of(admin));
        doThrow(new IllegalStateException("grant store down"))
                .when(listener).onUserDeleted(TENANT, "marvin.acme");

        assertThatThrownBy(() -> service.delete(TENANT, "marvin.acme"))
                .isInstanceOf(IllegalStateException.class);

        verify(repo, never()).delete(any(UserDocument.class));
    }

    @Test
    void delete_ofAnUnknownUser_notifiesNobody() {
        when(repo.findByTenantIdAndName(TENANT, "ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(TENANT, "ghost"))
                .isInstanceOf(UserService.UserNotFoundException.class);

        verify(listener, never()).onUserDeleted(any(), any());
    }

    @Test
    void create_notifiesTheLifecycleListener_withTheSavedAccount() {
        // The create side exists for the same hazard: state left under a name
        // that comes back. A listener clears it before the new owner can
        // inherit it.
        when(repo.existsByTenantIdAndName(TENANT, "alice")).thenReturn(false);

        service.create(TENANT, "alice", "hash", "Alice", "alice@x.test");

        ArgumentCaptor<UserDocument> created = ArgumentCaptor.forClass(UserDocument.class);
        verify(listener).onUserCreated(created.capture());
        assertThat(created.getValue().getName()).isEqualTo("alice");
        assertThat(created.getValue().getTenantId()).isEqualTo(TENANT);
    }

    @Test
    void create_whenAListenerFails_theAccountStillStands() {
        // Nothing left to abort by then — the document is written. Reporting a
        // failed create for an account that exists would be the worse answer.
        when(repo.existsByTenantIdAndName(TENANT, "alice")).thenReturn(false);
        doThrow(new IllegalStateException("grant store down"))
                .when(listener).onUserCreated(any());

        UserDocument user = service.create(TENANT, "alice", "hash", null, null);

        assertThat(user.getName()).isEqualTo("alice");
    }

    @Test
    void ensureVanceServiceAccount_onSecondCall_doesNotNotifyAgain() {
        // Fires once per account, not once per call: the second call finds the
        // document and creates nothing.
        UserDocument existing = UserDocument.builder()
                .tenantId(TENANT).name("_vance-admin").build();
        when(repo.findByTenantIdAndName(TENANT, "_vance-admin"))
                .thenReturn(Optional.of(existing));

        service.ensureVanceServiceAccount(TENANT, "_vance-admin", null, null, null);

        verify(listener, never()).onUserCreated(any());
    }

    @Test
    void setPasswordHash_stampsChangedAt_andClearsLockout() {
        UserDocument locked = UserDocument.builder()
                .tenantId(TENANT).name("alice")
                .failedLoginAttempts(4)
                .lockedUntil(Instant.now().plusSeconds(600))
                .lastFailedLoginAt(Instant.now())
                .build();
        when(repo.findByTenantIdAndName(TENANT, "alice")).thenReturn(Optional.of(locked));

        UserDocument saved = service.setPasswordHash(TENANT, "alice", "new-hash");

        assertThat(saved.getPasswordHash()).isEqualTo("new-hash");
        assertThat(saved.getPasswordChangedAt()).isNotNull();
        assertThat(saved.getFailedLoginAttempts()).isZero();
        assertThat(saved.getLockedUntil()).isNull();
        assertThat(saved.getLastFailedLoginAt()).isNull();
    }
}
