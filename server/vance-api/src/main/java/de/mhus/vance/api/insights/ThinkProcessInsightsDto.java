package de.mhus.vance.api.insights;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Read-only view of a think-process for the insights inspector.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("insights")
public class ThinkProcessInsightsDto {

    /** Mongo id — used as the addressing key for the inspector. */
    private String id;

    private String sessionId;

    private String name;

    private @Nullable String title;

    private String thinkEngine;

    private @Nullable String thinkEngineVersion;

    private @Nullable String goal;

    private @Nullable String recipeName;

    private String status;

    private @Nullable String parentProcessId;

    @Builder.Default
    private Map<String, Object> engineParams = new LinkedHashMap<>();

    @Builder.Default
    private List<ActiveSkillInsightsDto> activeSkills = new ArrayList<>();

    @Builder.Default
    private List<PendingMessageInsightsDto> pendingMessages = new ArrayList<>();

    private @Nullable Instant createdAt;

    private @Nullable Instant updatedAt;
}
