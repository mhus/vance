package de.mhus.vance.shared.net;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Uses literal IPs only, so no DNS or network is involved. */
class GuardedHttpClientTest {

    private static final URI PUBLIC = URI.create("https://1.1.1.1/x");
    private static final URI METADATA = URI.create("http://169.254.169.254/latest/meta-data/");

    private HttpClient delegate;

    @BeforeEach
    void setUp() {
        delegate = mock(HttpClient.class);
        when(delegate.followRedirects()).thenReturn(HttpClient.Redirect.NEVER);
    }

    @Test
    void send_toPrivateTarget_isRefusedBeforeTheDelegateRuns() throws Exception {
        HttpClient guarded = SsrfGuard.guard(delegate, true);

        assertThatThrownBy(() -> guarded.send(get(METADATA), HttpResponse.BodyHandlers.discarding()))
                .isInstanceOf(SsrfGuard.SsrfException.class);

        verify(delegate, never()).send(any(), any());
    }

    @Test
    void send_redirectIntoPrivateNetwork_isRefusedOnTheHop() throws Exception {
        HttpResponse<Void> redirect = redirectTo(METADATA);
        when(delegate.<Void>send(any(), any())).thenReturn(redirect);
        HttpClient guarded = SsrfGuard.guard(delegate, true);

        assertThatThrownBy(() -> guarded.send(get(PUBLIC), HttpResponse.BodyHandlers.discarding()))
                .isInstanceOf(SsrfGuard.SsrfException.class);
    }

    @Test
    void send_withoutFollowing_returnsTheRedirectUntouched() throws Exception {
        HttpResponse<Void> redirect = redirectTo(METADATA);
        when(delegate.<Void>send(any(), any())).thenReturn(redirect);
        HttpClient guarded = SsrfGuard.guard(delegate, false);

        HttpResponse<Void> response = guarded.send(get(PUBLIC), HttpResponse.BodyHandlers.discarding());

        assertThat(response.statusCode()).isEqualTo(302);
    }

    @Test
    void sendAsync_toPrivateTarget_failsTheFuture() {
        HttpClient guarded = SsrfGuard.guard(delegate, true);

        CompletableFuture<HttpResponse<Void>> future =
                guarded.sendAsync(get(METADATA), HttpResponse.BodyHandlers.discarding());

        assertThatThrownBy(future::get)
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(SsrfGuard.SsrfException.class);
    }

    @Test
    void sendAsync_redirectIntoPrivateNetwork_failsTheFuture() {
        HttpResponse<Void> redirect = redirectTo(METADATA);
        when(delegate.<Void>sendAsync(any(), any())).thenReturn(CompletableFuture.completedFuture(redirect));
        HttpClient guarded = SsrfGuard.guard(delegate, true);

        CompletableFuture<HttpResponse<Void>> future =
                guarded.sendAsync(get(PUBLIC), HttpResponse.BodyHandlers.discarding());

        assertThatThrownBy(future::get)
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(SsrfGuard.SsrfException.class);
    }

    @Test
    void send_redirectToOtherOrigin_dropsCredentialHeaders() throws Exception {
        HttpResponse<Void> redirect = redirectTo(URI.create("https://8.8.8.8/elsewhere"));
        HttpResponse<Void> done = okResponse();
        when(delegate.<Void>send(any(), any())).thenReturn(redirect, done);
        HttpClient guarded = SsrfGuard.guard(delegate, true);

        guarded.send(withCredentials(PUBLIC), HttpResponse.BodyHandlers.discarding());

        HttpRequest followUp = secondRequest();
        assertThat(followUp.headers().firstValue("Authorization")).isEmpty();
        assertThat(followUp.headers().firstValue("X-API-KEY")).isEmpty();
        assertThat(followUp.headers().firstValue("Accept")).contains("application/json");
    }

    @Test
    void send_redirectWithinOrigin_keepsHeaders() throws Exception {
        HttpResponse<Void> redirect = redirectTo(URI.create("https://1.1.1.1/moved"));
        HttpResponse<Void> done = okResponse();
        when(delegate.<Void>send(any(), any())).thenReturn(redirect, done);
        HttpClient guarded = SsrfGuard.guard(delegate, true);

        guarded.send(withCredentials(PUBLIC), HttpResponse.BodyHandlers.discarding());

        assertThat(secondRequest().headers().firstValue("Authorization")).contains("Bearer secret");
    }

    @Test
    void guard_delegateFollowingRedirectsItself_isRejected() {
        when(delegate.followRedirects()).thenReturn(HttpClient.Redirect.NORMAL);

        assertThatThrownBy(() -> SsrfGuard.guard(delegate, true)).isInstanceOf(IllegalArgumentException.class);
    }

    private HttpRequest secondRequest() throws Exception {
        ArgumentCaptor<HttpRequest> sent = ArgumentCaptor.forClass(HttpRequest.class);
        verify(delegate, times(2)).send(sent.capture(), any());
        return sent.getAllValues().get(1);
    }

    private static HttpRequest withCredentials(URI uri) {
        return HttpRequest.newBuilder(uri)
                .header("Authorization", "Bearer secret")
                .header("X-API-KEY", "key")
                .header("Accept", "application/json")
                .GET()
                .build();
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<Void> okResponse() {
        HttpResponse<Void> ok = mock(HttpResponse.class);
        when(ok.statusCode()).thenReturn(200);
        return ok;
    }

    private static HttpRequest get(URI uri) {
        return HttpRequest.newBuilder(uri).GET().build();
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<Void> redirectTo(URI target) {
        HttpResponse<Void> redirect = mock(HttpResponse.class);
        when(redirect.statusCode()).thenReturn(302);
        when(redirect.uri()).thenReturn(PUBLIC);
        when(redirect.headers())
                .thenReturn(HttpHeaders.of(Map.of("location", List.of(target.toString())), (a, b) -> true));
        return redirect;
    }
}
