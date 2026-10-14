package de.mhus.vance.brain.oauthserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.audit.AuditService;
import de.mhus.vance.shared.oauthserver.OAuthServerAuthRequestDocument;
import de.mhus.vance.shared.oauthserver.OAuthServerAuthorizationService;
import de.mhus.vance.shared.oauthserver.OAuthServerClientService;
import de.mhus.vance.shared.oauthserver.OAuthServerGrantDocument;
import de.mhus.vance.shared.oauthserver.OAuthServerGrantService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OAuthAuthorizationFlowTest {

    private static final String BASE = "https://vance.example.com";
    private static final String TENANT = "acme";
    private static final String CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";
    private static final String REDIRECT = "http://127.0.0.1:4711/callback";
    private static final String RESOURCE = BASE + "/brain/acme/mcp/docs";

    private final OAuthServerSettings settings = OAuthDoorsTest.settings(BASE);
    private final OAuthClientResolver clientResolver = mock(OAuthClientResolver.class);
    private final OAuthServerClientService clientService = mock(OAuthServerClientService.class);
    private final OAuthDoors doors = new OAuthDoors(settings);
    private final OAuthDoorGate gate = mock(OAuthDoorGate.class);
    private final OAuthServerAuthorizationService authorizations = mock(OAuthServerAuthorizationService.class);
    private final OAuthServerGrantService grants = mock(OAuthServerGrantService.class);
    private final AuditService audit = mock(AuditService.class);
    private final OAuthAuthorizationFlow flow = new OAuthAuthorizationFlow(
            settings, clientResolver, clientService, doors, gate, authorizations, grants, audit);

    private final ResolvedOAuthClient dcrClient = new ResolvedOAuthClient(
            "dcr_x", OAuthClientKind.DCR, "Claude Code", null, List.of("http://127.0.0.1/callback"));

    @BeforeEach
    void setUp() {
        when(clientResolver.resolve(TENANT, "dcr_x")).thenReturn(dcrClient);
        when(clientResolver.resolve(TENANT, OAuthClientResolver.FOOT_CLIENT_ID))
                .thenReturn(OAuthClientResolver.FIRST_PARTY.get(OAuthClientResolver.FOOT_CLIENT_ID));
        when(authorizations.createRequest(any())).thenAnswer(inv -> {
            OAuthServerAuthRequestDocument doc = inv.getArgument(0);
            doc.setRequestId("r1");
            return doc;
        });
        when(authorizations.bindUser(eq(TENANT), eq("r1"), anyString())).thenAnswer(inv -> {
            return Optional.of(OAuthServerAuthRequestDocument.builder()
                    .requestId("r1")
                    .tenantId(TENANT)
                    .clientId("dcr_x")
                    .clientName("Claude Code")
                    .clientKind("dcr")
                    .redirectUri(REDIRECT)
                    .state("st")
                    .codeChallenge(CHALLENGE)
                    .resource(RESOURCE)
                    .scope("mcp-project")
                    .scopeProfiles(List.of("mcp-project"))
                    .projectId("docs")
                    .userId(inv.getArgument(2))
                    .build());
        });
    }

    private OAuthAuthorizationFlow.AuthorizeParams params(String clientId, String redirect, String resource) {
        return new OAuthAuthorizationFlow.AuthorizeParams(
                "code", clientId, redirect, "st", CHALLENGE, "S256", null, resource);
    }

    private static OAuthServerAuthRequestDocument request(String clientId, String kind) {
        return OAuthServerAuthRequestDocument.builder()
                .requestId("r1")
                .tenantId(TENANT)
                .userId("alice")
                .clientId(clientId)
                .clientName("Claude Code")
                .clientKind(kind)
                .redirectUri(REDIRECT)
                .codeChallenge(CHALLENGE)
                .resource(RESOURCE)
                .scope("mcp-project")
                .scopeProfiles(List.of("mcp-project"))
                .projectId("docs")
                .state("st")
                .build();
    }

    @Test
    void unregisteredRedirect_isShownNotRedirected() {
        OAuthAuthorizationFlow.Step step =
                flow.begin(TENANT, params("dcr_x", "https://evil.example/cb", RESOURCE), null);

        assertThat(step).isInstanceOf(OAuthAuthorizationFlow.Step.ShowError.class);
    }

    /** The cause (here: a resolver detail) goes to the log only — no oracle for the caller. */
    @Test
    void unknownClient_isShownNotRedirected_withAGenericMessage() {
        when(clientResolver.resolve(TENANT, "nope"))
                .thenThrow(new InvalidClientException("metadata host did not resolve"));

        OAuthAuthorizationFlow.Step step = flow.begin(TENANT, params("nope", REDIRECT, RESOURCE), null);

        assertThat(step).isInstanceOf(OAuthAuthorizationFlow.Step.ShowError.class);
        assertThat(((OAuthAuthorizationFlow.Step.ShowError) step).message())
                .isEqualTo(OAuthAuthorizationFlow.CLIENT_NOT_VERIFIED)
                .doesNotContain("resolve");
    }

    @Test
    void oversizedState_goesBackAsInvalidRequest_withoutEchoingIt() {
        String state = "s".repeat(OAuthServerAuthorizationService.MAX_STATE_LENGTH + 1);
        OAuthAuthorizationFlow.AuthorizeParams p = new OAuthAuthorizationFlow.AuthorizeParams(
                "code", "dcr_x", REDIRECT, state, CHALLENGE, "S256", null, RESOURCE);

        String url = ((OAuthAuthorizationFlow.Step.Redirect) flow.begin(TENANT, p, null)).url();

        assertThat(url).startsWith(REDIRECT + "?error=invalid_request").doesNotContain("state=");
        verify(authorizations, never()).createRequest(any());
    }

    @Test
    void stateAtTheLimit_isAccepted() {
        String state = "s".repeat(OAuthServerAuthorizationService.MAX_STATE_LENGTH);
        OAuthAuthorizationFlow.AuthorizeParams p = new OAuthAuthorizationFlow.AuthorizeParams(
                "code", "dcr_x", REDIRECT, state, CHALLENGE, "S256", null, RESOURCE);

        flow.begin(TENANT, p, null);

        verify(authorizations).createRequest(any());
    }

    /** An anonymous visit is not use — the registration's clock only moves when a code is issued. */
    @Test
    void anonymousAuthorize_doesNotTouchTheClient() {
        flow.begin(TENANT, params("dcr_x", REDIRECT, RESOURCE), null);

        verify(clientService, never()).touch(anyString());
    }

    @Test
    void missingPkce_goesBackToTheClientAsError_withIss() {
        OAuthAuthorizationFlow.AuthorizeParams noPkce =
                new OAuthAuthorizationFlow.AuthorizeParams("code", "dcr_x", REDIRECT, "st", null, null, null, RESOURCE);

        OAuthAuthorizationFlow.Step step = flow.begin(TENANT, noPkce, null);

        assertThat(step).isInstanceOf(OAuthAuthorizationFlow.Step.Redirect.class);
        String url = ((OAuthAuthorizationFlow.Step.Redirect) step).url();
        assertThat(url)
                .startsWith(REDIRECT + "?error=invalid_request")
                .contains("state=st")
                .contains("iss="
                        + java.net.URLEncoder.encode(BASE + "/brain/acme", java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void foreignResource_isInvalidTarget() {
        OAuthAuthorizationFlow.Step step =
                flow.begin(TENANT, params("dcr_x", REDIRECT, "https://evil.example/brain/acme/mcp"), null);

        assertThat(((OAuthAuthorizationFlow.Step.Redirect) step).url()).contains("error=invalid_target");
    }

    /** The full-account scope is for built-in clients only. */
    @Test
    void thirdPartyClientWithoutResource_cannotGetTheAccountScope() {
        OAuthAuthorizationFlow.Step step = flow.begin(TENANT, params("dcr_x", REDIRECT, null), null);

        assertThat(((OAuthAuthorizationFlow.Step.Redirect) step).url()).contains("error=invalid_target");
        verify(authorizations, never()).createRequest(any());
    }

    @Test
    void wrongScope_isInvalidScope() {
        OAuthAuthorizationFlow.AuthorizeParams p = new OAuthAuthorizationFlow.AuthorizeParams(
                "code", "dcr_x", REDIRECT, "st", CHALLENGE, "S256", "mcp", RESOURCE);

        assertThat(((OAuthAuthorizationFlow.Step.Redirect) flow.begin(TENANT, p, null)).url())
                .contains("error=invalid_scope");
    }

    @Test
    void notLoggedIn_goesToTheLoginPage_withAResumePath() {
        OAuthAuthorizationFlow.Step step = flow.begin(TENANT, params("dcr_x", REDIRECT, RESOURCE), null);

        assertThat(((OAuthAuthorizationFlow.Step.Redirect) step).url())
                .startsWith(BASE + "/login.html?tenant=acme&next=")
                .contains("%2Fbrain%2Facme%2Foauth%2Fauthorize%2Fresume%2Fr1");
    }

    @Test
    void loggedIn_thirdPartyWithoutGrant_goesToConsent() {
        when(grants.findReusable(TENANT, "alice", "dcr_x", RESOURCE)).thenReturn(Optional.empty());

        OAuthAuthorizationFlow.Step step = flow.begin(TENANT, params("dcr_x", REDIRECT, RESOURCE), "alice");

        assertThat(((OAuthAuthorizationFlow.Step.Redirect) step).url())
                .isEqualTo(BASE + "/oauth-consent.html?tenant=acme&request=r1");
    }

    @Test
    void loggedIn_withLiveGrant_skipsConsent_butAsksTheGateAgain() {
        OAuthServerGrantDocument existing =
                OAuthServerGrantDocument.builder().grantId("g1").build();
        when(grants.findReusable(TENANT, "alice", "dcr_x", RESOURCE)).thenReturn(Optional.of(existing));
        when(authorizations.take(TENANT, "r1", "alice")).thenReturn(Optional.of(request("dcr_x", "dcr")));
        when(authorizations.issueCode(any(), any(), eq("g1"), any(), any(), any(), any()))
                .thenReturn("CODE");

        OAuthAuthorizationFlow.Step step = flow.begin(TENANT, params("dcr_x", REDIRECT, RESOURCE), "alice");

        assertThat(((OAuthAuthorizationFlow.Step.Redirect) step).url()).startsWith(REDIRECT + "?code=CODE&state=st");
        verify(gate).denial(TENANT, "alice", OAuthDoor.mcpProject("docs"));
        verify(clientService).touch("dcr_x");
        verify(grants, never()).create(any(), any(), any(), any(), any(), any(), anyList(), any(), any());
    }

    /** A reload racing the first visit: only one of them takes the request and gets a code. */
    @Test
    void loggedIn_withLiveGrant_requestAlreadyTaken_isShownExpired() {
        when(grants.findReusable(TENANT, "alice", "dcr_x", RESOURCE))
                .thenReturn(Optional.of(
                        OAuthServerGrantDocument.builder().grantId("g1").build()));
        when(authorizations.take(TENANT, "r1", "alice")).thenReturn(Optional.empty());

        assertThat(flow.begin(TENANT, params("dcr_x", REDIRECT, RESOURCE), "alice"))
                .isInstanceOf(OAuthAuthorizationFlow.Step.ShowError.class);
        verify(authorizations, never()).issueCode(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void consentView_handsOutAFreshTicket() {
        when(authorizations.issueConsentTicket(TENANT, "r1", "alice")).thenReturn(Optional.of("raw-ticket"));

        assertThat(flow.consentView(TENANT, "r1", "alice").orElseThrow().getConsentTicket())
                .isEqualTo("raw-ticket");
    }

    /** Wrong ticket, wrong user, or a second submission: the service hands nothing out. */
    @Test
    void decide_requestNotTaken_doesNothing() {
        when(authorizations.takeForDecision(any(), any(), any(), any())).thenReturn(Optional.empty());

        assertThat(flow.decide(TENANT, "r1", "forged", true, "alice")).isEmpty();
        verify(authorizations, never()).issueCode(any(), any(), any(), any(), any(), any(), any());
        verify(grants, never()).create(any(), any(), any(), any(), any(), any(), anyList(), any(), any());
    }

    @Test
    void decide_decline_isAccessDenied() {
        when(authorizations.takeForDecision(TENANT, "r1", "alice", "ticket"))
                .thenReturn(Optional.of(request("dcr_x", "dcr")));

        assertThat(flow.decide(TENANT, "r1", "ticket", false, "alice").orElseThrow())
                .startsWith(REDIRECT + "?error=access_denied");
        verify(authorizations, never()).issueCode(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void decide_approve_gateDenies_isAccessDenied_andNoGrant() {
        when(authorizations.takeForDecision(TENANT, "r1", "alice", "ticket"))
                .thenReturn(Optional.of(request("dcr_x", "dcr")));
        when(gate.denial(TENANT, "alice", OAuthDoor.mcpProject("docs"))).thenReturn("no entry");

        String url = flow.decide(TENANT, "r1", "ticket", true, "alice").orElseThrow();

        assertThat(url).contains("error=access_denied");
        verify(grants, never()).create(any(), any(), any(), any(), any(), any(), anyList(), any(), any());
    }

    @Test
    void decide_approve_createsGrantAndCode() {
        when(authorizations.takeForDecision(TENANT, "r1", "alice", "ticket"))
                .thenReturn(Optional.of(request("dcr_x", "dcr")));
        when(grants.findReusable(TENANT, "alice", "dcr_x", RESOURCE)).thenReturn(Optional.empty());
        when(grants.create(
                        eq(TENANT),
                        eq("alice"),
                        eq("dcr_x"),
                        eq("Claude Code"),
                        eq(RESOURCE),
                        eq("mcp-project"),
                        eq(List.of("mcp-project")),
                        eq("docs"),
                        any()))
                .thenReturn(OAuthServerGrantDocument.builder().grantId("g9").build());
        when(authorizations.issueCode(TENANT, "alice", "g9", "dcr_x", REDIRECT, CHALLENGE, RESOURCE))
                .thenReturn("CODE");

        String url = flow.decide(TENANT, "r1", "ticket", true, "alice").orElseThrow();

        assertThat(url).startsWith(REDIRECT + "?code=CODE&state=st&iss=");
        verify(audit).authOAuthGrantIssued(TENANT, "alice", "dcr_x", "mcp-project", "docs", "g9");
        verify(clientService).touch("dcr_x");
    }

    /** A third-party client's second consent (e.g. two browser tabs) reuses the grant it already has. */
    @Test
    void decide_approve_thirdParty_reusesALiveGrant() {
        when(authorizations.takeForDecision(TENANT, "r1", "alice", "ticket"))
                .thenReturn(Optional.of(request("dcr_x", "dcr")));
        when(grants.findReusable(TENANT, "alice", "dcr_x", RESOURCE))
                .thenReturn(Optional.of(
                        OAuthServerGrantDocument.builder().grantId("g1").build()));
        when(authorizations.issueCode(any(), any(), eq("g1"), any(), any(), any(), any()))
                .thenReturn("CODE");

        assertThat(flow.decide(TENANT, "r1", "ticket", true, "alice").orElseThrow())
                .startsWith(REDIRECT + "?code=CODE");
        verify(grants, never()).create(any(), any(), any(), any(), any(), any(), anyList(), any(), any());
    }

    private OAuthServerAuthRequestDocument footRequest() {
        return OAuthServerAuthRequestDocument.builder()
                .requestId("r1")
                .tenantId(TENANT)
                .clientId(OAuthClientResolver.FOOT_CLIENT_ID)
                .clientName("Vancetope CLI")
                .clientKind("first-party")
                .redirectUri(REDIRECT)
                .codeChallenge(CHALLENGE)
                .scope("account")
                .userId("alice")
                .build();
    }

    /**
     * The first-party client may hold the account scope — and precisely
     * because of that it confirms every login: a login link someone else
     * opened must not silently hand out a full-account code.
     */
    @Test
    void firstPartyClient_mayAskForTheAccountScope_andAlwaysGoesToConsent() {
        when(authorizations.bindUser(eq(TENANT), eq("r1"), eq("alice"))).thenReturn(Optional.of(footRequest()));
        when(grants.findReusable(TENANT, "alice", OAuthClientResolver.FOOT_CLIENT_ID, null))
                .thenReturn(Optional.of(
                        OAuthServerGrantDocument.builder().grantId("g-old").build()));

        OAuthAuthorizationFlow.Step step =
                flow.begin(TENANT, params(OAuthClientResolver.FOOT_CLIENT_ID, REDIRECT, null), "alice");

        assertThat(((OAuthAuthorizationFlow.Step.Redirect) step).url())
                .isEqualTo(BASE + "/oauth-consent.html?tenant=acme&request=r1");
        verify(authorizations, never()).issueCode(any(), any(), any(), any(), any(), any(), any());
    }

    /** Every foot login gets a grant of its own — two installations never share a refresh strand. */
    @Test
    void firstPartyClient_approve_alwaysCreatesANewGrant() {
        when(authorizations.takeForDecision(TENANT, "r1", "alice", "ticket")).thenReturn(Optional.of(footRequest()));
        when(grants.findReusable(any(), any(), any(), any()))
                .thenReturn(Optional.of(
                        OAuthServerGrantDocument.builder().grantId("g-old").build()));
        when(grants.create(any(), any(), any(), any(), isNull(), eq("account"), anyList(), isNull(), any()))
                .thenReturn(OAuthServerGrantDocument.builder().grantId("g2").build());
        when(authorizations.issueCode(any(), any(), eq("g2"), any(), any(), any(), isNull()))
                .thenReturn("CODE");

        String url = flow.decide(TENANT, "r1", "ticket", true, "alice").orElseThrow();

        assertThat(url).startsWith(REDIRECT + "?code=CODE");
        verify(grants, never()).findReusable(any(), any(), any(), any());
        verify(audit).authOAuthGrantIssued(TENANT, "alice", OAuthClientResolver.FOOT_CLIENT_ID, "account", null, "g2");
    }

    /** The foot CLI listens on a loopback IP literal, any port; {@code localhost} is not registered. */
    @Test
    void firstPartyRedirects_areLoopbackIpLiteralsOnly() {
        ResolvedOAuthClient foot = OAuthClientResolver.FIRST_PARTY.get(OAuthClientResolver.FOOT_CLIENT_ID);

        assertThat(foot.allowsRedirect("http://127.0.0.1:53111/callback")).isTrue();
        assertThat(foot.allowsRedirect("http://[::1]:53111/callback")).isTrue();
        assertThat(foot.allowsRedirect("http://localhost:53111/callback")).isFalse();
    }

    @Test
    void scopeAcceptable_onlyTheDoorScopeOrOfflineAccess() {
        assertThat(OAuthAuthorizationFlow.scopeAcceptable(null, "mcp")).isTrue();
        assertThat(OAuthAuthorizationFlow.scopeAcceptable("mcp", "mcp")).isTrue();
        assertThat(OAuthAuthorizationFlow.scopeAcceptable("mcp offline_access", "mcp"))
                .isTrue();
        assertThat(OAuthAuthorizationFlow.scopeAcceptable("mcp account", "mcp")).isFalse();
    }
}
