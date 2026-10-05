package de.mhus.vance.brain.audio;

import lombok.Builder;
import lombok.Value;
import org.jspecify.annotations.Nullable;

/**
 * Input for {@link AudioManipulationService#probe(ProbeRequest)}.
 * Exactly one of {@code path} / {@code documentId} identifies the
 * source document. Nothing is written.
 */
@Value
@Builder
public class ProbeRequest {

    /** Required. Tenant scope. */
    String tenantId;

    /** Optional. Username of the caller. */
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
}
