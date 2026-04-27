package de.mhus.vance.api.vogon;

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
 * A complete strategy plan. Loaded from {@code strategies.yaml}
 * (bundled) or from the {@code strategies} Mongo collection
 * (tenant/project overrides). The plan is a linear list of phases
 * in v1 — fork / loop primitives extend that in v2.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StrategySpec {

    private String name = "";
    private @Nullable String description;
    private String version = "1";

    /** Phases run in order. v1: linear. */
    @Builder.Default
    private List<PhaseSpec> phases = new ArrayList<>();

    /** Default values for caller params, merged underneath the
     *  caller-supplied params at spawn time. */
    @Builder.Default
    private Map<String, Object> paramDefaults = new LinkedHashMap<>();
}
