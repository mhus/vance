package de.mhus.vance.brain.zaphod;

import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.api.thinkprocess.ProcessEventType;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.api.zaphod.HeadStatus;
import de.mhus.vance.api.zaphod.ZaphodHead;
import de.mhus.vance.api.zaphod.ZaphodPattern;
import de.mhus.vance.api.zaphod.ZaphodState;
import de.mhus.vance.api.zaphod.ZaphodStatus;
import de.mhus.vance.brain.ai.AiChat;
import de.mhus.vance.brain.ai.AiChatConfig;
import de.mhus.vance.brain.ai.AiChatOptions;
import de.mhus.vance.brain.ai.AiModelResolver;
import de.mhus.vance.brain.recipe.AppliedRecipe;
import de.mhus.vance.brain.recipe.RecipeResolver;
import de.mhus.vance.brain.scheduling.LaneScheduler;
import de.mhus.vance.brain.thinkengine.ParentReport;
import de.mhus.vance.brain.thinkengine.ProcessEventEmitter;
import de.mhus.vance.brain.thinkengine.SteerMessage;
import de.mhus.vance.brain.thinkengine.ThinkEngine;
import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.brain.thinkengine.ThinkEngineService;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Zaphod — the multi-head engine. Spawns N heads (one Ford-style
 * sub-process each), drives them sequentially against the same
 * goal, then runs a direct synthesizer LLM-call to produce one
 * combined answer.
 *
 * <p>State persists on
 * {@code ThinkProcessDocument.engineParams.zaphodState}, brain-restart
 * resumes pick up the next pending head without losing already
 * captured replies. Each {@code runTurn} performs <em>one</em>
 * action (drive next head, or run synthesis), then yields and
 * schedules the next turn — same lane-discipline as Vogon and Marvin.
 *
 * <p>See {@code specification/zaphod-engine.md} for the full design.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ZaphodEngine implements ThinkEngine {

    public static final String NAME = "zaphod";
    public static final String VERSION = "0.1.0";

    /** Set on {@code engineParams[STATE_KEY]} as the persisted
     *  {@link ZaphodState} for this process. */
    public static final String STATE_KEY = "zaphodState";

    /** {@code engineParams[PATTERN_KEY]} — pattern name string. */
    public static final String PATTERN_KEY = "pattern";

    /** {@code engineParams[HEADS_KEY]} — heads spec list. */
    public static final String HEADS_KEY = "heads";

    /** {@code engineParams[SYNTHESIS_PROMPT_KEY]} — optional. */
    public static final String SYNTHESIS_PROMPT_KEY = "synthesisPrompt";

    private static final String SETTINGS_REF_TYPE = "tenant";
    private static final String SETTING_PROVIDER_API_KEY_FMT = "ai.provider.%s.apiKey";

    /** Engine-default synthesis system prompt. Recipes override
     *  the *user-message* prefix via {@code synthesisPrompt}; the
     *  system prompt itself is engine-stable. */
    private static final String SYNTHESIS_SYSTEM_PROMPT =
            """
            Du synthetisierst die Sichten mehrerer Berater zu einer einzigen
            Antwort. Strukturiere typisch:
            1. Gemeinsamer Konsens — wo sind sich alle einig?
            2. Differenzen — wo widersprechen sich die Sichten, und welche
               Argumente werden ins Feld geführt?
            3. Empfehlung — eine konkrete Schlussfolgerung mit Begründung.
            Zitiere konkrete Punkte aus den Köpfen, paraphrasiere nicht generisch.
            """;

    /** Soft-cap on heads per council. More than this is almost
     *  certainly a config error; we warn + cut off. */
    private static final int MAX_HEADS = 10;

    private final ThinkProcessService thinkProcessService;
    private final ChatMessageService chatMessageService;
    private final RecipeResolver recipeResolver;
    private final AiModelResolver aiModelResolver;
    private final ProcessEventEmitter eventEmitter;
    private final LaneScheduler laneScheduler;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<ThinkEngineService> thinkEngineServiceProvider;

    // ──────────────────── Metadata ────────────────────

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String title() {
        return "Zaphod (Multi-Head Council)";
    }

    @Override
    public String description() {
        return "Multi-head engine. Spawns N heads (each its own Ford sub-process "
                + "with a distinct persona), drives them against the same goal, "
                + "then synthesizes one combined answer.";
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public Set<String> allowedTools() {
        // Zaphod itself uses no tools — heads have their own pools
        // via their recipes.
        return Set.of();
    }

    @Override
    public boolean asyncSteer() {
        return true;
    }

    // ──────────────────── Lifecycle ────────────────────

    @Override
    public void start(ThinkProcessDocument process, ThinkEngineContext ctx) {
        ZaphodState state = buildInitialState(process);
        persistState(process, state);
        log.info("Zaphod.start tenant='{}' session='{}' id='{}' pattern={} heads={}",
                process.getTenantId(), process.getSessionId(), process.getId(),
                state.getPattern(), state.getHeads().size());
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.READY);
        eventEmitter.scheduleTurn(process.getId());
    }

    @Override
    public void resume(ThinkProcessDocument process, ThinkEngineContext ctx) {
        log.debug("Zaphod.resume id='{}'", process.getId());
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.READY);
        eventEmitter.scheduleTurn(process.getId());
    }

    @Override
    public void suspend(ThinkProcessDocument process, ThinkEngineContext ctx) {
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.SUSPENDED);
    }

    @Override
    public void steer(ThinkProcessDocument process, ThinkEngineContext ctx, SteerMessage message) {
        // Async — orchestrators just queue and we wake on the next
        // scheduled turn. Steering content is informational; v1 has
        // no live-edit path.
        eventEmitter.scheduleTurn(process.getId());
    }

    @Override
    public void stop(ThinkProcessDocument process, ThinkEngineContext ctx) {
        log.info("Zaphod.stop id='{}'", process.getId());
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.STOPPED);
    }

    // ──────────────────── runTurn ────────────────────

    @Override
    public void runTurn(ThinkProcessDocument process, ThinkEngineContext ctx) {
        ZaphodState state = loadState(process);

        // Terminal check FIRST — avoid spurious RUNNING→DONE flickers.
        // Each runHead iteration calls scheduleTurn(self), so by the
        // time synthesis finishes there are typically several queued
        // runTurn tasks still pending on the lane. They must NOT pass
        // through RUNNING again, or each one re-fires a DONE-transition
        // and the parent (Arthur) gets duplicate notifications.
        if (state.getStatus() == ZaphodStatus.DONE) {
            // Sync ThinkProcessStatus.DONE if not already there. The
            // listener filters DONE→DONE so this is silent on no-ops.
            thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.DONE);
            return;
        }
        if (state.getStatus() == ZaphodStatus.FAILED) {
            thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.STALE);
            return;
        }

        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.RUNNING);
        try {
            // Drain any incoming messages — defensively, V1 doesn't
            // expect inbox-answers / process-events on Zaphod itself.
            for (SteerMessage ignored : ctx.drainPending()) {
                // discard
            }

            // 1. Drive the next head if any are pending.
            if (state.getCurrentHeadIndex() < state.getHeads().size()) {
                ZaphodHead head = state.getHeads().get(state.getCurrentHeadIndex());
                runHeadSync(process, ctx, state, head);
                state.setCurrentHeadIndex(state.getCurrentHeadIndex() + 1);
                state.setStatus(ZaphodStatus.RUNNING);
                persistState(process, state);
                eventEmitter.scheduleTurn(process.getId());
                thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.READY);
                return;
            }

            // 2. All heads processed — run synthesis.
            state.setStatus(ZaphodStatus.SYNTHESIZING);
            persistState(process, state);
            runSynthesis(process, ctx, state);
            persistState(process, state);
            if (state.getStatus() == ZaphodStatus.DONE) {
                thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.DONE);
            } else {
                thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.STALE);
            }
        } catch (RuntimeException e) {
            log.warn("Zaphod runTurn failed id='{}': {}",
                    process.getId(), e.toString(), e);
            thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.STALE);
            throw e;
        }
    }

    // ──────────────────── Head spawn + sync drive ────────────────────

    private void runHeadSync(
            ThinkProcessDocument process,
            ThinkEngineContext ctx,
            ZaphodState state,
            ZaphodHead head) {
        head.setStatus(HeadStatus.RUNNING);
        ThinkProcessDocument child;
        try {
            AppliedRecipe applied = recipeResolver.apply(
                    process.getTenantId(), ctx.projectId(), head.getRecipe(), null);
            ThinkEngine targetEngine = thinkEngineServiceProvider.getObject()
                    .resolve(applied.engine())
                    .orElseThrow(() -> new IllegalStateException(
                            "Recipe '" + applied.name() + "' references unknown engine '"
                                    + applied.engine() + "'"));
            String childName = "zaphod-" + process.getId() + "-" + head.getName();
            child = thinkProcessService.create(
                    process.getTenantId(),
                    process.getSessionId(),
                    childName,
                    targetEngine.name(),
                    targetEngine.version(),
                    "Zaphod head: " + head.getName(),
                    process.getGoal(),
                    process.getId(),
                    applied.params(),
                    applied.name(),
                    applied.promptOverride(),
                    applied.promptOverrideSmall(),
                    applied.promptMode(),
                    applied.intentCorrection(),
                    applied.dataRelayCorrection(),
                    applied.effectiveAllowedTools());
            head.setSpawnedProcessId(child.getId());
            thinkEngineServiceProvider.getObject().start(child);
            log.info("Zaphod id='{}' head '{}' spawned child='{}' recipe='{}'",
                    process.getId(), head.getName(), child.getId(), applied.name());
        } catch (RecipeResolver.UnknownRecipeException ure) {
            head.setStatus(HeadStatus.FAILED);
            head.setFailureReason("Unknown recipe: " + head.getRecipe());
            log.warn("Zaphod id='{}' head '{}' unknown recipe '{}'",
                    process.getId(), head.getName(), head.getRecipe());
            return;
        } catch (RuntimeException e) {
            head.setStatus(HeadStatus.FAILED);
            head.setFailureReason("Spawn failed: " + e.getMessage());
            log.warn("Zaphod id='{}' head '{}' spawn failed: {}",
                    process.getId(), head.getName(), e.toString());
            return;
        }
        try {
            String steerContent = process.getGoal() == null ? "" : process.getGoal();
            if (head.getPersona() != null && !head.getPersona().isBlank()) {
                steerContent = steerContent
                        + "\n\n[Deine Rolle / Persona]\n"
                        + head.getPersona();
            }
            driveHeadTurn(child, process.getId(), steerContent);
            String reply = readLastAssistantText(
                    process.getTenantId(), process.getSessionId(), child.getId());
            head.setReply(reply);
            head.setStatus(reply != null && !reply.isBlank()
                    ? HeadStatus.DONE : HeadStatus.FAILED);
            if (head.getStatus() == HeadStatus.FAILED) {
                head.setFailureReason("worker produced no assistant reply");
            }
            log.info("Zaphod id='{}' head '{}' {} — reply chars={}",
                    process.getId(), head.getName(), head.getStatus(),
                    reply == null ? 0 : reply.length());
        } catch (RuntimeException e) {
            head.setStatus(HeadStatus.FAILED);
            head.setFailureReason("Drive failed: " + e.getMessage());
            log.warn("Zaphod id='{}' head '{}' drive failed: {}",
                    process.getId(), head.getName(), e.toString());
        } finally {
            try {
                thinkEngineServiceProvider.getObject().stop(child);
            } catch (RuntimeException e) {
                log.warn("Zaphod id='{}' head '{}' stop failed: {}",
                        process.getId(), head.getName(), e.toString());
            }
        }
    }

    private void driveHeadTurn(
            ThinkProcessDocument child, String zaphodProcessId, String content) {
        SteerMessage.UserChatInput message = new SteerMessage.UserChatInput(
                java.time.Instant.now(),
                /*idempotencyKey*/ null,
                "zaphod:" + zaphodProcessId,
                content);
        try {
            laneScheduler.submit(child.getId(),
                    () -> thinkEngineServiceProvider.getObject().steer(child, message)).get();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(
                    "Zaphod head interrupted child='" + child.getId() + "'", ie);
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause() == null ? ee : ee.getCause();
            throw new RuntimeException(
                    "Zaphod head turn failed child='" + child.getId()
                            + "': " + cause.getMessage(), cause);
        }
    }

    private @Nullable String readLastAssistantText(
            String tenantId, String sessionId, String workerProcessId) {
        List<ChatMessageDocument> history = chatMessageService.history(
                tenantId, sessionId, workerProcessId);
        for (int i = history.size() - 1; i >= 0; i--) {
            ChatMessageDocument m = history.get(i);
            if (m.getRole() == ChatRole.ASSISTANT && m.getContent() != null
                    && !m.getContent().isBlank()) {
                return m.getContent();
            }
        }
        return null;
    }

    // ──────────────────── Synthesis ────────────────────

    private void runSynthesis(
            ThinkProcessDocument process,
            ThinkEngineContext ctx,
            ZaphodState state) {
        // Bail if nobody produced a reply — there's nothing to
        // synthesize.
        boolean anyDone = false;
        for (ZaphodHead h : state.getHeads()) {
            if (h.getStatus() == HeadStatus.DONE && h.getReply() != null) {
                anyDone = true;
                break;
            }
        }
        if (!anyDone) {
            state.setStatus(ZaphodStatus.FAILED);
            state.setFailureReason("All heads failed — nothing to synthesize.");
            log.warn("Zaphod id='{}' synthesis aborted — all {} heads failed",
                    process.getId(), state.getHeads().size());
            return;
        }
        try {
            AiChatConfig config = resolveAiConfig(process, ctx.settingService(), aiModelResolver);
            AiChat ai = ctx.aiModelService().createChat(config, AiChatOptions.builder().build());

            StringBuilder body = new StringBuilder();
            if (state.getSynthesizerPrompt() != null && !state.getSynthesizerPrompt().isBlank()) {
                body.append(state.getSynthesizerPrompt()).append("\n\n");
            }
            body.append("Frage: ").append(process.getGoal() == null ? "" : process.getGoal())
                    .append("\n\nKopf-Antworten:\n");
            for (ZaphodHead h : state.getHeads()) {
                body.append("\n--- ").append(h.getName()).append(" ---\n");
                if (h.getStatus() == HeadStatus.DONE && h.getReply() != null) {
                    body.append(h.getReply());
                } else {
                    body.append("[head failed: ")
                            .append(h.getFailureReason() == null ? "?" : h.getFailureReason())
                            .append("]");
                }
                body.append('\n');
            }
            List<ChatMessage> messages = new ArrayList<>();
            messages.add(SystemMessage.from(SYNTHESIS_SYSTEM_PROMPT));
            messages.add(UserMessage.from(body.toString()));
            ChatResponse response = ai.chatModel().chat(
                    ChatRequest.builder().messages(messages).build());
            String text = response.aiMessage() == null
                    ? null : response.aiMessage().text();
            if (text == null || text.isBlank()) {
                state.setStatus(ZaphodStatus.FAILED);
                state.setFailureReason("Synthesizer returned empty reply.");
                log.warn("Zaphod id='{}' synthesizer returned empty reply",
                        process.getId());
                return;
            }
            state.setSynthesis(text.trim());
            state.setStatus(ZaphodStatus.DONE);
            log.info("Zaphod id='{}' synthesis done — {} chars",
                    process.getId(), text.length());
        } catch (RuntimeException e) {
            state.setStatus(ZaphodStatus.FAILED);
            state.setFailureReason("Synthesizer failed: " + e.getMessage());
            log.warn("Zaphod id='{}' synthesis failed: {}",
                    process.getId(), e.toString());
        }
    }

    // ──────────────────── summarizeForParent ────────────────────

    @Override
    public ParentReport summarizeForParent(
            ThinkProcessDocument process, ProcessEventType eventType) {
        ZaphodState state;
        try {
            state = loadState(process);
        } catch (RuntimeException e) {
            return ParentReport.of("Zaphod process " + process.getId()
                    + " status=" + eventType.name().toLowerCase());
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventType", eventType.name());
        payload.put("pattern", state.getPattern() == null
                ? null : state.getPattern().name());
        List<Map<String, Object>> headEntries = new ArrayList<>();
        int doneCount = 0;
        for (ZaphodHead h : state.getHeads()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", h.getName());
            entry.put("status", h.getStatus() == null ? null : h.getStatus().name());
            headEntries.add(entry);
            if (h.getStatus() == HeadStatus.DONE) doneCount++;
        }
        payload.put("heads", headEntries);
        payload.put("synthesisChars", state.getSynthesis() == null
                ? 0 : state.getSynthesis().length());

        if (state.getStatus() == ZaphodStatus.DONE && state.getSynthesis() != null) {
            return new ParentReport(state.getSynthesis(), payload);
        }
        if (state.getStatus() == ZaphodStatus.FAILED) {
            return new ParentReport(
                    "Zaphod council failed: "
                            + (state.getFailureReason() == null
                                    ? "unknown reason" : state.getFailureReason()),
                    payload);
        }
        return new ParentReport(
                "Zaphod council in progress (" + doneCount + "/"
                        + state.getHeads().size() + " heads done)",
                payload);
    }

    // ──────────────────── State construction ────────────────────

    @SuppressWarnings("unchecked")
    private ZaphodState buildInitialState(ThinkProcessDocument process) {
        Map<String, Object> p = process.getEngineParams() == null
                ? new LinkedHashMap<>() : process.getEngineParams();
        // Pattern.
        Object patternRaw = p.get(PATTERN_KEY);
        ZaphodPattern pattern;
        if (patternRaw == null) {
            throw new IllegalStateException(
                    "Zaphod.start requires engineParams.pattern — id='"
                            + process.getId() + "'");
        }
        try {
            pattern = ZaphodPattern.valueOf(
                    String.valueOf(patternRaw).trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "Zaphod: unknown pattern '" + patternRaw
                            + "' (V1 supports COUNCIL only)");
        }
        if (pattern != ZaphodPattern.COUNCIL) {
            throw new IllegalStateException(
                    "Zaphod V1 supports only COUNCIL pattern; got " + pattern);
        }
        // Heads.
        Object headsRaw = p.get(HEADS_KEY);
        if (!(headsRaw instanceof List<?> headList) || headList.isEmpty()) {
            throw new IllegalStateException(
                    "Zaphod.start requires engineParams.heads (non-empty list)");
        }
        if (headList.size() > MAX_HEADS) {
            log.warn("Zaphod id='{}' heads={} exceeds soft-cap {} — truncating",
                    process.getId(), headList.size(), MAX_HEADS);
        }
        List<ZaphodHead> heads = new ArrayList<>();
        Set<String> seenNames = new LinkedHashSet<>();
        int limit = Math.min(headList.size(), MAX_HEADS);
        for (int i = 0; i < limit; i++) {
            Object entry = headList.get(i);
            if (!(entry instanceof Map<?, ?> m)) {
                throw new IllegalStateException(
                        "Zaphod heads[" + i + "] is not a map");
            }
            Map<String, Object> spec = stringMap((Map<String, Object>) m);
            String name = stringOrThrow(spec, "name", "heads[" + i + "].name");
            String recipe = stringOrThrow(spec, "recipe", "heads[" + i + "].recipe");
            if (!seenNames.add(name)) {
                throw new IllegalStateException(
                        "Zaphod heads must have unique names — duplicate: '"
                                + name + "'");
            }
            String persona = optString(spec.get("persona"));
            heads.add(ZaphodHead.builder()
                    .name(name)
                    .recipe(recipe)
                    .persona(persona)
                    .status(HeadStatus.PENDING)
                    .build());
        }
        // Synthesizer prompt (optional).
        String synthesizerPrompt = optString(p.get(SYNTHESIS_PROMPT_KEY));

        return ZaphodState.builder()
                .pattern(pattern)
                .heads(heads)
                .currentHeadIndex(0)
                .synthesizerPrompt(synthesizerPrompt)
                .status(ZaphodStatus.SPAWNING)
                .build();
    }

    // ──────────────────── State persistence ────────────────────

    @SuppressWarnings("unchecked")
    private ZaphodState loadState(ThinkProcessDocument process) {
        Map<String, Object> p = process.getEngineParams();
        if (p == null) return ZaphodState.builder().build();
        Object raw = p.get(STATE_KEY);
        if (raw == null) return ZaphodState.builder().build();
        return objectMapper.convertValue(raw, ZaphodState.class);
    }

    @SuppressWarnings("unchecked")
    private void persistState(ThinkProcessDocument process, ZaphodState state) {
        Map<String, Object> p = process.getEngineParams() == null
                ? new LinkedHashMap<>() : process.getEngineParams();
        Map<String, Object> serialized = objectMapper.convertValue(state, Map.class);
        p.put(STATE_KEY, serialized);
        process.setEngineParams(p);
        thinkProcessService.replaceEngineParams(process.getId(), p);
    }

    // ──────────────────── AI-Config (mirrors Marvin / Vogon) ────────────────────

    private static AiChatConfig resolveAiConfig(
            ThinkProcessDocument process,
            SettingService settings,
            AiModelResolver modelResolver) {
        String tenantId = process.getTenantId();
        String paramModel = paramString(process, "model", null);
        String paramProvider = paramString(process, "provider", null);
        String spec;
        if (paramModel != null && paramModel.contains(":")) {
            spec = paramModel;
        } else if (paramModel != null && paramProvider != null) {
            spec = paramProvider + ":" + paramModel;
        } else if (paramModel != null) {
            spec = "default:" + paramModel;
        } else {
            spec = null;
        }
        AiModelResolver.Resolved resolved = modelResolver.resolveOrDefault(spec, tenantId);
        String apiKeySetting = String.format(SETTING_PROVIDER_API_KEY_FMT, resolved.provider());
        String apiKey = settings.getDecryptedPassword(
                tenantId, SETTINGS_REF_TYPE, tenantId, apiKeySetting);
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "No API key configured for provider '" + resolved.provider()
                            + "' (tenant='" + tenantId
                            + "', setting='" + apiKeySetting + "')");
        }
        return new AiChatConfig(resolved.provider(), resolved.modelName(), apiKey);
    }

    private static @Nullable String paramString(
            ThinkProcessDocument process, String key, @Nullable String fallback) {
        Map<String, Object> p = process.getEngineParams();
        Object v = p == null ? null : p.get(key);
        return v instanceof String s && !s.isBlank() ? s : fallback;
    }

    // ──────────────────── Map helpers ────────────────────

    private static String stringOrThrow(Map<String, Object> spec, String key, String trail) {
        Object raw = spec.get(key);
        if (!(raw instanceof String s) || s.isBlank()) {
            throw new IllegalStateException(trail + " is required and must be a non-empty string");
        }
        return s;
    }

    private static @Nullable String optString(@Nullable Object raw) {
        return raw instanceof String s && !s.isBlank() ? s : null;
    }

    private static Map<String, Object> stringMap(Map<String, Object> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            out.put(String.valueOf(e.getKey()), e.getValue());
        }
        return out;
    }
}
