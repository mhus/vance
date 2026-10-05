package de.mhus.vance.brain.audio;

import lombok.Builder;
import lombok.Value;
import org.jspecify.annotations.Nullable;

/**
 * Input for {@link AudioManipulationService#mix(MixRequest)}: two
 * audio documents mixed into one. The base is what the mix duration
 * follows ({@code duration=first}); the overlay is placed at
 * {@code overlayAtSeconds}, optionally ducked under the base (music
 * bed under narration) and gain-adjusted. This is the operation that
 * closes the {@code audio_speak} + {@code audio_music} workflow.
 */
@Value
@Builder
public class MixRequest {

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

    /** Base document path — exactly one of path/documentId. The mix
     *  duration follows the base. */
    @Nullable
    String path;

    /** Base document id — exactly one of path/documentId. */
    @Nullable
    String documentId;

    /** Overlay document path — exactly one of overlayPath/overlayDocumentId. */
    @Nullable
    String overlayPath;

    /** Overlay document id — exactly one of overlayPath/overlayDocumentId. */
    @Nullable
    String overlayDocumentId;

    /** Optional. Destination path. {@code null} or equal to the base
     *  ⇒ overwrite the base document. */
    @Nullable
    String targetPath;

    /** Linear gain applied to the base before mixing. Default 1.0. */
    double baseGain;

    /** Linear gain applied to the overlay before mixing. Default 1.0. */
    double overlayGain;

    /** Position of the overlay within the base timeline, in seconds.
     *  Default 0. {@code >= 0}. */
    double overlayAtSeconds;

    /** When true, the overlay (e.g. a music bed) is attenuated while
     *  the base (e.g. narration) speaks — sidechain compression. */
    boolean duckOverlay;

    /** Optional fade-out applied to the whole mix, in seconds. */
    double fadeOutSeconds;
}
