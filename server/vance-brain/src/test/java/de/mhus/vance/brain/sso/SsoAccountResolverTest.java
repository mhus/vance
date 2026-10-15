package de.mhus.vance.brain.sso;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.audit.AuditService;
import de.mhus.vance.shared.user.ExternalIdentity;
import de.mhus.vance.shared.user.UserDocument;
import de.mhus.vance.shared.user.UserService;
import de.mhus.vance.shared.user.UserStatus;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SsoAccountResolverTest {

    private static final String ISSUER = "https://accounts.google.com";

    private UserService users;
    private SsoAccountResolver resolver;

    @BeforeEach
    void setUp() {
        users = mock(UserService.class);
        when(users.findByExternalSubject(anyString(), anyString())).thenReturn(Optional.empty());
        when(users.findPreparedByEmail(anyString(), anyString())).thenReturn(Optional.empty());
        resolver = new SsoAccountResolver(users, mock(AuditService.class));
    }

    private static SsoAccountResolver.SignIn signIn(String tenant, boolean autoCreate) {
        return new SsoAccountResolver.SignIn(
                tenant, "google", ISSUER, "sub-1", "wile@acme.com", "wile@acme.com", "wile", autoCreate);
    }

    private static UserDocument linked(String tenant) {
        return UserDocument.builder()
                .tenantId(tenant)
                .name("wile")
                .status(UserStatus.ACTIVE)
                .externalIdentity(ExternalIdentity.builder()
                        .provider("google")
                        .issuer(ISSUER)
                        .subject("sub-1")
                        .build())
                .build();
    }

    @Test
    void boundSubject_isThePerson() {
        when(users.findByExternalSubject(ISSUER, "sub-1")).thenReturn(Optional.of(linked("acme")));

        SsoAccountResolver.Outcome outcome = resolver.resolve(signIn("acme", false));

        assertThat(outcome.user()).isNotNull();
        verify(users).recordExternalLogin("acme", "wile", "wile@acme.com");
    }

    @Test
    void boundSubject_inAnotherTenant_isRefused_notMoved() {
        when(users.findByExternalSubject(ISSUER, "sub-1")).thenReturn(Optional.of(linked("other")));

        assertThat(resolver.resolve(signIn("acme", true)).rejection()).isEqualTo("tenant-moved");
    }

    @Test
    void boundSubject_disabledAccount_isRefused() {
        UserDocument disabled = linked("acme");
        disabled.setStatus(UserStatus.DISABLED);
        when(users.findByExternalSubject(ISSUER, "sub-1")).thenReturn(Optional.of(disabled));

        assertThat(resolver.resolve(signIn("acme", false)).rejection()).isEqualTo("user-not-active");
    }

    @Test
    void preparedAccount_isLinked_whateverItsName() {
        UserDocument prepared = UserDocument.builder()
                .tenantId("acme")
                .name("w.coyote")
                .status(UserStatus.ACTIVE)
                .build();
        when(users.findPreparedByEmail("acme", "wile@acme.com")).thenReturn(Optional.of(prepared));
        when(users.linkExternalIdentity("acme", "w.coyote", ISSUER, "wile@acme.com", "sub-1", "wile@acme.com"))
                .thenReturn(Optional.of(prepared));

        assertThat(resolver.resolve(signIn("acme", false)).user()).isSameAs(prepared);
    }

    @Test
    void preparedAccount_linkLost_isRefused() {
        UserDocument prepared = UserDocument.builder()
                .tenantId("acme")
                .name("wile")
                .status(UserStatus.ACTIVE)
                .build();
        when(users.findPreparedByEmail("acme", "wile@acme.com")).thenReturn(Optional.of(prepared));
        when(users.linkExternalIdentity(any(), any(), any(), any(), any(), any()))
                .thenReturn(Optional.empty());

        assertThat(resolver.resolve(signIn("acme", false)).rejection()).isEqualTo("not-prepared");
    }

    @Test
    void sameNamedAccount_withoutPreparation_isNeverTakenOver() {
        // A passwordless account named like the address — not prepared, so not ours.
        when(users.existsByTenantAndName("acme", "wile")).thenReturn(true);

        assertThat(resolver.resolve(signIn("acme", false)).rejection()).isEqualTo("not-prepared");
        assertThat(resolver.resolve(signIn("acme", true)).rejection()).isEqualTo("name-taken");
        verify(users, never()).linkExternalIdentity(any(), any(), any(), any(), any(), any());
        verify(users, never()).createExternal(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void autoCreate_createsBoundAccount() {
        UserDocument created = linked("acme");
        when(users.existsByTenantAndName("acme", "wile")).thenReturn(false);
        when(users.createExternal(eq("acme"), eq("wile"), any(), eq("google"), eq(ISSUER), eq("sub-1"), any()))
                .thenReturn(created);

        assertThat(resolver.resolve(signIn("acme", true)).user()).isSameAs(created);
    }

    @Test
    void nameFor_refusesServiceAccountShape_andPlus() {
        assertThat(SsoAccountResolver.nameFor("wile.coyote@acme.com")).isEqualTo("wile.coyote");
        assertThat(SsoAccountResolver.nameFor("_admin@acme.com")).isNull();
        assertThat(SsoAccountResolver.nameFor("wile+x@acme.com")).isNull();
        assertThat(SsoAccountResolver.nameFor("wile'o@acme.com")).isNull();
    }
}
