package de.mhus.vance.anus.compose;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ComposeFileRendererTest {

    @Test
    void compose_default_hasRedisAndAnus_noDebugUis() {
        String yaml = ComposeFileRenderer.renderCompose(new ComposeSetupState());

        assertThat(yaml).contains("mongodb:").contains("brain:").contains("face:");
        assertThat(yaml).contains("redis:");
        assertThat(yaml).contains("VANCE_REDIS_ENABLED: \"true\"");
        assertThat(yaml).contains("redis-data:");
        assertThat(yaml).contains("anus:").contains("profiles: [\"tools\"]");
        assertThat(yaml).doesNotContain("mongo-express:");
        assertThat(yaml).doesNotContain("redis-commander:");
    }

    @Test
    void compose_redisDisabled_dropsRedisServiceAndBrainEnv() {
        ComposeSetupState s = new ComposeSetupState();
        s.setRedisEnabled(false);

        String yaml = ComposeFileRenderer.renderCompose(s);

        assertThat(yaml).doesNotContain("redis:");
        assertThat(yaml).doesNotContain("VANCE_REDIS_ENABLED");
        assertThat(yaml).doesNotContain("redis-data:");
        // brain must still depend on mongodb only
        assertThat(yaml).contains("brain:");
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
    void env_toolsEnabled_addsDebugCredentials() {
        ComposeSetupState s = new ComposeSetupState();
        s.setToolsEnabled(true);

        Map<String, String> env = ComposeFileRenderer.renderEnv(s);

        assertThat(env).containsKeys("MONGO_EXPRESS_USERNAME",
                "MONGO_EXPRESS_PASSWORD", "MONGO_EXPRESS_PORT", "REDIS_UI_PORT");
    }
}
