package de.mhus.vance.brain.ai.audio;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AudioSourceTest {

    @Test
    void known_mimes_map_to_wire_formats() {
        assertThat(AudioSource.formatForMime("audio/wav")).isEqualTo("wav");
        assertThat(AudioSource.formatForMime("audio/mpeg")).isEqualTo("mp3");
        assertThat(AudioSource.formatForMime("audio/mp4")).isEqualTo("m4a");
        assertThat(AudioSource.formatForMime("audio/x-m4a")).isEqualTo("m4a");
        assertThat(AudioSource.formatForMime("audio/ogg")).isEqualTo("ogg");
        assertThat(AudioSource.formatForMime("audio/webm")).isEqualTo("webm");
        assertThat(AudioSource.formatForMime("audio/flac")).isEqualTo("flac");
    }

    @Test
    void mime_with_parameters_is_trimmed_to_the_essence() {
        assertThat(AudioSource.formatForMime("audio/pcm;rate=24000;channels=1")).isEqualTo("wav");
        assertThat(AudioSource.formatForMime("audio/mpeg; charset=binary")).isEqualTo("mp3");
    }

    @Test
    void unknown_audio_subtype_passes_through() {
        assertThat(AudioSource.formatForMime("audio/aac")).isEqualTo("m4a");
        assertThat(AudioSource.formatForMime("audio/x-custom")).isEqualTo("x-custom");
    }

    @Test
    void blank_mime_defaults_to_wav() {
        assertThat(AudioSource.formatForMime(null)).isEqualTo("wav");
        assertThat(AudioSource.formatForMime("  ")).isEqualTo("wav");
    }
}
