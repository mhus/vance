package de.mhus.vance.brain.oauthserver;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OAuthServerSettingsTest {

    private static OAuthServerSettings settings(String base) {
        return new OAuthServerSettings(base, true, 3600, 30, 365, true, true);
    }

    @Test
    void ownOrigin_isTheNormalizedOriginOfTheBaseUrl() {
        assertThat(settings("https://Vance.Example.com/").ownOrigin()).isEqualTo("https://vance.example.com:443");
        assertThat(settings("http://localhost:9797").ownOrigin()).isEqualTo("http://localhost:9797");
        assertThat(settings("").ownOrigin()).isNull();
    }

    @Test
    void plainHttp_isOnlyFlaggedForRemoteHosts() {
        assertThat(OAuthServerSettings.isPlainHttpOnRemoteHost("http://vance.example.com"))
                .isTrue();
        assertThat(OAuthServerSettings.isPlainHttpOnRemoteHost("http://10.42.40.10:9797"))
                .isTrue();
        assertThat(OAuthServerSettings.isPlainHttpOnRemoteHost("http://localhost:9797"))
                .isFalse();
        assertThat(OAuthServerSettings.isPlainHttpOnRemoteHost("http://127.0.0.1:9797"))
                .isFalse();
        assertThat(OAuthServerSettings.isPlainHttpOnRemoteHost("http://[::1]:9797"))
                .isFalse();
        assertThat(OAuthServerSettings.isPlainHttpOnRemoteHost("https://vance.example.com"))
                .isFalse();
        assertThat(OAuthServerSettings.isPlainHttpOnRemoteHost(null)).isFalse();
    }
}
