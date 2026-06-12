package de.mhus.vance.api.insights;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * One project owned by a brain-pod, as the cluster dashboard sees it.
 * Carries the user-visible attributes that complement the bare project
 * name: lifecycle status, lifecycle type, and the resource-score the
 * project contributes to its home pod.
 *
 * <p>Nullable fields default to {@code null} when the controller could
 * not resolve the project (e.g. removed between heartbeat and read) —
 * the pod's denormalised list lags the canonical {@code projects}
 * collection by up to one heartbeat window.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("insights")
public class BrainPodProjectInsightsDto {

    /** Project name without the {@code <tenantId>/} prefix. */
    private String name;

    /** {@code ProjectStatus.name()} — {@code INIT}, {@code RUNNING}, …  */
    private @Nullable String status;

    /** {@code LifecycleType.name()} — {@code HOMELESS}, {@code EPHEMERAL}, {@code PERMANENT}. */
    private @Nullable String lifecycleType;

    /**
     * Score the project contributes to {@code resourcesCurrentScore} on
     * its home pod. {@code 0} when the project couldn't be resolved.
     */
    private int homeResourceScore;
}
