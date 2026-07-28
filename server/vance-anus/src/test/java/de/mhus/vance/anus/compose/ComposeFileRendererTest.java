package de.mhus.vance.anus.compose;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ComposeFileRendererTest {

    @Test
    void compose_default_singlePort_noAnus_noDebugUis() {
        String yaml = ComposeFileRenderer.renderCompose(new ComposeSetupState());

        assertThat(yaml).contains("mongodb:").contains("brain:").contains("face:");
        assertThat(yaml).contains("redis:");
        assertThat(yaml).contains("VANCE_REDIS_ENABLED: \"true\"");
        assertThat(yaml).contains("redis-data:");
        // Only the Vance/face port is published; brain/mongo/redis stay internal.
        assertThat(yaml).contains("${FACE_PORT:-8080}:80");
        assertThat(yaml).doesNotContain("${BRAIN_PORT");
        assertThat(yaml).doesNotContain("${MONGO_PORT");
        assertThat(yaml).doesNotContain("${REDIS_PORT");
        // anus service and debug UIs are opt-in.
        assertThat(yaml).doesNotContain("anus:");
        assertThat(yaml).doesNotContain("mongo-express:");
        assertThat(yaml).doesNotContain("redis-commander:");
        assertThat(yaml).doesNotContain("caddy:");
    }

    @Test
    void compose_exposeToggles_publishHostPorts() {
        ComposeSetupState s = new ComposeSetupState();
        s.setExposeBrainPort(true);
        s.setExposeMongoPort(true);
        s.setExposeRedisPort(true);

        String yaml = ComposeFileRenderer.renderCompose(s);

        assertThat(yaml).contains("${BRAIN_PORT:-9990}:9990");
        assertThat(yaml).contains("${MONGO_PORT:-27017}:27017");
        assertThat(yaml).contains("${REDIS_PORT:-6379}:6379");
    }

    @Test
    void compose_redisDisabled_dropsRedisServiceAndBrainEnv() {
        ComposeSetupState s = new ComposeSetupState();
        s.setRedisEnabled(false);

        String yaml = ComposeFileRenderer.renderCompose(s);

        assertThat(yaml).doesNotContain("redis:");
        assertThat(yaml).doesNotContain("VANCE_REDIS_ENABLED");
        assertThat(yaml).doesNotContain("redis-data:");
        assertThat(yaml).contains("brain:");
    }

    @Test
    void compose_anusServiceEnabled_addsAnus() {
        ComposeSetupState s = new ComposeSetupState();
        s.setAnusServiceEnabled(true);

        String yaml = ComposeFileRenderer.renderCompose(s);

        assertThat(yaml).contains("anus:").contains("profiles: [\"tools\"]");
        assertThat(yaml).contains("anus-data:");
    }

    @Test
    void compose_externalWithCaddy_frontsTlsAndKeepsFaceInternal() {
        ComposeSetupState s = new ComposeSetupState();
        s.setExternalAccess(true);
        s.setExternalUrl("https://vance.example.de");
        s.setCaddyTls(true);

        String yaml = ComposeFileRenderer.renderCompose(s);

        assertThat(yaml).contains("caddy:");
        assertThat(yaml).contains("caddy reverse-proxy --from ${VANCE_EXTERNAL_HOST} --to face:80");
        assertThat(yaml).contains("caddy-data:").contains("caddy-config:");
        // Caddy is the only published front door — face is not host-exposed.
        assertThat(yaml).doesNotContain("${FACE_PORT");
    }

    @Test
    void compose_externalHttpOnly_noCaddy_facePublished() {
        ComposeSetupState s = new ComposeSetupState();
        s.setExternalAccess(true);
        s.setExternalUrl("https://tunnel.ngrok.app");
        s.setCaddyTls(false);

        String yaml = ComposeFileRenderer.renderCompose(s);

        assertThat(yaml).doesNotContain("caddy:");
        assertThat(yaml).contains("${FACE_PORT:-8080}:80");
    }

    @Test
    void compose_toolsEnabled_addsDebugUis() {
        ComposeSetupState s = new ComposeSetupState();
        s.setToolsEnabled(true);

        String yaml = ComposeFileRenderer.renderCompose(s);

        assertThat(yaml).contains("mongo-express:");
        assertThat(yaml).contains("redis-commander:");
    }

    @Test
    void compose_toolsEnabledButRedisOff_dropsRedisCommanderOnly() {
        ComposeSetupState s = new ComposeSetupState();
        s.setToolsEnabled(true);
        s.setRedisEnabled(false);

        String yaml = ComposeFileRenderer.renderCompose(s);

        assertThat(yaml).contains("mongo-express:");
        assertThat(yaml).doesNotContain("redis-commander:");
    }

    @Test
    void env_reflectsToggles() {
        ComposeSetupState s = new ComposeSetupState();
        s.setFookEnabled(false);
        s.setRedisEnabled(false);
        s.setLanguageName("German");
        s.setLanguageCode("de");

        Map<String, String> env = ComposeFileRenderer.renderEnv(s);

        assertThat(env).containsEntry("VANCE_FOOK_ENABLED", "false");
        assertThat(env).containsEntry("VANCE_REDIS_ENABLED", "false");
        assertThat(env).containsEntry("VANCE_DEFAULT_LANGUAGE", "German");
        assertThat(env).containsEntry("VANCE_DEFAULT_LANGUAGE_CODE", "de");
        assertThat(env).doesNotContainKey("MONGO_EXPRESS_USERNAME");
    }

    @Test
    void env_local_defaultsPublicBaseUrlToLocalhostVancePort() {
        ComposeSetupState s = new ComposeSetupState();
        s.setFacePort(8090);

        Map<String, String> env = ComposeFileRenderer.renderEnv(s);

        assertThat(env).containsEntry("VANCE_ACCESS_MODE", "local");
        assertThat(env).containsEntry("VANCE_WEB_PUBLICBASEURL", "http://localhost:8090");
        assertThat(env).containsEntry("VANCE_WEB_COOKIES_SECURE", "false");
        assertThat(env).doesNotContainKey("VANCE_EXTERNAL_URL");
    }

    @Test
    void env_externalHttps_setsSecureCookiesAndHost() {
        ComposeSetupState s = new ComposeSetupState();
        s.setExternalAccess(true);
        s.setExternalUrl("https://vance.example.de/");
        s.setCaddyTls(true);

        Map<String, String> env = ComposeFileRenderer.renderEnv(s);

        assertThat(env).containsEntry("VANCE_ACCESS_MODE", "external");
        assertThat(env).containsEntry("VANCE_WEB_PUBLICBASEURL", "https://vance.example.de");
        assertThat(env).containsEntry("VANCE_WEB_COOKIES_SECURE", "true");
        assertThat(env).containsEntry("VANCE_EXTERNAL_HOST", "vance.example.de");
        assertThat(env).containsEntry("VANCE_CADDY_TLS", "auto");
    }

    @Test
    void env_externalHttpUrl_cookiesNotSecure() {
        ComposeSetupState s = new ComposeSetupState();
        s.setExternalAccess(true);
        s.setExternalUrl("http://192.168.1.10:8080");
        s.setCaddyTls(false);

        Map<String, String> env = ComposeFileRenderer.renderEnv(s);

        assertThat(env).containsEntry("VANCE_WEB_COOKIES_SECURE", "false");
        assertThat(env).containsEntry("VANCE_CADDY_TLS", "off");
        assertThat(env).doesNotContainKey("VANCE_EXTERNAL_HOST");
    }

    @Test
    void env_exposeFlagsRoundTripped() {
        ComposeSetupState s = new ComposeSetupState();
        s.setExposeBrainPort(true);
        s.setAnusServiceEnabled(true);

        Map<String, String> env = ComposeFileRenderer.renderEnv(s);

        assertThat(env).containsEntry("VANCE_EXPOSE_BRAIN", "true");
        assertThat(env).containsEntry("VANCE_EXPOSE_MONGO", "false");
        assertThat(env).containsEntry("VANCE_EXPOSE_REDIS", "false");
        assertThat(env).containsEntry("VANCE_ANUS_SERVICE", "true");
    }

    @Test
    void env_toolsEnabled_addsDebugCredentials() {
        ComposeSetupState s = new ComposeSetupState();
        s.setToolsEnabled(true);

        Map<String, String> env = ComposeFileRenderer.renderEnv(s);

        assertThat(env).containsKeys("MONGO_EXPRESS_USERNAME",
                "MONGO_EXPRESS_PASSWORD", "MONGO_EXPRESS_PORT", "REDIS_UI_PORT");
    }

    @Test
    void externalHost_stripsSchemeAndPath() {
        ComposeSetupState s = new ComposeSetupState();
        s.setExternalUrl("https://vance.example.de/app/");

        assertThat(ComposeFileRenderer.externalHost(s)).isEqualTo("vance.example.de");
    }
}
