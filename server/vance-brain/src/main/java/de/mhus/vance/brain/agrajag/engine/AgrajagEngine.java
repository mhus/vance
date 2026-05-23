package de.mhus.vance.brain.agrajag.engine;

import de.mhus.vance.api.thinkprocess.CloseReason;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.api.toolhealth.ToolHealthClassification;
import de.mhus.vance.api.toolhealth.ToolHealthScope;
import de.mhus.vance.brain.thinkengine.SteerMessage;
import de.mhus.vance.brain.thinkengine.ThinkEngine;
import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.shared.toolhealth.ToolHealthService;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Agrajag — tool-health diagnostic service engine.
 *
 * <p>v1 ships as a deterministic stub: when spawned by
 * {@code AgrajagChecker} with the failure context in {@code engineParams},
 * the engine writes a {@code DEGRADED} status entry into the tool-health
 * document with the failure note attached and closes with
 * {@link CloseReason#DONE}. No LLM call, no probe-loop.
 *
 * <p>The point of v1 is the plumbing — system-session bootstrap, spawn
 * path, role-gated tool surface, recipe — being in place. The LLM-driven
 * diagnostic loop (probe-as-user vs. probe-as-system, history-aware
 * classification) will replace {@link #start} in a follow-up without
 * changing how callers spawn Agrajag.
 *
 * <p>Spec: {@code specification/agrajag-engine.md}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AgrajagEngine implements ThinkEngine {

    public static final String NAME = "agrajag";
    public static final String VERSION = "0.1.0";

    /** Default visibility horizon for the stubbed DEGRADED entry. */
    private static final Duration STUB_RECOVERY_HORIZON = Duration.ofMinutes(15);

    private final ThinkProcessService thinkProcessService;
    private final ToolHealthService toolHealthService;

    @Override public String name() { return NAME; }
    @Override public String title() { return "Agrajag"; }
    @Override public String description() {
        return "Tool-health diagnostic service engine. Investigates "
                + "ambiguous tool errors and writes status verdicts "
                + "into the tool-health document.";
    }
    @Override public String version() { return VERSION; }

    @Override
    public Set<String> roles() {
        return Set.of("tool-prober", "tool-health-writer", "tool-health-reader");
    }

    @Override
    public Set<String> allowedTools() {
        return Set.of(
                "tool_probe_as_user", "tool_probe_as_system",
                "tool_health_read",
                "tool_health_set_unavailable", "tool_health_set_available",
                "tool_health_set_cooldown", "tool_health_clear_cooldown");
    }

    @Override
    public void start(ThinkProcessDocument process, ThinkEngineContext context) {
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.RUNNING);

        Map<String, Object> params = process.getEngineParams();
        if (params == null) {
            log.warn("Agrajag spawned without engineParams id='{}'", process.getId());
            thinkProcessService.closeProcess(process.getId(), CloseReason.DONE);
            return;
        }

        String toolName = stringOrNull(params.get("toolName"));
        String scopeStr = stringOrNull(params.get("scope"));
        String scopeId = stringOrNull(params.get("scopeId"));
        String note = stringOrNull(params.get("note"));
        String signature = stringOrNull(params.get("errorSignature"));

        if (toolName == null || toolName.isBlank()
                || scopeStr == null || scopeStr.isBlank()) {
            log.warn("Agrajag stub: missing toolName/scope in engineParams id='{}' params={}",
                    process.getId(), params);
            thinkProcessService.closeProcess(process.getId(), CloseReason.DONE);
            return;
        }

        ToolHealthScope scope;
        try {
            scope = ToolHealthScope.valueOf(scopeStr.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            log.warn("Agrajag stub: unknown scope '{}' for id='{}'", scopeStr, process.getId());
            thinkProcessService.closeProcess(process.getId(), CloseReason.DONE);
            return;
        }

        Instant recoveryEstimate = Instant.now().plus(STUB_RECOVERY_HORIZON);
        String summary = String.format(
                "Agrajag stub diagnosis — signature='%s'%s. Replace with LLM "
                        + "probe-and-classify in a follow-up.",
                signature == null ? "unknown" : signature,
                note == null || note.isBlank() ? "" : "; original-note: " + note);

        log.info("Agrajag stub diagnosis id='{}' tool='{}' scope={} scopeId='{}' signature='{}'",
                process.getId(), toolName, scope, scopeId, signature);

        try {
            toolHealthService.markDegraded(
                    process.getTenantId(),
                    scope,
                    scopeId == null ? "" : scopeId,
                    toolName,
                    ToolHealthClassification.INTERMITTENT,
                    recoveryEstimate,
                    summary,
                    "agrajag-engine/" + process.getId());
        } catch (RuntimeException e) {
            log.warn("Agrajag stub: markDegraded failed id='{}': {}",
                    process.getId(), e.toString());
        }

        thinkProcessService.closeProcess(process.getId(), CloseReason.DONE);
    }

    @Override
    public void resume(ThinkProcessDocument process, ThinkEngineContext context) {
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.IDLE);
    }

    @Override
    public void suspend(ThinkProcessDocument process, ThinkEngineContext context) {
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.SUSPENDED);
    }

    @Override
    public void steer(ThinkProcessDocument process, ThinkEngineContext context,
                      SteerMessage message) {
        // Stub engine; spawned per failure, no inbox interaction.
    }

    @Override
    public void stop(ThinkProcessDocument process, ThinkEngineContext context) {
        thinkProcessService.closeProcess(process.getId(), CloseReason.STOPPED);
    }

    private static @Nullable String stringOrNull(@Nullable Object v) {
        return v == null ? null : v.toString();
    }
}
