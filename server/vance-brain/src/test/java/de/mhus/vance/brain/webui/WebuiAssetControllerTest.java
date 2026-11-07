package de.mhus.vance.brain.webui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class WebuiAssetControllerTest {

    private WebuiAssetService assets;
    private RequestAuthority authority;
    private HttpServletRequest request;
    private WebuiAssetController controller;

    @BeforeEach
    void setUp() {
        assets = mock(WebuiAssetService.class);
        authority = mock(RequestAuthority.class);
        request = mock(HttpServletRequest.class);
        controller = new WebuiAssetController(assets, authority);
    }

    @Test
    void theProjectIsReadCheckedBeforeAnythingIsLookedUp() {
        when(assets.find("acme", "proj", "/icons/a.png")).thenReturn(Optional.empty());

        controller.asset("acme", "proj", "/icons/a.png", request);

        verify(authority).enforce(request, new Resource.Project("acme", "proj"), Action.READ);
    }

    @Test
    void aFoundAssetCarriesTypeCacheEtagAndNosniff() {
        byte[] png = {1, 2, 3};
        when(assets.find("acme", "proj", "/icons/a.png"))
                .thenReturn(Optional.of(new WebuiAssetService.Asset(png, "image/png", "\"abc\"", false)));

        ResponseEntity<byte[]> response = controller.asset("acme", "proj", "/icons/a.png", request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsExactly(png);
        assertThat(response.getHeaders().getContentType()).hasToString("image/png");
        assertThat(response.getHeaders().getETag()).isEqualTo("\"abc\"");
        assertThat(response.getHeaders().getCacheControl()).contains("private").contains("max-age=300");
        assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeaders().getFirst("Content-Security-Policy")).isNull();
    }

    @Test
    void anSvgAlsoCarriesTheRestrictivePolicy() {
        when(assets.find("acme", "proj", "/icons/a.svg"))
                .thenReturn(Optional.of(new WebuiAssetService.Asset(new byte[] {1}, "image/svg+xml", "\"abc\"", true)));

        ResponseEntity<byte[]> response = controller.asset("acme", "proj", "/icons/a.svg", request);

        assertThat(response.getHeaders().getFirst("Content-Security-Policy"))
                .contains("default-src 'none'")
                .contains("sandbox");
    }

    @Test
    void whatIsNotServedIsAPlain404() {
        when(assets.find("acme", "proj", "/icons/gone.png")).thenReturn(Optional.empty());

        ResponseEntity<byte[]> response = controller.asset("acme", "proj", "/icons/gone.png", request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNull();
    }
}
