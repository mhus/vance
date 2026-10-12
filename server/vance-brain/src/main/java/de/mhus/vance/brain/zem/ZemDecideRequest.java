package de.mhus.vance.brain.zem;

import de.mhus.vance.api.zem.ZemQuestionDto;
import java.util.List;
import lombok.Builder;
import lombok.Singular;
import lombok.Value;
import org.jspecify.annotations.Nullable;

/**
 * What a caller asks {@link ZemService} to prepare: a profile, inline
 * questions, or both (inline questions replace profile questions of the
 * same name and append the rest). The state is not part of it — a
 * prepared call is reused for many states (bulk callers).
 */
@Value
@Builder
public class ZemDecideRequest {

    String tenantId;

    @Nullable
    String projectId;

    @Nullable
    String processId;

    /** Caller's username, for attribution. */
    @Nullable
    String userId;

    /** Recipe name of an {@code engine: zem} profile. */
    @Nullable
    String profile;

    @Singular
    List<ZemQuestionDto> questions;

    /** Model alias or {@code instance:model}; overrides the profile's. */
    @Nullable
    String model;

    /** Field paths to project the state onto; overrides the profile's. */
    @Nullable
    List<String> stateSelect;
}
