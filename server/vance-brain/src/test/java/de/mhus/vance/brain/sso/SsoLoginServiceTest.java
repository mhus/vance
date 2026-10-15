package de.mhus.vance.brain.sso;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.access.LoginTokenIssuer;
import de.mhus.vance.shared.audit.AuditService;
import de.mhus.vance.shared.oauthserver.OAuthSecrets;
import de.mhus.vance.shared.sso.SsoConfig;
import de.mhus.vance.shared.sso.SsoConfigParser;
import de.mhus.vance.shared.sso.SsoConfigService;
import de.mhus.vance.shared.sso.SsoHandoffDocument;
import de.mhus.vance.shared.sso.SsoHandoffService;
import de.mhus.vance.shared.sso.SsoLoginRequestDocument;
import de.mhus.vance.shared.sso.SsoLoginRequestService;
import de.mhus.vance.shared.user.UserDocument;
import de.mhus.vance.shared.user.UserService;
import de.mhus.vance.shared.user.UserStatus;
import de.mhus.vance.toolpack.core.SecretResolver;
import io.jsonwebtoken.JwtBuilder;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The sign-in end to end with real ID-token signatures: start → provider →
 * callback, with the identity provider and the stores mocked. Covers the
 * rules of planning/sso-login.md §6.1, §6.6, §6.7.
 */
class SsoLoginServiceTest {

    private static final String CONFIG = """
            providers:
              google:
                type: google
                clientId: "%s"
                clientSecret: "{{secret:vault:sso.google.clientSecret}}"
            domains:
              - {domain: acme.com, tenant: acme, provider: google, maxSessionDays: 3}
            consumerAccounts: {provider: google, tenants: [mhus]}
            """.formatted(TestIdTokens.CLIENT_ID);

    private final TestIdTokens tokens = new TestIdTokens();
    private SsoOidcClient oidc;
    private SsoLoginRequestService requests;
    private SsoAccountResolver accounts;
    private LoginTokenIssuer issuer;
    private SsoHandoffService handoffs;
    private UserService users;
    private SsoLoginService service;
    private final AtomicReference<SsoLoginRequestDocument> stored = new AtomicReference<>();
    private final UserDocument wile =
            UserDocument.builder().tenantId("acme").name("wile").build();

    @BeforeEach
    void setUp() throws Exception {
        SsoConfig config = SsoConfigParser.parse(CONFIG, Set.of("acme", "mhus")::contains);
        SsoConfigService configService = mock(SsoConfigService.class);
        when(configService.config()).thenReturn(config);
        SecretResolver secrets = mock(SecretResolver.class);
        when(secrets.resolveForConnector(any(), any())).thenReturn("client-secret");

        oidc = mock(SsoOidcClient.class);
        SsoOidcClient.Discovery endpoints = new SsoOidcClient.Discovery(
                "https://accounts.google.com/o/oauth2/v2/auth",
                "https://oauth2.googleapis.com/token",
                "https://www.googleapis.com/oauth2/v3/certs");
        when(oidc.discover(any())).thenReturn(endpoints);
        when(oidc.publicKey(any(), eq(TestIdTokens.KID))).thenReturn(tokens.keys.getPublic());

        requests = mock(SsoLoginRequestService.class);
        when(requests.create(any())).thenAnswer(inv -> {
            stored.set(inv.getArgument(0));
            return inv.getArgument(0);
        });
        when(requests.take(anyString())).thenAnswer(inv -> {
            SsoLoginRequestDocument doc = stored.getAndSet(null);
            return doc != null && doc.getStateHash().equals(inv.getArgument(0)) ? Optional.of(doc) : Optional.empty();
        });

        accounts = mock(SsoAccountResolver.class);
        when(accounts.resolve(any())).thenReturn(new SsoAccountResolver.Outcome(wile, null));
        issuer = mock(LoginTokenIssuer.class);
        when(issuer.issue(anyString(), anyString(), anyBoolean(), any()))
                .thenReturn(new LoginTokenIssuer.Issued("access", Instant.now(), "refresh", Instant.now()));

        handoffs = mock(SsoHandoffService.class);
        users = mock(UserService.class);
        service = new SsoLoginService(
                configService,
                secrets,
                oidc,
                requests,
                handoffs,
                users,
                accounts,
                issuer,
                mock(AuditService.class),
                "http://localhost:9901/",
                Clock.systemUTC());
    }

    /** Starts, lets the "provider" answer with a token built by {@code tweak}, runs the callback. */
    private SsoLoginService.Callback signIn(
            String email, String tenant, String next, String tokenEmail, UnaryOperator<JwtBuilder> tweak)
            throws Exception {
        SsoLoginService.Start start = service.start(email, tenant, next);
        assertThat(start).isInstanceOf(SsoLoginService.Start.Redirect.class);
        String state = ((SsoLoginService.Start.Redirect) start).state();
        String nonce = stored.get().getNonce();
        when(oidc.exchangeCode(any(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(tokens.token("sub-1", tokenEmail, nonce, tweak));
        return service.callback("google", "code-1", state, null, state);
    }

    // ─── Workspace domain ──────────────────────────────────────────────

    @Test
    void domainSignIn_withMatchingHd_succeeds_withSessionCap() throws Exception {
        SsoLoginService.Callback result =
                signIn("wile@acme.com", null, "/projects.html", "wile@acme.com", b -> b.claim("hd", "acme.com"));

        assertThat(result).isInstanceOf(SsoLoginService.Callback.Success.class);
        assertThat(((SsoLoginService.Callback.Success) result).next()).isEqualTo("/projects.html");
        ArgumentCaptor<Instant> sessionEnd = ArgumentCaptor.forClass(Instant.class);
        verify(issuer).issue(eq("acme"), eq("wile"), eq(true), sessionEnd.capture());
        assertThat(sessionEnd.getValue())
                .isBetween(
                        Instant.now().plus(Duration.ofDays(3)).minusSeconds(60),
                        Instant.now().plus(Duration.ofDays(3)));
        ArgumentCaptor<SsoAccountResolver.SignIn> in = ArgumentCaptor.forClass(SsoAccountResolver.SignIn.class);
        verify(accounts).resolve(in.capture());
        assertThat(in.getValue().tenant()).isEqualTo("acme");
        assertThat(in.getValue().subject()).isEqualTo("sub-1");
    }

    @Test
    void start_redirectCarriesPkceNonceAndHdPreselection() {
        SsoLoginService.Start.Redirect start =
                (SsoLoginService.Start.Redirect) service.start("wile@acme.com", null, null);

        assertThat(start.url())
                .startsWith("https://accounts.google.com/o/oauth2/v2/auth?")
                .contains("client_id=" + TestIdTokens.CLIENT_ID)
                .contains("code_challenge_method=S256")
                .contains("hd=acme.com")
                .contains("redirect_uri=http%3A%2F%2Flocalhost%3A9901%2Fbrain%2F_sso%2Fgoogle%2Fcallback");
        assertThat(stored.get().getStateHash()).isEqualTo(OAuthSecrets.hash(start.state()));
        assertThat(start.url()).doesNotContain(stored.get().getCodeVerifier());
    }

    @Test
    void domainSignIn_withoutHd_isRejected() throws Exception {
        // A private Google account registered under someone@acme.com: verified
        // e-mail, but no hd. The classic takeover — must not get in.
        assertRejected(signIn("wile@acme.com", null, null, "wile@acme.com", b -> b));
    }

    @Test
    void domainSignIn_withForeignHd_isRejected() throws Exception {
        assertRejected(signIn("wile@acme.com", null, null, "wile@acme.com", b -> b.claim("hd", "evil.com")));
    }

    @Test
    void domainSignIn_emailOfOtherDomain_isRejected() throws Exception {
        assertRejected(signIn("wile@acme.com", null, null, "wile@evil.com", b -> b.claim("hd", "acme.com")));
    }

    @Test
    void unverifiedEmail_isRejected() throws Exception {
        assertRejected(signIn(
                "wile@acme.com",
                null,
                null,
                "wile@acme.com",
                b -> b.claim("hd", "acme.com").claim("email_verified", false)));
    }

    @Test
    void plusAddress_isRejected() throws Exception {
        assertRejected(signIn("wile@acme.com", null, null, "wile+x@acme.com", b -> b.claim("hd", "acme.com")));
    }

    @Test
    void start_addressOfAnotherTenant_onTenantBoundPage_isRefused() {
        assertThat(service.start("wile@acme.com", "mhus", null)).isInstanceOf(SsoLoginService.Start.Failure.class);
    }

    @Test
    void start_addressOfAnotherTenant_viaOAuthResumePath_isRefused() {
        // foot authorizes tenant "mhus", the address belongs to "acme".
        assertThat(service.start("wile@acme.com", null, "/brain/mhus/oauth/authorize/resume/r1"))
                .isInstanceOf(SsoLoginService.Start.Failure.class);
    }

    @Test
    void start_unparseableNext_fallsBackToRoot() throws Exception {
        SsoLoginService.Callback result =
                signIn("wile@acme.com", null, "/a b|c", "wile@acme.com", b -> b.claim("hd", "acme.com"));

        assertThat(((SsoLoginService.Callback.Success) result).next()).isEqualTo("/");
    }

    @Test
    void issuer_isPassedOnInItsCanonicalSpelling() throws Exception {
        // Google may send iss without scheme — still the same identity.
        signIn(
                "wile@acme.com",
                null,
                null,
                "wile@acme.com",
                b -> b.issuer("accounts.google.com").claim("hd", "acme.com"));

        ArgumentCaptor<SsoAccountResolver.SignIn> in = ArgumentCaptor.forClass(SsoAccountResolver.SignIn.class);
        verify(accounts).resolve(in.capture());
        assertThat(in.getValue().issuer()).isEqualTo("https://accounts.google.com");
    }

    @Test
    void start_unsafeNext_isDropped() throws Exception {
        SsoLoginService.Callback result =
                signIn("wile@acme.com", null, "//evil.example/x", "wile@acme.com", b -> b.claim("hd", "acme.com"));

        assertThat(((SsoLoginService.Callback.Success) result).next()).isEqualTo("/");
    }

    // ─── private Gmail ─────────────────────────────────────────────────

    @Test
    void consumerSignIn_onOpenTenant_succeeds_withoutAutoCreate() throws Exception {
        SsoLoginService.Callback result = signIn(null, "mhus", null, "mike.x@gmail.com", b -> b);

        assertThat(result).isInstanceOf(SsoLoginService.Callback.Success.class);
        ArgumentCaptor<SsoAccountResolver.SignIn> in = ArgumentCaptor.forClass(SsoAccountResolver.SignIn.class);
        verify(accounts).resolve(in.capture());
        assertThat(in.getValue().tenant()).isEqualTo("mhus");
        assertThat(in.getValue().normalizedEmail()).isEqualTo("mikex@gmail.com");
        assertThat(in.getValue().autoCreate()).isFalse();
    }

    @Test
    void consumerPath_workspaceAccount_isRejected() throws Exception {
        assertRejected(signIn(null, "mhus", null, "wile@acme.com", b -> b.claim("hd", "acme.com")));
    }

    @Test
    void consumerPath_foreignDomainWithoutHd_isRejected() throws Exception {
        // Exactly the account hd protects against — not let in through the side door.
        assertRejected(signIn(null, "mhus", null, "someone@acme.com", b -> b));
    }

    @Test
    void consumerPath_onTenantNotOpened_isRefusedAtStart() {
        assertThat(service.start(null, "acme", null)).isInstanceOf(SsoLoginService.Start.Failure.class);
        assertThat(service.start(null, null, null)).isInstanceOf(SsoLoginService.Start.Failure.class);
    }

    @Test
    void discover_neverAnswersForGmail_andNeverNamesTheTenant() {
        assertThat(service.providerForEmail("wile@acme.com")).contains("google");
        assertThat(service.providerForEmail("mike@gmail.com")).isEmpty();
        assertThat(service.providerForEmail("x@unknown.org")).isEmpty();
        assertThat(service.consumerProvider("mhus")).contains("google");
        assertThat(service.consumerProvider("acme")).isEmpty();
    }

    // ─── callback integrity ────────────────────────────────────────────

    @Test
    void callback_withoutStateCookie_isRefused_beforeAnythingElse() {
        SsoLoginService.Start.Redirect start =
                (SsoLoginService.Start.Redirect) service.start("wile@acme.com", null, null);

        SsoLoginService.Callback result = service.callback("google", "code-1", start.state(), null, null);

        assertThat(result).isInstanceOf(SsoLoginService.Callback.Failure.class);
        verify(requests, never()).take(anyString());
    }

    @Test
    void callback_replayed_findsNoRequest() throws Exception {
        SsoLoginService.Start.Redirect start =
                (SsoLoginService.Start.Redirect) service.start("wile@acme.com", null, null);
        String nonce = stored.get().getNonce();
        when(oidc.exchangeCode(any(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(tokens.token("sub-1", "wile@acme.com", nonce, b -> b.claim("hd", "acme.com")));

        assertThat(service.callback("google", "code-1", start.state(), null, start.state()))
                .isInstanceOf(SsoLoginService.Callback.Success.class);
        assertThat(service.callback("google", "code-1", start.state(), null, start.state()))
                .isInstanceOf(SsoLoginService.Callback.Failure.class);
    }

    @Test
    void callback_providerError_isRefused() {
        SsoLoginService.Start.Redirect start =
                (SsoLoginService.Start.Redirect) service.start("wile@acme.com", null, null);

        assertThat(service.callback("google", null, start.state(), "access_denied", start.state()))
                .isInstanceOf(SsoLoginService.Callback.Failure.class);
    }

    @Test
    void resolverRefusal_isRejected() throws Exception {
        when(accounts.resolve(any())).thenReturn(new SsoAccountResolver.Outcome(null, "not-prepared"));

        assertRejected(signIn("wile@acme.com", null, null, "wile@acme.com", b -> b.claim("hd", "acme.com")));
    }

    @Test
    void safeNext_andTenantOf() {
        assertThat(SsoLoginService.safeNext("/a?b=c")).isEqualTo("/a?b=c");
        assertThat(SsoLoginService.safeNext("https://evil")).isNull();
        assertThat(SsoLoginService.safeNext("/\\evil")).isNull();
        assertThat(SsoLoginService.safeNext("/a\nb")).isNull();
        // Must parse as the callback's Location — refused at start, not after the sign-in.
        assertThat(SsoLoginService.safeNext("/a b")).isNull();
        assertThat(SsoLoginService.safeNext("/a|b")).isNull();
        assertThat(SsoLoginService.safeNext("/a{b}")).isNull();
        assertThat(SsoLoginService.safeNext("/a\"b")).isNull();
        assertThat(SsoLoginService.safeNext("/%zz")).isNull();
        assertThat(SsoLoginService.safeNext("/brain/acme/x?y=%20z#f")).isEqualTo("/brain/acme/x?y=%20z#f");
        assertThat(SsoLoginService.tenantOf("/brain/acme/oauth/authorize/resume/x"))
                .isEqualTo("acme");
        assertThat(SsoLoginService.tenantOf("/brain/_sso/start")).isNull();
        assertThat(SsoLoginService.tenantOf("/projects.html")).isNull();
    }

    // ─── native app handoff ────────────────────────────────────────────

    private static final String CHALLENGE = SsoHandoffService.s256("verifier-of-the-webview-0123456789-abcdef");

    @Test
    void appRedirect_onlyTheTwoKnownShapes() {
        assertThat(SsoLoginService.validAppRedirect("vance-facelift://sso-done"))
                .isTrue();
        assertThat(SsoLoginService.validAppRedirect("http://127.0.0.1:53123/sso-done"))
                .isTrue();
        assertThat(SsoLoginService.validAppRedirect("http://[::1]:53123/sso-done"))
                .isTrue();
        assertThat(SsoLoginService.validAppRedirect("http://localhost:53123/sso-done"))
                .isFalse();
        assertThat(SsoLoginService.validAppRedirect("https://evil.example/sso-done"))
                .isFalse();
        assertThat(SsoLoginService.validAppRedirect("http://127.0.0.1:99999/sso-done"))
                .isFalse();
        assertThat(SsoLoginService.validAppRedirect("vance-facelift://elsewhere"))
                .isFalse();
    }

    @Test
    void appStart_needsRedirectAndChallengeTogether() {
        assertThat(service.start("wile@acme.com", null, null, "vance-facelift://sso-done", null))
                .isInstanceOf(SsoLoginService.Start.Failure.class);
        assertThat(service.start("wile@acme.com", null, null, null, CHALLENGE))
                .isInstanceOf(SsoLoginService.Start.Failure.class);
        assertThat(service.start("wile@acme.com", null, null, "https://evil.example/x", CHALLENGE))
                .isInstanceOf(SsoLoginService.Start.Failure.class);
        assertThat(service.start("wile@acme.com", null, null, "vance-facelift://sso-done", "short"))
                .isInstanceOf(SsoLoginService.Start.Failure.class);
    }

    @Test
    void appSignIn_handsOverACode_insteadOfIssuingCookies() throws Exception {
        when(handoffs.create(eq("acme"), eq("wile"), any(), eq(CHALLENGE))).thenReturn("handoff-1");
        SsoLoginService.Start.ConfirmApp start = (SsoLoginService.Start.ConfirmApp)
                service.start("wile@acme.com", null, null, "http://127.0.0.1:53123/sso-done", CHALLENGE);
        when(oidc.exchangeCode(any(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(tokens.token(
                        "sub-1", "wile@acme.com", stored.get().getNonce(), b -> b.claim("hd", "acme.com")));

        SsoLoginService.Callback result = service.callback("google", "code-1", start.state(), null, start.state());

        assertThat(result).isInstanceOf(SsoLoginService.Callback.AppHandoff.class);
        assertThat(((SsoLoginService.Callback.AppHandoff) result).redirectUrl())
                .isEqualTo("http://127.0.0.1:53123/sso-done?code=handoff-1");
        verify(issuer, never()).issue(anyString(), anyString(), anyBoolean(), any());
    }

    @Test
    void appSignIn_refusal_goesBackToTheApp() throws Exception {
        SsoLoginService.Start.ConfirmApp start = (SsoLoginService.Start.ConfirmApp)
                service.start("wile@acme.com", null, null, "vance-facelift://sso-done", CHALLENGE);
        when(oidc.exchangeCode(any(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(tokens.token("sub-1", "wile@acme.com", stored.get().getNonce(), b -> b));

        SsoLoginService.Callback result = service.callback("google", "code-1", start.state(), null, start.state());

        assertThat(result).isInstanceOf(SsoLoginService.Callback.Failure.class);
        assertThat(((SsoLoginService.Callback.Failure) result).appRedirect()).isEqualTo("vance-facelift://sso-done");
    }

    @Test
    void appStart_neverGoesToTheProviderDirectly_butNamesTheReceivingApp() {
        // Anybody can build an app start link with a challenge of their own;
        // the person in the browser must see where the login goes first.
        SsoLoginService.Start mobile =
                service.start("wile@acme.com", null, null, "vance-facelift://sso-done", CHALLENGE);
        SsoLoginService.Start desktop =
                service.start("wile@acme.com", null, null, "http://127.0.0.1:53123/sso-done", CHALLENGE);

        assertThat(mobile).isInstanceOf(SsoLoginService.Start.ConfirmApp.class);
        SsoLoginService.Start.ConfirmApp m = (SsoLoginService.Start.ConfirmApp) mobile;
        assertThat(m.receiver()).isEqualTo("the Vancetope app on this device");
        assertThat(m.email()).isEqualTo("wile@acme.com");
        assertThat(m.url()).startsWith("https://accounts.google.com/o/oauth2/v2/auth?");
        assertThat(((SsoLoginService.Start.ConfirmApp) desktop).receiver()).contains("local port 53123");
    }

    @Test
    void redeem_issuesTheSession_withItsEnd_onlyForAnActiveAccount() {
        Instant end = Instant.now().plusSeconds(3600);
        SsoHandoffDocument handoff = SsoHandoffDocument.builder()
                .tenantId("acme")
                .username("wile")
                .sessionExpiresAt(end)
                .build();
        when(handoffs.redeem("handoff-1", "v")).thenReturn(Optional.of(handoff));
        UserDocument active = UserDocument.builder()
                .tenantId("acme")
                .name("wile")
                .status(UserStatus.ACTIVE)
                .build();
        when(users.findByTenantAndName("acme", "wile")).thenReturn(Optional.of(active));

        assertThat(service.redeem("handoff-1", "v")).isPresent();
        verify(issuer).issue("acme", "wile", true, end);

        active.setStatus(UserStatus.DISABLED);
        assertThat(service.redeem("handoff-1", "v")).isEmpty();
        assertThat(service.redeem("unknown", "v")).isEmpty();
    }

    private void assertRejected(SsoLoginService.Callback result) {
        assertThat(result).isInstanceOf(SsoLoginService.Callback.Failure.class);
        assertThat(((SsoLoginService.Callback.Failure) result).code()).isEqualTo(SsoLoginService.FAIL_REJECTED);
        verify(issuer, never()).issue(anyString(), anyString(), anyBoolean(), any());
    }
}
