package de.mhus.vance.brain.ai.audio;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AudioMimeTypeSnifferTest {

    @Test
    void id3_header_sniffs_as_mpeg() {
        byte[] bytes = {'I', 'D', '3', 3, 0, 0, 0, 0, 0, 0, 0, 0};
        assertThat(AudioMimeTypeSniffer.sniff(bytes, "audio/wav")).isEqualTo("audio/mpeg");
    }

    @Test
    void mpeg_frame_sync_sniffs_as_mpeg() {
        byte[] bytes = {(byte) 0xFF, (byte) 0xFB, 0x10, 0x00};
        assertThat(AudioMimeTypeSniffer.sniff(bytes, "audio/wav")).isEqualTo("audio/mpeg");
    }

    @Test
    void riff_wave_sniffs_as_wav() {
        byte[] bytes = {'R', 'I', 'F', 'F', 36, 0, 0, 0, 'W', 'A', 'V', 'E'};
        assertThat(AudioMimeTypeSniffer.sniff(bytes, "audio/mpeg")).isEqualTo("audio/wav");
    }

    @Test
    void flac_and_ogg_and_mp4_are_recognised() {
        assertThat(AudioMimeTypeSniffer.sniff(new byte[] {'f', 'L', 'a', 'C', 0, 0}, "x"))
                .isEqualTo("audio/flac");
        assertThat(AudioMimeTypeSniffer.sniff(new byte[] {'O', 'g', 'g', 'S', 0, 0}, "x"))
                .isEqualTo("audio/ogg");
        byte[] mp4 = {0, 0, 0, 24, 'f', 't', 'y', 'p', 'M', '4', 'A', ' '};
        assertThat(AudioMimeTypeSniffer.sniff(mp4, "x")).isEqualTo("audio/mp4");
    }

    @Test
    void unknown_payload_falls_back_to_the_advertised_mime() {
        byte[] bytes = {1, 2, 3, 4, 5, 6, 7, 8};
        assertThat(AudioMimeTypeSniffer.sniff(bytes, "audio/wav")).isEqualTo("audio/wav");
    }

    @Test
    void short_or_empty_payload_falls_back() {
        assertThat(AudioMimeTypeSniffer.sniff(new byte[] {'I', 'D'}, "audio/mpeg"))
                .isEqualTo("audio/mpeg");
    }
}
