package de.mhus.vance.brain.webdav;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WebDavLockServiceTest {

    @Test
    void parseToken_stripsOpaqueLockTokenWrapping() {
        assertThat(WebDavLockService.parseToken("<opaquelocktoken:abc-123>")).isEqualTo("abc-123");
    }

    @Test
    void parseToken_stripsIfHeaderParenthesesWrapping() {
        assertThat(WebDavLockService.parseToken("(<opaquelocktoken:abc-123>)")).isEqualTo("abc-123");
    }

    @Test
    void parseToken_bareTokenPassesThrough() {
        assertThat(WebDavLockService.parseToken("abc-123")).isEqualTo("abc-123");
    }

    @Test
    void parseToken_nullYieldsEmpty() {
        assertThat(WebDavLockService.parseToken(null)).isEmpty();
    }
}
