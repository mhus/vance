package de.mhus.vance.api.runs;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Everything the detail page shows. The four blocks below turned out to
 * be common ground: a workflow's states and a strategy's phases are both
 * {@link #steps}, its {@code storeAs} variables and the strategy's flags
 * are both {@link #variables}, and both block on the very same inbox item
 * ({@link #waitingOnInboxItemId}) — which is the strongest reason to
 * think of them as one thing at all.
 *
 * <p>{@link #extra} is where the commonality stops. Rather than flatten a
 * journal timeline and a worker tree into whatever they share, each
 * source hands its own payload through and a source-specific component
 * renders it.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("runs")
public class RunDetailDto {

    private RunSummaryDto summary;

    @Builder.Default
    private List<RunStepDto> steps = new ArrayList<>();

    @Builder.Default
    private Map<String, Object> variables = new LinkedHashMap<>();

    @Builder.Default
    private List<RunChildDto> children = new ArrayList<>();

    @Builder.Default
    private List<RunLinkDto> links = new ArrayList<>();

    /** Set while the run waits on a person; the id of the inbox item to answer. */
    private @Nullable String waitingOnInboxItemId;

    /** Result payload of a finished run, when it produced one. */
    private @Nullable Map<String, Object> result;

    /** Why it failed, when it did. */
    private @Nullable String errorMessage;

    /**
     * What may be done to this run right now. Empty in v1 across all
     * sources — the field exists so the control surface lands later
     * without a DTO change, and so the UI can already render from data
     * instead of from a per-source case.
     */
    @Builder.Default
    private Set<RunAction> allowedActions = Set.of();

    /** Source-specific payload for the detail page's extra block. */
    private @Nullable Map<String, Object> extra;
}
