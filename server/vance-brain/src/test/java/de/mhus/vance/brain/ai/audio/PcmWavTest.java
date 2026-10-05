package de.mhus.vance.brain.ai.audio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

class PcmWavTest {

    @Test
    void wrap_builds_a_valid_riff_header() {
        byte[] pcm = new byte[160]; // 80 samples mono
        byte[] wav = PcmWav.wrap(pcm, 24000, 1);

        assertThat(wav).hasSize(44 + 160);
        assertThat(new String(wav, 0, 4)).isEqualTo("RIFF");
        assertThat(new String(wav, 8, 4)).isEqualTo("WAVE");
        // chunk size = 36 + data
        ByteBuffer buf = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        assertThat(buf.getInt(4)).isEqualTo(36 + 160);
        assertThat(buf.getShort(20)).isEqualTo((short) 1); // PCM
        assertThat(buf.getShort(22)).isEqualTo((short) 1); // channels
        assertThat(buf.getInt(24)).isEqualTo(24000); // sample rate
        assertThat(buf.getInt(40)).isEqualTo(160); // data length
    }

    @Test
    void wrapped_pcm_sniffs_as_wav() {
        byte[] wav = PcmWav.wrap(new byte[64], 24000, 1);
        assertThat(AudioMimeTypeSniffer.sniff(wav, "audio/mpeg")).isEqualTo("audio/wav");
    }

    @Test
    void empty_pcm_is_rejected() {
        assertThatThrownBy(() -> PcmWav.wrap(new byte[0], 24000, 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void bad_params_are_rejected() {
        assertThatThrownBy(() -> PcmWav.wrap(new byte[2], 0, 1)).isInstanceOf(IllegalArgumentException.class);
    }
}
