package de.mhus.vance.brain.sso;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.access.WebUiCookieService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * The HTTP surface around {@link SsoLoginService}: the confirmation page in
 * front of a native-app sign-in, and the per-kind budgets (sso-login.md §3,
 * §8.1).
 */
class SsoControllerTest {

    private final SsoLoginService service = mock(SsoLoginService.class);
    private final SsoController controller = new SsoController(service, mock(WebUiCookieService.class), true, 2);

    @Test
    void appStart_showsTheConfirmation_insteadOfGoingToTheProvider() {
        when(service.start(any(), any(), any(), any(), any()))
                .thenReturn(new SsoLoginService.Start.ConfirmApp(
                        "https://accounts.google.com/o/oauth2/v2/auth?client_id=x&state=s",
                        "s",
                        "the Vancetope app on this device",
                        "wile@acme.com"));

        ResponseEntity<?> response = controller.start(
                "wile@acme.com", null, null, "vance-facelift://sso-done", "c".repeat(43), request("203.0.113.5"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getLocation()).isNull();
        assertThat(response.getHeaders().getFirst("X-Frame-Options")).isEqualTo("DENY");
        assertThat(response.getHeaders().getFirst("Content-Security-Policy"))
                .contains("frame-ancestors 'none'")
                .contains("default-src 'none'");
        assertThat(response.getHeaders().get(HttpHeaders.SET_COOKIE))
                .anySatisfy(c -> assertThat(c).startsWith(SsoController.STATE_COOKIE + "=s"));
        assertThat((String) response.getBody())
                .contains("the Vancetope app on this device")
                .contains("wile@acme.com")
                // The link is HTML-escaped: & in the provider URL becomes &amp;.
                .contains("href=\"https://accounts.google.com/o/oauth2/v2/auth?client_id=x&amp;state=s\"")
                .doesNotContain("<script");
    }

    @Test
    void confirmationPage_escapesWhatItShows() {
        String page = SsoController.appConfirmPage(new SsoLoginService.Start.ConfirmApp(
                "https://idp.example/auth?a=\"><script>", "s", "the <b>app</b>", "<x>@acme.com"));

        assertThat(page).doesNotContain("<script>").doesNotContain("<b>app</b>").doesNotContain("<x>@");
    }

    @Test
    void webStart_redirectsToTheProvider() {
        when(service.start(any(), any(), any(), any(), any()))
                .thenReturn(new SsoLoginService.Start.Redirect("https://accounts.google.com/auth?x=1", "s"));

        ResponseEntity<?> response = controller.start("wile@acme.com", null, null, null, null, request("203.0.113.5"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(response.getHeaders().getLocation()).hasToString("https://accounts.google.com/auth?x=1");
    }

    @Test
    void budgets_areCountedPerKind_andTheCallbackIsNotBudgeted() {
        when(service.providerForEmail(anyString())).thenReturn(java.util.Optional.empty());
        when(service.callback(anyString(), any(), any(), any(), any()))
                .thenReturn(new SsoLoginService.Callback.Failure(SsoLoginService.FAIL_ERROR, null));
        MockHttpServletRequest nat = request("198.51.100.7");

        // Lookups use up their own budget (2 in this test) …
        assertThat(controller.discover("a@acme.com", nat).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(controller.discover("a@acme.com", nat).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(controller.discover("a@acme.com", nat).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        // … which leaves the starts and redeems of that address alone.
        assertThat(controller.withinBudget(SsoController.Budget.START, nat)).isTrue();
        assertThat(controller.withinBudget(SsoController.Budget.REDEEM, nat)).isTrue();

        // A completed sign-in is never refused for volume.
        for (int i = 0; i < 10; i++) {
            assertThat(controller.callback("google", "code", "state", null, nat).getStatusCode())
                    .isEqualTo(HttpStatus.FOUND);
        }
    }

    private static MockHttpServletRequest request(String address) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(address);
        return request;
    }
}
