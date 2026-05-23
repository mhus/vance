package de.mhus.vance.brain.agrajag.engine;

import de.mhus.vance.api.thinkprocess.CloseReason;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.api.toolhealth.ToolHealthClassification;
import de.mhus.vance.api.toolhealth.ToolHealthScope;
import de.mhus.vance.brain.ai.EngineChatFactory;
import de.mhus.vance.brain.thinkengine.SteerMessage;
import de.mhus.vance.brain.thinkengine.ThinkEngine;
import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.shared.toolhealth.ToolHealthCooldown;
import de.mhus.vance.shared.toolhealth.ToolHealthDocument;
import de.mhus.vance.shared.toolhealth.ToolHealthHistoryEntry;
import de.mhus.vance.shared.toolhealth.ToolHealthService;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Agrajag — tool-health diagnostic service engine.
 *
 * <p>Spawned by {@code AgrajagChecker} for each {@code UNCLEAR} tool-error
 * classification. Reads the failure context from {@code engineParams},
 * loads the existing tool-health doc (if any) for history-aware context,
 * makes a single LLM call requesting a strict JSON object, and writes
 * the verdict back through {@link ToolHealthService} (markUnavailable /
 * markDegraded / markAvailable / setCooldown).
 *
 * <p>The engine is single-shot — one {@code start()}, no inbox, no
 * resume loop. Failures (LLM call errors, unparseable output) fall back
 * to {@code DEGRADED} with an audit note explaining what went wrong, so
 * the brain never loses the failure event.
 *
 * <p>Spec: {@code specification/agrajag-engine.md}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AgrajagEngine implements ThinkEngine {

    public static final String NAME = "agrajag";
    public static final String VERSION = "0.2.0";

    /** Recovery horizon stamped on fallback DEGRADED entries (LLM error / unparseable). */
    private static final Duration FALLBACK_RECOVERY_HORIZON = Duration.ofMinutes(15);

    /** How many history entries we include in the LLM prompt. */
    private static final int HISTORY_CONTEXT_LIMIT = 6;

    private static final String SYSTEM_PROMPT = """
            You are Agrajag, the tool-health diagnostic engine for Vance.

            Input: one tool error report plus the recent history of the
            tool-health document (if any). Output: exactly one JSON object
            matching the schema below — no prose before or after, no
            markdown fences, no commentary.

            Schema (all fields required except where marked optional):
            {
              "classification": <one of
                  "TECHNICALLY_BROKEN" | "USER_SPECIFIC_TECHNICAL" |
                  "USER_PERMISSION"    | "USER_INPUT"              |
                  "INTERMITTENT"       | "WORKING"                  |
                  "UNCLEAR">,
              "expectedRecoveryAt": "<ISO-8601 instant>" | null,
              "humanNote": "<short audit string, ≤ 300 chars>",
              "userActionHint": "<≤ 200 chars, only when USER_SPECIFIC_TECHNICAL>" | null,
              "cooldownAdjustments": [                       // may be empty
                {"errorSignature": "<short key>",
                 "duration": "<ISO-8601 Duration like PT30M>",
                 "userId": "<userId>" | null}
              ]
            }

            Decision guidelines:
            - Prefer history over speculation. Repeated INTERMITTENT in the
              last hour → suggest a longer expectedRecoveryAt.
            - TECHNICALLY_BROKEN when every user is plausibly affected.
              USER_SPECIFIC_TECHNICAL when only the originating user's
              credentials look at fault (expired token, suspended account).
            - USER_PERMISSION and USER_INPUT are NOT tool-health issues;
              return them only if the evidence is unambiguous, and leave
              expectedRecoveryAt null.
            - UNCLEAR + expectedRecoveryAt null is allowed when evidence
              is insufficient. Use cooldownAdjustments to throttle further
              diagnosis attempts on the same signature.
            """;

    private final ThinkProcessService thinkProcessService;
    private final ToolHealthService toolHealthService;
    private final EngineChatFactory engineChatFactory;
    private final ObjectMapper objectMapper;

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

    // ────────────────── Lifecycle ──────────────────

    @Override
    public void start(ThinkProcessDocument process, ThinkEngineContext ctx) {
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.RUNNING);
        try {
            runDiagnosis(process, ctx);
        } catch (RuntimeException e) {
            log.warn("Agrajag id='{}' diagnosis failed unhandled: {}",
                    process.getId(), e.toString());
        } finally {
            if (process.getStatus() != ThinkProcessStatus.CLOSED) {
                thinkProcessService.closeProcess(process.getId(), CloseReason.DONE);
            }
        }
    }

    @Override
    public void resume(ThinkProcessDocument process, ThinkEngineContext context) {
        // Diagnostic engine is single-shot — resume reruns the analysis.
        if (process.getStatus() == ThinkProcessStatus.CLOSED) return;
        start(process, context);
    }

    @Override
    public void suspend(ThinkProcessDocument process, ThinkEngineContext context) {
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.SUSPENDED);
    }

    @Override
    public void steer(ThinkProcessDocument process, ThinkEngineContext context,
                      SteerMessage message) {
        // single-shot — no inbox interaction.
    }

    @Override
    public void stop(ThinkProcessDocument process, ThinkEngineContext context) {
        thinkProcessService.closeProcess(process.getId(), CloseReason.STOPPED);
    }

    // ────────────────── Core ──────────────────

    private void runDiagnosis(ThinkProcessDocument process, ThinkEngineContext ctx) {
        Map<String, Object> params = process.getEngineParams();
        if (params == null) {
            log.warn("Agrajag id='{}' spawned without engineParams", process.getId());
            return;
        }

        String toolName = stringOrNull(params.get("toolName"));
        String scopeStr = stringOrNull(params.get("scope"));
        String scopeId = nullToEmpty(stringOrNull(params.get("scopeId")));
        String signature = stringOrNull(params.get("errorSignature"));
        String originatingUserId = stringOrNull(params.get("originatingUserId"));
        String triggerNote = stringOrNull(params.get("note"));

        if (toolName == null || toolName.isBlank()
                || scopeStr == null || scopeStr.isBlank()) {
            log.warn("Agrajag id='{}' missing toolName/scope in engineParams",
                    process.getId());
            return;
        }
        ToolHealthScope scope;
        try {
            scope = ToolHealthScope.valueOf(scopeStr.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            log.warn("Agrajag id='{}' unknown scope '{}'", process.getId(), scopeStr);
            return;
        }

        Optional<ToolHealthDocument> existing = toolHealthService.lookup(
                process.getTenantId(),
                /*sessionId*/ null,
                originatingUserId,
                process.getProjectId() == null || process.getProjectId().isBlank()
                        ? null : process.getProjectId(),
                toolName);

        String userPrompt = renderUserPrompt(
                toolName, scope, scopeId, signature,
                originatingUserId, triggerNote, existing.orElse(null));

        Map<String, Object> output;
        try {
            output = callLlm(process, ctx, userPrompt);
        } catch (RuntimeException e) {
            log.warn("Agrajag id='{}' LLM call failed: {}",
                    process.getId(), e.toString());
            fallbackDegraded(process, scope, scopeId, toolName,
                    "Agrajag LLM call failed: " + e.getMessage());
            return;
        }
        if (output == null) {
            fallbackDegraded(process, scope, scopeId, toolName,
                    "Agrajag could not parse LLM output");
            return;
        }

        applyDiagnosis(process, scope, scopeId, toolName, output);
    }

    private @Nullable Map<String, Object> callLlm(
            ThinkProcessDocument process, ThinkEngineContext ctx, String userPrompt) {
        EngineChatFactory.EngineChatBundle bundle =
                engineChatFactory.forProcess(process, ctx, NAME);

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(SYSTEM_PROMPT));
        messages.add(UserMessage.from(userPrompt));

        ChatResponse response = bundle.chat().chatModel().chat(
                ChatRequest.builder().messages(messages).build());
        if (response.aiMessage() == null) return null;
        String text = response.aiMessage().text();
        if (text == null || text.isBlank()) return null;

        String json = extractJson(text);
        try {
            Object parsed = objectMapper.readValue(json, Object.class);
            if (parsed instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) map;
                return typed;
            }
            log.info("Agrajag id='{}' LLM output not a JSON object: {}",
                    process.getId(), text.length() > 200 ? text.substring(0, 200) + "…" : text);
            return null;
        } catch (RuntimeException e) {
            log.info("Agrajag id='{}' LLM output not parseable JSON: {}",
                    process.getId(), e.getMessage());
            return null;
        }
    }

    private void applyDiagnosis(
            ThinkProcessDocument process,
            ToolHealthScope scope,
            String scopeId,
            String toolName,
            Map<String, Object> output) {
        ToolHealthClassification cls = parseClassification(output.get("classification"));
        String note = stringOrNull(output.get("humanNote"));
        Instant eta = parseInstantOrNull(output.get("expectedRecoveryAt"));
        String by = "agrajag-engine/" + process.getId();
        String tenantId = process.getTenantId();

        try {
            switch (cls) {
                case TECHNICALLY_BROKEN, USER_SPECIFIC_TECHNICAL ->
                    toolHealthService.markUnavailable(
                            tenantId, scope, scopeId, toolName, cls, eta, note, by);
                case INTERMITTENT ->
                    toolHealthService.markDegraded(
                            tenantId, scope, scopeId, toolName, cls, eta, note, by);
                case WORKING ->
                    toolHealthService.markAvailable(
                            tenantId, scope, scopeId, toolName, note, by);
                case USER_PERMISSION, USER_INPUT ->
                    // No health-doc status change — the upstream
                    // AgrajagChecker already set the right cooldown.
                    log.info("Agrajag id='{}' classified {} — no status change "
                                    + "(cooldown owned by checker)",
                            process.getId(), cls);
                case UNCLEAR -> {
                    Instant fallbackEta = eta == null
                            ? Instant.now().plus(FALLBACK_RECOVERY_HORIZON)
                            : eta;
                    String fallbackNote = note == null
                            ? "Agrajag could not classify confidently — flagged DEGRADED for retry"
                            : note;
                    toolHealthService.markDegraded(
                            tenantId, scope, scopeId, toolName, cls,
                            fallbackEta, fallbackNote, by);
                }
            }
        } catch (RuntimeException e) {
            log.warn("Agrajag id='{}' health-write failed: {}",
                    process.getId(), e.toString());
        }

        applyCooldownAdjustments(process, scope, scopeId, toolName, cls, output.get("cooldownAdjustments"));
    }

    @SuppressWarnings("unchecked")
    private void applyCooldownAdjustments(
            ThinkProcessDocument process,
            ToolHealthScope scope,
            String scopeId,
            String toolName,
            ToolHealthClassification cls,
            @Nullable Object raw) {
        if (!(raw instanceof List<?> list)) return;
        for (Object entry : list) {
            if (!(entry instanceof Map<?, ?> map)) continue;
            Map<String, Object> typed = (Map<String, Object>) map;
            String sig = stringOrNull(typed.get("errorSignature"));
            String user = stringOrNull(typed.get("userId"));
            Object durObj = typed.get("duration");
            if (sig == null || durObj == null) continue;
            try {
                Duration d = Duration.parse(durObj.toString());
                toolHealthService.setCooldown(
                        process.getTenantId(), scope, scopeId, toolName,
                        sig, user, cls, d,
                        "agrajag-cooldown/" + process.getId());
            } catch (RuntimeException e) {
                log.debug("Agrajag id='{}' skip cooldown adjustment {}: {}",
                        process.getId(), entry, e.toString());
            }
        }
    }

    private void fallbackDegraded(
            ThinkProcessDocument process,
            ToolHealthScope scope,
            String scopeId,
            String toolName,
            String note) {
        try {
            toolHealthService.markDegraded(
                    process.getTenantId(), scope, scopeId, toolName,
                    ToolHealthClassification.INTERMITTENT,
                    Instant.now().plus(FALLBACK_RECOVERY_HORIZON),
                    note,
                    "agrajag-engine/" + process.getId() + "/fallback");
        } catch (RuntimeException e) {
            log.warn("Agrajag fallback markDegraded failed id='{}': {}",
                    process.getId(), e.toString());
        }
    }

    // ────────────────── Helpers ──────────────────

    private static String renderUserPrompt(
            String toolName,
            ToolHealthScope scope,
            String scopeId,
            @Nullable String signature,
            @Nullable String originatingUserId,
            @Nullable String triggerNote,
            @Nullable ToolHealthDocument existing) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Tool error report\n\n");
        sb.append("- toolName: ").append(toolName).append('\n');
        sb.append("- scope: ").append(scope.name()).append('\n');
        if (!scopeId.isBlank()) sb.append("- scopeId: ").append(scopeId).append('\n');
        if (signature != null) sb.append("- errorSignature: ").append(signature).append('\n');
        if (originatingUserId != null && !originatingUserId.isBlank()) {
            sb.append("- originatingUserId: ").append(originatingUserId).append('\n');
        }
        if (triggerNote != null && !triggerNote.isBlank()) {
            sb.append("- note: ").append(triggerNote).append('\n');
        }
        sb.append('\n');

        if (existing == null) {
            sb.append("## Tool-health history\n\nNo prior entry for this tool in this scope.\n");
            return sb.toString();
        }

        sb.append("## Tool-health history\n\n");
        sb.append("Current status: ").append(existing.getStatus()).append('\n');
        if (existing.getSince() != null) {
            sb.append("Status since: ").append(existing.getSince()).append('\n');
        }
        if (existing.getLastClassification() != null) {
            sb.append("Last classification: ").append(existing.getLastClassification()).append('\n');
        }
        if (existing.getLastNote() != null && !existing.getLastNote().isBlank()) {
            sb.append("Last note: ").append(existing.getLastNote()).append('\n');
        }
        if (existing.getExpectedRecoveryAt() != null) {
            sb.append("Expected recovery: ").append(existing.getExpectedRecoveryAt()).append('\n');
        }

        List<ToolHealthHistoryEntry> history = existing.getHistory();
        if (history != null && !history.isEmpty()) {
            sb.append("\nRecent transitions (newest first, max ")
                    .append(HISTORY_CONTEXT_LIMIT).append("):\n");
            int n = Math.min(history.size(), HISTORY_CONTEXT_LIMIT);
            for (int i = 0; i < n; i++) {
                ToolHealthHistoryEntry h = history.get(i);
                sb.append("  - ").append(h.getAt())
                        .append(" status=").append(h.getStatus())
                        .append(" classification=").append(h.getClassification());
                if (h.getNote() != null && !h.getNote().isBlank()) {
                    sb.append(" note='").append(truncate(h.getNote(), 120)).append('\'');
                }
                sb.append('\n');
            }
        }
        List<ToolHealthCooldown> cd = existing.getCooldowns();
        if (cd != null && !cd.isEmpty()) {
            sb.append("\nActive cooldowns:\n");
            for (ToolHealthCooldown c : cd) {
                sb.append("  - signature='").append(c.getErrorSignature()).append('\'');
                if (c.getUserId() != null) sb.append(" user='").append(c.getUserId()).append('\'');
                sb.append(" nextSpawnAllowedAt=").append(c.getNextSpawnAllowedAt());
                sb.append(" hits=").append(c.getHits());
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    private static final Pattern JSON_BLOCK = Pattern.compile(
            "```(?:json)?\\s*(\\{.*?})\\s*```", Pattern.DOTALL);

    /** Strip optional markdown fences around the JSON object, if present. */
    static String extractJson(String text) {
        Matcher m = JSON_BLOCK.matcher(text);
        if (m.find()) return m.group(1);
        int first = text.indexOf('{');
        int last = text.lastIndexOf('}');
        if (first >= 0 && last > first) return text.substring(first, last + 1);
        return text.trim();
    }

    static ToolHealthClassification parseClassification(@Nullable Object raw) {
        if (raw == null) return ToolHealthClassification.UNCLEAR;
        try {
            return ToolHealthClassification.valueOf(
                    raw.toString().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return ToolHealthClassification.UNCLEAR;
        }
    }

    static @Nullable Instant parseInstantOrNull(@Nullable Object raw) {
        if (raw == null) return null;
        String s = raw.toString();
        if (s.isBlank() || "null".equalsIgnoreCase(s)) return null;
        try {
            return Instant.parse(s);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static @Nullable String stringOrNull(@Nullable Object v) {
        return v == null ? null : v.toString();
    }

    private static String nullToEmpty(@Nullable String s) {
        return s == null ? "" : s;
    }

    private static String truncate(String s, int max) {
        if (s.length() <= max) return s;
        return s.substring(0, max - 1) + "…";
    }

    /** For tests: deterministic apply with a pre-built output. */
    void applyForTest(
            ThinkProcessDocument process,
            ToolHealthScope scope,
            String scopeId,
            String toolName,
            Map<String, Object> output) {
        applyDiagnosis(process, scope, scopeId, toolName,
                new LinkedHashMap<>(output));
    }
}
