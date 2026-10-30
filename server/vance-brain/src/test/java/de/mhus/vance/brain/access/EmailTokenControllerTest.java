package de.mhus.vance.brain.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.access.AccessTokenResponse;
import de.mhus.vance.api.access.EmailTokenStartResponse;
import de.mhus.vance.api.access.EmailTokenVerifyRequest;
import de.mhus.vance.shared.audit.AuditService;
import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.jwt.JwtService;
import de.mhus.vance.shared.jwt.TokenType;
import de.mhus.vance.shared.user.EmailLoginPurpose;
import de.mhus.vance.shared.user.EmailLoginService;
import de.mhus.vance.shared.user.ExternalIdentity;
import de.mhus.vance.shared.user.UserDocument;
import de.mhus.vance.shared.user.UserService;
import de.mhus.vance.shared.user.UserStatus;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class EmailTokenControllerTest {

    private static final String TENANT = "acme";
    private static final String USERNAME = "alice";

    private EmailLoginService emailLoginService;
    private UserService userService;
    private JwtService jwtService;
    private AuditService auditService;
    private EmailTokenController controller;
    private UserDocument user;

    @BeforeEach
    void setUp() {
        emailLoginService = mock(EmailLoginService.class);
        userService = mock(UserService.class);
        jwtService = mock(JwtService.class);
        auditService = mock(AuditService.class);
        controller = controllerWith(new LoginModes(true, true), 20, 60);
        user = activeUser();
        when(userService.findByTenantAndName(TENANT, USERNAME)).thenAnswer(inv -> Optional.of(user));
        when(jwtService.createToken(anyString(), anyString(), any(Instant.class), eq(TokenType.ACCESS), any()))
                .thenReturn("access-jwt");
        when(jwtService.createToken(anyString(), anyString(), any(Instant.class), eq(TokenType.REFRESH), any()))
                .thenReturn("refresh-jwt");
    }

    @Test
    void startAnswersWithTheTransactionOfTheService() {
        Instant expires = Instant.parse("2026-10-30T12:10:00Z");
        when(emailLoginService.startLogin(TENANT, USERNAME)).thenReturn(new EmailLoginService.Started("tx-1", expires));

        ResponseEntity<EmailTokenStartResponse> resp = controller.start(TENANT, USERNAME, from("203.0.113.5"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().getTransactionId()).isEqualTo("tx-1");
        assertThat(resp.getBody().getExpiresAtTimestamp()).isEqualTo(expires.toEpochMilli());
    }

    @Test
    void bothEndpointsAreNotFoundWhileTheModeIsOff() {
        EmailTokenController off = controllerWith(new LoginModes(false, true), 20, 60);

        assertThat(off.start(TENANT, USERNAME, from("203.0.113.5")).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(off.verify(TENANT, USERNAME, body("tx-1", "123456789012"), from("203.0.113.5"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        verify(emailLoginService, never()).startLogin(anyString(), anyString());
    }

    @Test
    void startIsBudgetedPerAddress() {
        EmailTokenController tight = controllerWith(new LoginModes(true, true), 1, 60);
        when(emailLoginService.startLogin(TENANT, USERNAME))
                .thenReturn(new EmailLoginService.Started("tx-1", Instant.now()));

        assertThat(tight.start(TENANT, USERNAME, from("203.0.113.5")).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(tight.start(TENANT, USERNAME, from("203.0.113.5")).getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void aGoodCodeSignsInLikeThePassword() {
        accept("tx-1", "123456789012");

        ResponseEntity<AccessTokenResponse> resp =
                controller.verify(TENANT, USERNAME, body("tx-1", "123456789012"), from("203.0.113.5"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().getToken()).isEqualTo("access-jwt");
        assertThat(resp.getBody().getRefreshToken()).isNull();
        verify(auditService).authLoginSuccess(TENANT, USERNAME);
    }

    @Test
    void theFlagsShapeTheAnswerLikeThePasswordLogin() {
        accept("tx-1", "123456789012");
        EmailTokenVerifyRequest request = body("tx-1", "123456789012");
        request.setRequestRefreshToken(true);
        request.setRequestCookies(true);

        ResponseEntity<AccessTokenResponse> resp = controller.verify(TENANT, USERNAME, request, from("203.0.113.5"));

        assertThat(resp.getBody().getRefreshToken()).isEqualTo("refresh-jwt");
        assertThat(resp.getHeaders().get("Set-Cookie")).isNotEmpty();
    }

    @Test
    void aWrongCodeIsAUniform401WithTheReasonInTheAuditLog() {
        when(emailLoginService.verify(TENANT, USERNAME, EmailLoginPurpose.LOGIN, "tx-1", "000000000000"))
                .thenReturn(new EmailLoginService.Verification(false, null));

        ResponseEntity<AccessTokenResponse> resp =
                controller.verify(TENANT, USERNAME, body("tx-1", "000000000000"), from("203.0.113.5"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(resp.getBody()).isNull();
        verify(auditService).authLoginFailure(TENANT, USERNAME, "email-token-invalid");
        verify(auditService, never()).authLoginSuccess(anyString(), anyString());
    }

    @Test
    void aGoodCodeDoesNotSignInAnAccountThatChangedSinceItWasIssued() {
        accept("tx-1", "123456789012");
        user.setStatus(UserStatus.DISABLED);

        ResponseEntity<AccessTokenResponse> resp =
                controller.verify(TENANT, USERNAME, body("tx-1", "123456789012"), from("203.0.113.5"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(auditService).authLoginFailure(TENANT, USERNAME, "user-not-allowed");
    }

    @Test
    void anSsoAccountCannotSignInWithACode() {
        accept("tx-1", "123456789012");
        user.setExternalIdentity(ExternalIdentity.builder()
                .issuer(ExternalIdentity.GOOGLE_ISSUER)
                .build());

        assertThat(controller
                        .verify(TENANT, USERNAME, body("tx-1", "123456789012"), from("203.0.113.5"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void aSuccessfulSignInClearsEarlierFailures() {
        accept("tx-1", "123456789012");
        user.setFailedLoginAttempts(3);

        controller.verify(TENANT, USERNAME, body("tx-1", "123456789012"), from("203.0.113.5"));

        verify(userService).resetLoginFailures(TENANT, USERNAME);
    }

    @Test
    void verifyIsBudgetedPerAddress() {
        EmailTokenController tight = controllerWith(new LoginModes(true, true), 20, 1);
        when(emailLoginService.verify(anyString(), anyString(), any(), anyString(), anyString()))
                .thenReturn(new EmailLoginService.Verification(false, null));

        assertThat(tight.verify(TENANT, USERNAME, body("tx-1", "000000000000"), from("203.0.113.5"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(tight.verify(TENANT, USERNAME, body("tx-1", "000000000000"), from("203.0.113.5"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    private void accept(String transactionId, String token) {
        when(emailLoginService.verify(TENANT, USERNAME, EmailLoginPurpose.LOGIN, transactionId, token))
                .thenReturn(new EmailLoginService.Verification(true, "alice@example.com"));
    }

    private EmailTokenController controllerWith(LoginModes modes, int starts, int verifies) {
        WebUiCookieService cookies = new WebUiCookieService(true);
        LoginTokenIssuer issuer = new LoginTokenIssuer(jwtService, mock(HomeBootstrapService.class));
        return new EmailTokenController(
                emailLoginService,
                userService,
                modes,
                new LoginSuccessResponder(issuer, auditService, cookies),
                auditService,
                starts,
                verifies);
    }

    private static EmailTokenVerifyRequest body(String transactionId, String token) {
        return EmailTokenVerifyRequest.builder()
                .transactionId(transactionId)
                .token(token)
                .build();
    }

    private static HttpServletRequest from(String address) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn(address);
        return request;
    }

    private static UserDocument activeUser() {
        return UserDocument.builder()
                .tenantId(TENANT)
                .name(USERNAME)
                .email("alice@example.com")
                .status(UserStatus.ACTIVE)
                .build();
    }
}
