package de.mhus.vance.brain.audio;

import lombok.Builder;
import lombok.Value;
import org.jspecify.annotations.Nullable;

/**
 * Input for {@link AudioManipulationService#trim(TrimRequest)}.
 * Cuts the window {@code [startSeconds, endSeconds)} out of the source
 * and optionally fades the cut in/out. {@code endSeconds == null} ⇒
 * end of the source. The result is written to {@code targetPath} (if
 * set and distinct from the source) or overwrites the source, in which
 * case document-versioning archives the prior version.
 */
@Value
@Builder
public class TrimRequest {

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

    /** Cut start in seconds. Must be {@code >= 0}. */
    double startSeconds;

    /** Cut end in seconds, exclusive. {@code null} ⇒ end of source.
     *  Must be {@code > startSeconds} when set. */
    @Nullable
    Double endSeconds;

    /** Optional fade-in applied to the cut, in seconds. {@code >= 0}. */
    double fadeInSeconds;

    /** Optional fade-out applied to the cut, in seconds. {@code >= 0}
     *  and less than the cut length. */
    double fadeOutSeconds;
}
