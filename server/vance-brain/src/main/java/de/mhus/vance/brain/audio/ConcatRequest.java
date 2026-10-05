package de.mhus.vance.brain.audio;

import java.util.List;
import lombok.Builder;
import lombok.Value;
import org.jspecify.annotations.Nullable;

/**
 * Input for {@link AudioManipulationService#concat(ConcatRequest)}:
 * clips joined in order into one audio document. Clips are normalised
 * to a common format by ffmpeg before joining; an optional
 * {@code crossfadeSeconds} blends neighbouring clips instead of
 * cutting hard.
 */
@Value
@Builder
public class ConcatRequest {

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

    /** Clip paths in join order — exactly one of paths/documentIds,
     *  at least 2 entries. */
    List<String> paths;

    /** Clip document ids in join order — exactly one of paths/documentIds. */
    List<String> documentIds;

    /** Optional. Destination path. Never overwrites a clip: when the
     *  target collides with one of the clips the call fails with
     *  {@code TARGET_BLOCKED}. */
    @Nullable
    String targetPath;

    /** Optional crossfade between neighbouring clips, in seconds.
     *  {@code 0} (default) ⇒ hard cut. */
    double crossfadeSeconds;
}
