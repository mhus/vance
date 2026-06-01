package de.mhus.vance.brain.zaphod;

import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.api.thinkprocess.CloseReason;
import de.mhus.vance.api.thinkprocess.ProcessEventType;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.api.zaphod.HeadStatus;
import de.mhus.vance.api.zaphod.ZaphodHead;
import de.mhus.vance.api.zaphod.ZaphodPattern;
import de.mhus.vance.api.zaphod.ZaphodState;
import de.mhus.vance.api.zaphod.ZaphodStatus;
import de.mhus.vance.brain.ai.AiChat;
import de.mhus.vance.brain.ai.AiChatConfig;
import de.mhus.vance.brain.ai.ChatBehaviorBuilder;
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

    private static final String SETTING_PROVIDER_API_KEY_FMT = "ai.provider.%s.apiKey";

    /** Engine-default synthesis system prompt — last-resort Java
     *  fallback. Primary source is the document cascade
     *  ({@code prompts/zaphod-synthesis.md}); recipes can override the
     *  cascade path via {@code promptDocument}. The user-message prefix
     *  remains a separate concern (recipe param {@code synthesisPrompt}).
     *
     *  <p>HARD OUTPUT CONTRACT: structured JSON object. The engine
     *  parses {@code synthesisMarkdown} and persists it under
     *  {@code _zaphod-drafts/<processId>/synthesis.md} — the LLM
     *  does NOT call any {@code doc_*} or {@code tool_*} pseudo-
     *  functions, those would be hallucinated and silently fail.
     *  Worker generates content, engine writes the file (see
     *  {@code instructions/general/engines.md} §"Tool usage"). */
    private static final String SYNTHESIS_SYSTEM_PROMPT =
            """
            Du bist der Synthesizer eines Zaphod-Konzils. Konsolidiere
            die Sichten der Berater zu einer einzigen Empfehlung.

            HARD OUTPUT CONTRACT:
            - Liefere GENAU ein JSON-Objekt, kein Markdown-Wrapper,
              kein Text davor oder danach.
            - KEINE Pseudo-Tool-Aufrufe wie `doc_create_kind(...)`
              oder `doc_write_text(...)`. Du hast KEINE Tools — die
              Engine persistiert das Dokument deterministisch aus
              `synthesisMarkdown`.

            Schema (alle Felder Pflicht):
                {
                  "title":             "<5-10 Wörter, deutsch, kein Punkt am Ende>",
                  "summary":           "<1-2 Sätze Kurzfassung — was der Rat empfiehlt>",
                  "synthesisMarkdown": "<vollständige Synthese als Markdown>"
                }

            Strukturiere `synthesisMarkdown` typischerweise so:
            1. Gemeinsamer Konsens — wo sind sich alle einig?
            2. Differenzen — wo widersprechen sich die Sichten,
               welche Argumente werden ins Feld geführt?
            3. Empfehlung — konkrete Schlussfolgerung mit Begründung.
            Zitiere konkrete Punkte aus den Köpfen (per Name),
            paraphrasiere nicht generisch.

            `summary` ist das, was der Anfragende im Chat zu sehen
            bekommt — also kurz, konkret, handlungsorientiert.
            `synthesisMarkdown` ist die ausführliche Form, die als
            Dokument abgelegt wird.

            Sprache: schreibe in der Sprache der ursprünglichen
            Frage. Bei deutscher Frage → deutsche Synthese.
            """;

    /** Engine-internal draft namespace — analog zu Vogon's
     *  {@code _vogon-drafts/}. The engine persists one
     *  {@code <head-name>.md} per council head right after the
     *  head's reply is captured, plus a {@code synthesis.md} after
     *  the synthesizer turn. Per-process subdirectory, overwritten
     *  on re-run of the same Zaphod process. The drafts are the
     *  audit / "let me re-read what X said" surface — the
     *  user-visible synthesis lives in the chat (ASSISTANT
     *  reply) and additionally in the synthesis.md draft. */
    public static final String DRAFTS_PREFIX = "_zaphod-drafts/";

    /** Max structured-output retries when the synthesizer LLM
     *  emits invalid JSON. Same budget Slart uses for FRAMING /
     *  PROPOSING re-prompts. */
    private static final int MAX_SYNTHESIS_CORRECTIONS = 2;

    /** Cascade path for the Zaphod synthesis prompt. Loaded via
     *  {@link de.mhus.vance.brain.thinkengine.EnginePromptResolver}. */
    private static final String SYNTHESIS_PROMPT_PATH = "_vance/prompts/zaphod-synthesis.md";

    /** Soft-cap on heads per council. More than this is almost
     *  certainly a config error; we warn + cut off. */
    /** Soft cap on the number of heads in a single council. Public
     *  so the Slartibartfast-side {@code ZaphodHeadsParser} can stay
     *  in sync with the engine's own start-up validation. */
    public static final int MAX_HEADS = 10;

    private final ThinkProcessService thinkProcessService;
    private final ChatMessageService chatMessageService;
    private final RecipeResolver recipeResolver;
    private final de.mhus.vance.brain.progress.LlmCallTracker llmCallTracker;
    private final de.mhus.vance.brain.thinkengine.EnginePromptResolver enginePromptResolver;
    private final de.mhus.vance.brain.prompt.PromptTemplateRenderer promptTemplateRenderer;
    private final de.mhus.vance.brain.ai.EngineChatFactory engineChatFactory;
    private final ProcessEventEmitter eventEmitter;
    private final LaneScheduler laneScheduler;
    private final ObjectMapper objectMapper;
    /** Writes head replies and the synthesizer's structured-output
     *  markdown body to project documents under
     *  {@code _zaphod-drafts/<processId>/}. The engine — not the
     *  LLM — performs the persistence so the artefacts are
     *  guaranteed to land. */
    private final de.mhus.vance.shared.document.DocumentService documentService;
    private final ObjectProvider<ThinkEngineService> thinkEngineServiceProvider;
    /** Resolves {@code chat.language} + {@code content.language} from
     *  the cascade and renders them as a "## Languages" block to
     *  append to the synthesizer's system prompt — Arthur/Eddie pick
     *  these up via MemoryContextLoader, but Zaphod's synthesizer
     *  runs inline and would otherwise miss them. */
    private final de.mhus.vance.brain.context.LanguageContextResolver languageContextResolver;

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
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.IDLE);
        eventEmitter.scheduleTurn(process.getId());
    }

    @Override
    public void resume(ThinkProcessDocument process, ThinkEngineContext ctx) {
        log.debug("Zaphod.resume id='{}'", process.getId());
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.IDLE);
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
        thinkProcessService.closeProcess(process.getId(), CloseReason.STOPPED);
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
            // Sync ThinkProcessStatus.CLOSED if not already there. The
            // listener filters CLOSED→CLOSED so this is silent on no-ops.
            thinkProcessService.closeProcess(process.getId(), CloseReason.DONE);
            return;
        }
        if (state.getStatus() == ZaphodStatus.FAILED) {
            thinkProcessService.closeProcess(process.getId(), CloseReason.STALE);
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
                thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.IDLE);
                return;
            }

            // 2. All heads processed — run synthesis.
            state.setStatus(ZaphodStatus.SYNTHESIZING);
            persistState(process, state);
            runSynthesis(process, ctx, state);
            persistState(process, state);
            if (state.getStatus() == ZaphodStatus.DONE) {
                thinkProcessService.closeProcess(process.getId(), CloseReason.DONE);
            } else {
                thinkProcessService.closeProcess(process.getId(), CloseReason.STALE);
            }
        } catch (RuntimeException e) {
            log.warn("Zaphod runTurn failed id='{}': {}",
                    process.getId(), e.toString(), e);
            thinkProcessService.closeProcess(process.getId(), CloseReason.STALE);
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
                    process.getTenantId(), ctx.projectId(), head.getRecipe(),
                    process.getConnectionProfile(), null);
            ThinkEngine targetEngine = thinkEngineServiceProvider.getObject()
                    .resolve(applied.engine())
                    .orElseThrow(() -> new IllegalStateException(
                            "Recipe '" + applied.name() + "' references unknown engine '"
                                    + applied.engine() + "'"));
            String childName = "zaphod-" + process.getId() + "-" + head.getName();
            child = thinkProcessService.create(
                    process.getTenantId(),
                    process.getProjectId(),
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
                    applied.promptOverrideAppend(),
                    applied.promptMode(),
                    applied.dataRelayCorrection(),
                    applied.effectiveAllowedTools(),
                    applied.connectionProfile(),
                    applied.defaultActiveSkills(),
                    applied.allowedSkills() == null
                            ? null : java.util.Set.copyOf(applied.allowedSkills()));
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
            // Persist the head reply as a draft document — same
            // pattern as Vogon's _vogon-drafts/. Lets Arthur (or the
            // user via the editor) re-read what each individual head
            // said when the synthesized chat answer isn't enough.
            // Overwrites on re-run of the same process.
            if (reply != null && !reply.isBlank()) {
                String draftPath = DRAFTS_PREFIX + process.getId()
                        + "/" + head.getName() + ".md";
                try {
                    writeDraftDocument(process, draftPath, reply,
                            "Council head '" + head.getName() + "' reply");
                } catch (RuntimeException e) {
                    log.warn("Zaphod id='{}' head '{}' draft persist failed: {}",
                            process.getId(), head.getName(), e.toString());
                }
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
            de.mhus.vance.brain.ai.EngineChatFactory.EngineChatBundle bundle =
                    engineChatFactory.forProcess(process, ctx, NAME);
            AiChat ai = bundle.chat();
            AiChatConfig config = bundle.primaryConfig();

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
            String basePath = paramString(process, "promptDocument", SYNTHESIS_PROMPT_PATH);
            String synthTpl = enginePromptResolver.resolve(
                    process, basePath, SYNTHESIS_SYSTEM_PROMPT);
            java.util.Map<String, Object> synthCtx = de.mhus.vance.brain.prompt.PromptContextBuilder
                    .forProcess(process, null)
                    .tier(de.mhus.vance.brain.ai.ModelSize.LARGE)
                    .engine(NAME)
                    .build();
            String renderedSystem = promptTemplateRenderer.render(synthTpl, synthCtx);
            String langBlock = languageContextResolver.formatBlock(process);
            if (!langBlock.isEmpty()) {
                renderedSystem = renderedSystem + "\n\n" + langBlock;
            }
            messages.add(SystemMessage.from(renderedSystem));
            messages.add(UserMessage.from(body.toString()));
            String modelAlias = config.provider() + ":" + config.modelName();

            // Structured-output loop: the synthesizer must emit a
            // JSON object with title/summary/synthesisMarkdown.
            // Up to MAX_SYNTHESIS_CORRECTIONS re-prompt attempts on
            // parse failure — same budget Slart uses for its
            // structured phases.
            SynthesisResult parsed = null;
            String validationError = null;
            for (int attempt = 0; attempt <= MAX_SYNTHESIS_CORRECTIONS; attempt++) {
                long startMs = System.currentTimeMillis();
                ChatRequest request = ChatRequest.builder().messages(messages).build();
                ChatResponse response = ai.chatModel().chat(request);
                llmCallTracker.record(
                        process, request, response,
                        System.currentTimeMillis() - startMs, modelAlias);
                String text = response.aiMessage() == null
                        ? null : response.aiMessage().text();
                if (text == null || text.isBlank()) {
                    validationError = "synthesizer returned empty reply";
                } else {
                    try {
                        parsed = parseSynthesisJson(text);
                        validationError = null;
                        break;
                    } catch (RuntimeException ve) {
                        validationError = ve.getMessage();
                        log.info("Zaphod id='{}' synthesis attempt {} parse failed: {}",
                                process.getId(), attempt, validationError);
                        if (attempt < MAX_SYNTHESIS_CORRECTIONS) {
                            messages.add(dev.langchain4j.data.message.AiMessage.from(text));
                            messages.add(UserMessage.from(
                                    "Dein letztes JSON war ungültig: "
                                            + validationError
                                            + "\n\nKorrigiere es und liefere "
                                            + "GENAU EIN JSON-Objekt nach dem Schema "
                                            + "oben — kein Markdown-Wrapper, KEINE "
                                            + "Pseudo-Tool-Aufrufe."));
                        }
                    }
                }
            }
            if (parsed == null) {
                state.setStatus(ZaphodStatus.FAILED);
                state.setFailureReason("Synthesizer failed after "
                        + MAX_SYNTHESIS_CORRECTIONS
                        + " corrections — last error: " + validationError);
                log.warn("Zaphod id='{}' synthesizer budget exhausted: {}",
                        process.getId(), validationError);
                return;
            }

            // Persist the synthesis markdown as a draft document
            // under the per-process drafts namespace. Same pattern
            // as the head replies above — overwrite-on-rerun,
            // engine-deterministic (worker generates content,
            // engine writes the file). The chat ASSISTANT-reply
            // (below) is the primary delivery; this draft is the
            // audit / re-read surface.
            String outputPath = DRAFTS_PREFIX + process.getId() + "/synthesis.md";
            try {
                writeDraftDocument(process, outputPath,
                        parsed.synthesisMarkdown(), parsed.title());
            } catch (RuntimeException e) {
                // Persist failure — keep the synthesis in-state so
                // the user can still see it via the parent-summary,
                // but mark the run failed because the document
                // contract is broken.
                state.setSynthesis(parsed.synthesisMarkdown());
                state.setSynthesisTitle(parsed.title());
                state.setSynthesisSummary(parsed.summary());
                state.setStatus(ZaphodStatus.FAILED);
                state.setFailureReason("Synthesizer produced output but "
                        + "document write to '" + outputPath + "' failed: "
                        + e.getMessage());
                log.warn("Zaphod id='{}' synthesis-doc write failed at '{}': {}",
                        process.getId(), outputPath, e.toString());
                return;
            }

            state.setSynthesis(parsed.synthesisMarkdown());
            state.setSynthesisTitle(parsed.title());
            state.setSynthesisSummary(parsed.summary());
            state.setSynthesisDocumentPath(outputPath);
            state.setStatus(ZaphodStatus.DONE);

            // Persist the FULL synthesis as the ASSISTANT chat message
            // so Arthur's RELAY hands the user the complete consolidated
            // answer in one go — not a summary with a path the user has
            // to open. The document copy (above) stays as an audit
            // asset; the chat is the primary output channel.
            StringBuilder reply = new StringBuilder();
            reply.append("**").append(parsed.title()).append("**\n\n")
                    .append(parsed.synthesisMarkdown())
                    .append("\n\n---\n_Synthese gespeichert unter `")
                    .append(outputPath).append("`._");
            ChatMessageDocument assistantReply = ChatMessageDocument.builder()
                    .tenantId(process.getTenantId())
                    .sessionId(process.getSessionId())
                    .thinkProcessId(process.getId())
                    .role(ChatRole.ASSISTANT)
                    .content(reply.toString())
                    .createdAt(java.time.Instant.now())
                    .build();
            chatMessageService.append(assistantReply);

            log.info("Zaphod id='{}' synthesis done — {} chars markdown, "
                            + "persisted at '{}'",
                    process.getId(), parsed.synthesisMarkdown().length(), outputPath);
        } catch (RuntimeException e) {
            state.setStatus(ZaphodStatus.FAILED);
            state.setFailureReason("Synthesizer failed: " + e.getMessage());
            log.warn("Zaphod id='{}' synthesis failed: {}",
                    process.getId(), e.toString());
        }
    }

    /**
     * Strict JSON-object parse for the synthesizer reply. Required
     * fields: title (non-blank string), summary (non-blank), and
     * synthesisMarkdown (non-blank). Tolerates incidental prose
     * outside the braces by extracting the first balanced JSON
     * object — same shape Slart's FRAMING/PROPOSING parsers use.
     */
    private SynthesisResult parseSynthesisJson(String raw) {
        String jsonOnly = extractFirstJsonObject(raw);
        if (jsonOnly == null) {
            throw new IllegalStateException(
                    "no JSON object found in synthesizer reply");
        }
        Map<String, Object> root;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed =
                    objectMapper.readValue(jsonOnly, Map.class);
            root = parsed;
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "JSON parse error: " + e.getMessage());
        }
        String title = requireSynthesisString(root, "title");
        String summary = requireSynthesisString(root, "summary");
        String markdown = requireSynthesisString(root, "synthesisMarkdown");
        // Defensive: refuse pseudo-tool-call bodies that some LLMs
        // produce despite the explicit "no doc_*" instruction.
        if (markdown.startsWith("doc_create_kind(")
                || markdown.startsWith("doc_write_text(")
                || markdown.startsWith("doc_create_text(")) {
            throw new IllegalStateException(
                    "synthesisMarkdown begins with a pseudo-tool-call — "
                            + "emit pure markdown text, no `doc_*(...)` syntax");
        }
        return new SynthesisResult(title, summary, markdown);
    }

    private static String requireSynthesisString(Map<String, Object> root, String key) {
        Object v = root.get(key);
        if (!(v instanceof String s) || s.isBlank()) {
            throw new IllegalStateException(
                    "required field '" + key + "' missing or blank");
        }
        return s.trim();
    }

    private static @org.jspecify.annotations.Nullable String extractFirstJsonObject(String raw) {
        if (raw == null) return null;
        int start = raw.indexOf('{');
        if (start < 0) return null;
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = start; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (escape) { escape = false; continue; }
            if (inString) {
                if (c == '\\') escape = true;
                else if (c == '"') inString = false;
                continue;
            }
            if (c == '"') { inString = true; continue; }
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return raw.substring(start, i + 1);
            }
        }
        return null;
    }

    private record SynthesisResult(
            String title, String summary, String synthesisMarkdown) {}

    /**
     * Persists one draft document under
     * {@code _zaphod-drafts/<processId>/} via the
     * {@link de.mhus.vance.shared.document.DocumentService}.
     * Upserts — if the same process re-runs (recovery, restart),
     * the draft is overwritten in place. Same find-or-update
     * pattern Vogon uses for its phase drafts.
     */
    private void writeDraftDocument(
            ThinkProcessDocument process, String path,
            String content, String title) {
        String tenantId = process.getTenantId();
        String projectId = process.getProjectId();
        java.util.Optional<de.mhus.vance.shared.document.DocumentDocument> existing =
                documentService.findByPath(tenantId, projectId, path);
        if (existing.isPresent()) {
            documentService.update(
                    existing.get().getId(),
                    title, /*tags*/ null, content, /*newPath*/ null);
        } else {
            documentService.createText(
                    tenantId, projectId, path, title,
                    java.util.List.of("council", "draft"),
                    content, "zaphod:" + process.getId());
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
        payload.put("synthesisTitle", state.getSynthesisTitle());
        payload.put("synthesisSummary", state.getSynthesisSummary());
        payload.put("synthesisDocumentPath", state.getSynthesisDocumentPath());

        if (state.getStatus() == ZaphodStatus.DONE && state.getSynthesis() != null) {
            // Parent-facing chat reply: the FULL consolidated synthesis
            // so Arthur's RELAY hands it back to the user directly. The
            // document persistence (above) is an audit asset, not the
            // primary delivery channel. The user asked a question; the
            // user gets the answer inline.
            StringBuilder reply = new StringBuilder();
            if (state.getSynthesisTitle() != null) {
                reply.append("**").append(state.getSynthesisTitle()).append("**\n\n");
            }
            reply.append(state.getSynthesis());
            if (state.getSynthesisDocumentPath() != null) {
                reply.append("\n\n---\n_Synthese gespeichert unter `")
                        .append(state.getSynthesisDocumentPath())
                        .append("`._");
            }
            return new ParentReport(reply.toString().trim(), payload);
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
        return ChatBehaviorBuilder.resolveForProcess(process, settings, modelResolver);
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
