package de.mhus.vance.brain.ai.audio;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Wraps raw PCM samples (16-bit little-endian) in a minimal RIFF/WAVE
 * container so a {@code response_format: "pcm"} answer becomes a
 * playable {@code .wav} document.
 *
 * <p>Exists because several TTS endpoints only serve raw PCM — Google's
 * Gemini TTS rejects every other {@code response_format} outright
 * (verified live 2026-10-05) — while the document store and every media
 * player expect a self-describing container.
 */
public final class PcmWav {

    private PcmWav() {}

    /** Build a WAV file from raw 16-bit LE PCM samples. */
    public static byte[] wrap(byte[] pcm, int sampleRate, int channels) {
        if (pcm == null || pcm.length == 0) {
            throw new IllegalArgumentException("pcm data is empty");
        }
        if (sampleRate <= 0 || channels <= 0) {
            throw new IllegalArgumentException("bad wav params: rate=" + sampleRate + " channels=" + channels);
        }
        int byteRate = sampleRate * channels * 2;
        int dataLen = pcm.length;
        ByteBuffer buf = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN);
        buf.put((byte) 'R').put((byte) 'I').put((byte) 'F').put((byte) 'F');
        buf.putInt(36 + dataLen);
        buf.put((byte) 'W').put((byte) 'A').put((byte) 'V').put((byte) 'E');
        buf.put((byte) 'f').put((byte) 'm').put((byte) 't').put((byte) ' ');
        buf.putInt(16); // PCM fmt chunk size
        buf.putShort((short) 1); // audio format: PCM
        buf.putShort((short) channels);
        buf.putInt(sampleRate);
        buf.putInt(byteRate);
        buf.putShort((short) (channels * 2)); // block align
        buf.putShort((short) 16); // bits per sample
        buf.put((byte) 'd').put((byte) 'a').put((byte) 't').put((byte) 'a');
        buf.putInt(dataLen);
        buf.put(pcm);
        return buf.array();
    }

    /** Default sample rate when a provider does not announce one. */
    public static final int DEFAULT_SAMPLE_RATE = 24000;
}
