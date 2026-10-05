package de.mhus.vance.brain.audio;

import lombok.Builder;
import lombok.Value;
import org.jspecify.annotations.Nullable;

/**
 * Input for {@link AudioManipulationService#convert(ConvertRequest)}:
 * re-encode an audio document into another format — the normaliser for
 * browser uploads (webm/ogg → mp3/wav) and the housekeeping tool for
 * loudness and size.
 */
@Value
@Builder
public class ConvertRequest {

    /** Required. Tenant scope. */
    String tenantId;

    /** Optional. Username of the caller — passed through to the
     *  document write as {@code createdBy}. */
    @Nullable
    String userId;

    /** Optional. Project scope. {@code null} ⇒ tenant-system project. */
    @Nullable
    String projectId;

    /** Optional. Process scope — setting cascade + progress side-channel. */
    @Nullable
    String processId;

    /** Source document path — exactly one of path/documentId. */
    @Nullable
    String path;

    /** Source document id — exactly one of path/documentId. */
    @Nullable
    String documentId;

    /** Optional. Destination path. {@code null} or equal to the source
     *  ⇒ overwrite source. */
    @Nullable
    String targetPath;

    /** Required. Target format: {@code mp3} or {@code wav}. */
    String format;

    /** Optional. Target sample rate in Hz (e.g. 16000, 44100). */
    @Nullable
    Integer sampleRate;

    /** Optional. Target channel count (1 = mono, 2 = stereo). */
    @Nullable
    Integer channels;

    /** Optional. Target bitrate in kbit/s (mp3 only). */
    @Nullable
    Integer bitrateKbps;

    /** When true, loudness is normalised (EBU R128) during the
     *  re-encode. Default false. */
    boolean normalize;
}
