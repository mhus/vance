package de.mhus.vance.brain.tools.web;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.brain.tools.web.YouTubeValidatorService.YouTubeOEmbedHttp;
import java.net.URI;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link YouTubeValidatorService}. Two surfaces:
 * the URL parser (lots of URL shapes in the wild) and the
 * embeddability probe with cache reuse.
 */
class YouTubeValidatorServiceTest {

    @Test
    void extractVideoId_recognisesYoutubeShortUrl() {
        assertThat(YouTubeValidatorService.extractVideoId(
                "https://youtu.be/dQw4w9WgXcQ")).isEqualTo("dQw4w9WgXcQ");
    }

    @Test
    void extractVideoId_recognisesWatchUrl() {
        assertThat(YouTubeValidatorService.extractVideoId(
                "https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
                .isEqualTo("dQw4w9WgXcQ");
    }

    @Test
    void extractVideoId_watchUrl_withExtraParams() {
        assertThat(YouTubeValidatorService.extractVideoId(
                "https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=120s&list=PLabc"))
                .isEqualTo("dQw4w9WgXcQ");
    }

    @Test
    void extractVideoId_embedUrl() {
        assertThat(YouTubeValidatorService.extractVideoId(
                "https://www.youtube.com/embed/dQw4w9WgXcQ"))
                .isEqualTo("dQw4w9WgXcQ");
    }

    @Test
    void extractVideoId_shortsUrl() {
        assertThat(YouTubeValidatorService.extractVideoId(
                "https://www.youtube.com/shorts/dQw4w9WgXcQ?feature=share"))
                .isEqualTo("dQw4w9WgXcQ");
    }

    @Test
    void extractVideoId_mobileUrl() {
        assertThat(YouTubeValidatorService.extractVideoId(
                "https://m.youtube.com/watch?v=dQw4w9WgXcQ"))
                .isEqualTo("dQw4w9WgXcQ");
    }

    @Test
    void extractVideoId_nocookieEmbedUrl() {
        assertThat(YouTubeValidatorService.extractVideoId(
                "https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ"))
                .isEqualTo("dQw4w9WgXcQ");
    }

    @Test
    void extractVideoId_bareEleven_charIdentifier() {
        assertThat(YouTubeValidatorService.extractVideoId("dQw4w9WgXcQ"))
                .isEqualTo("dQw4w9WgXcQ");
    }

    @Test
    void extractVideoId_idWithUnderscoreAndDash() {
        assertThat(YouTubeValidatorService.extractVideoId(
                "https://youtu.be/abc_def-XYZ")).isEqualTo("abc_def-XYZ");
    }

    @Test
    void extractVideoId_nonYouTubeHost_returnsNull() {
        assertThat(YouTubeValidatorService.extractVideoId(
                "https://vimeo.com/123456789")).isNull();
        assertThat(YouTubeValidatorService.extractVideoId(
                "https://example.com/watch?v=dQw4w9WgXcQ")).isNull();
    }

    @Test
    void extractVideoId_invalidLength_returnsNull() {
        assertThat(YouTubeValidatorService.extractVideoId(
                "https://youtu.be/toolong-id-here-fail")).isNull();
        assertThat(YouTubeValidatorService.extractVideoId(
                "https://youtu.be/short")).isNull();
    }

    @Test
    void extractVideoId_invalidChars_returnsNull() {
        assertThat(YouTubeValidatorService.extractVideoId(
                "https://youtu.be/abc!def@xyz")).isNull();
    }

    @Test
    void extractVideoId_blankInput_returnsNull() {
        assertThat(YouTubeValidatorService.extractVideoId(null)).isNull();
        assertThat(YouTubeValidatorService.extractVideoId("")).isNull();
        assertThat(YouTubeValidatorService.extractVideoId("   ")).isNull();
    }

    @Test
    void isEmbeddable_status200_returnsTrue() {
        StubOEmbed http = new StubOEmbed();
        http.statusToReturn = 200;
        YouTubeValidatorService svc = new YouTubeValidatorService(http);
        assertThat(svc.isEmbeddable("dQw4w9WgXcQ")).isTrue();
        assertThat(http.calls).isEqualTo(1);
    }

    @Test
    void isEmbeddable_status401_returnsFalse() {
        // 401 from oEmbed = embed disabled by uploader, or private video.
        StubOEmbed http = new StubOEmbed();
        http.statusToReturn = 401;
        YouTubeValidatorService svc = new YouTubeValidatorService(http);
        assertThat(svc.isEmbeddable("dQw4w9WgXcQ")).isFalse();
    }

    @Test
    void isEmbeddable_status404_returnsFalse() {
        StubOEmbed http = new StubOEmbed();
        http.statusToReturn = 404;
        YouTubeValidatorService svc = new YouTubeValidatorService(http);
        assertThat(svc.isEmbeddable("dQw4w9WgXcQ")).isFalse();
    }

    @Test
    void isEmbeddable_blankId_returnsFalseWithoutHttp() {
        StubOEmbed http = new StubOEmbed();
        YouTubeValidatorService svc = new YouTubeValidatorService(http);
        assertThat(svc.isEmbeddable(null)).isFalse();
        assertThat(svc.isEmbeddable("")).isFalse();
        assertThat(http.calls).isZero();
    }

    @Test
    void isEmbeddable_secondCall_servedFromCache() {
        StubOEmbed http = new StubOEmbed();
        http.statusToReturn = 200;
        YouTubeValidatorService svc = new YouTubeValidatorService(http);
        assertThat(svc.isEmbeddable("dQw4w9WgXcQ")).isTrue();
        assertThat(svc.isEmbeddable("dQw4w9WgXcQ")).isTrue();
        assertThat(svc.isEmbeddable("dQw4w9WgXcQ")).isTrue();
        // First call probes; the next two come from the in-memory map.
        assertThat(http.calls).isEqualTo(1);
    }

    @Test
    void isEmbeddable_probeException_returnsFalseAndCachesNegative() {
        StubOEmbed http = new StubOEmbed();
        http.throwOnCall = true;
        YouTubeValidatorService svc = new YouTubeValidatorService(http);
        assertThat(svc.isEmbeddable("dQw4w9WgXcQ")).isFalse();
        // Negative verdict should also be cached so we don't hammer
        // oEmbed every search call for a broken video.
        http.throwOnCall = false;
        http.statusToReturn = 200;
        assertThat(svc.isEmbeddable("dQw4w9WgXcQ")).isFalse();
    }

    // ──────────────────────────────────────────────────────────────────

    private static class StubOEmbed implements YouTubeOEmbedHttp {
        int statusToReturn = 200;
        boolean throwOnCall = false;
        int calls;

        @Override
        public int statusCode(URI oembedUri) throws Exception {
            calls++;
            if (throwOnCall) throw new RuntimeException("simulated network failure");
            return statusToReturn;
        }
    }
}
