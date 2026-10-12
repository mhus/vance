package de.mhus.vance.brain.ai.audio;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AiAudioConfigTest {

    @Test
    void toString_masksTheApiKey() {
        AiAudioConfig cfg = new AiAudioConfig("openai", "openai", "tts-1", "sk-secret-123", null, 60);

        assertThat(cfg.toString())
                .doesNotContain("sk-secret-123")
                .contains("key=***")
                .contains("openai:tts-1");
    }

    @Test
    void toString_saysNoneWithoutAKey() {
        AiAudioConfig cfg = new AiAudioConfig("local", "local", "whisper", null, null, 60);

        assertThat(cfg.toString()).contains("key=none");
    }
}
