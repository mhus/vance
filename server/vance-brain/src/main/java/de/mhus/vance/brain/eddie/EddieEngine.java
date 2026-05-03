package de.mhus.vance.brain.eddie;

import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.api.thinkprocess.CloseReason;
import de.mhus.vance.api.thinkprocess.ProcessEventType;
import de.mhus.vance.api.thinkprocess.PromptMode;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.brain.arthur.ArthurEngine;
import de.mhus.vance.brain.thinkengine.EngineBundledConfig;
import de.mhus.vance.brain.thinkengine.ParentReport;
import de.mhus.vance.brain.thinkengine.SteerMessage;
import de.mhus.vance.brain.thinkengine.ThinkEngine;
import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.brain.eddie.activity.EddieActivityEntry;
import de.mhus.vance.brain.eddie.activity.EddieActivityService;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Vance — the per-user personal hub engine.
 *
 * <p>Phase-2 skeleton: Vance reuses Arthur's conversational machinery
 * (drain-once-per-turn, streaming tool-loop, recipe-driven prompt) by
 * delegating {@link #runTurn} and {@link #steer}. Vance only differs
 * in identity, greeting, and the eventual tool cut. Hub-specific
 * mechanics — Activity-Log writes (§5.2), Peer-Notification handling
 * (§5.3), Bootstrap-Recap (§5.5) — are introduced in phase 4 by
 * adding hooks around the delegated calls.
 *
 * <p>See {@code specification/vance-engine.md}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EddieEngine implements ThinkEngine {

    public static final String NAME = "eddie";
    public static final String VERSION = "0.1.0";

    public static final String GREETING =
            "Hi, I'm Eddie. What can I take off your plate?";

    /**
     * Eddie's tool cut. Wider than originally specced — Eddie is
     * Jarvis-like: she can do small things herself (search the web,
     * compute, recall notes) and only delegates the bigger work to
     * worker projects. The tool set spans four groups:
     *
     * <ul>
     *   <li><b>Identity / discovery</b>: {@code whoami},
     *       {@code current_time}, {@code find_tools},
     *       {@code describe_tool}, {@code invoke_tool} —
     *       so Eddie can introspect what she has.</li>
     *   <li><b>Quick research / compute</b>: {@code web_search},
     *       {@code web_fetch}, {@code execute_javascript} —
     *       enough for a one-shot answer without spawning a worker.</li>
     *   <li><b>Personal memory</b>: {@code scratchpad_get/set/list/delete}
     *       — Eddie's own note-pad for things the user wants
     *       remembered between sessions.</li>
     *   <li><b>Organizational</b>: {@code project_list},
     *       {@code recipe_list}, {@code recipe_describe},
     *       {@code manual_list}, {@code manual_read} —
     *       inventory of the user's projects + worker recipes
     *       + the recipe-configured manuals. {@code project_create},
     *       {@code process_create}, {@code process_steer},
     *       {@code process_observe}, {@code peer_notify}
     *       arrive later in phase 3.</li>
     * </ul>
     *
     * <p>What's deliberately absent: {@code workspace_*} (file I/O is
     * worker concern), {@code exec_run} (shell commands —
     * worker concern), {@code rag_*} (worker memory, not hub
     * memory), {@code workspace_execute_javascript}
     * ({@code execute_javascript} is enough for hub-side compute).
     */
    private static final Set<String> ALLOWED_TOOLS = Set.of(
            // Identity / discovery
            "whoami",
            "respond",
            "current_time",
            "find_tools",
            "describe_tool",
            "invoke_tool",
            // Quick research / compute
            "web_search",
            "web_fetch",
            "execute_javascript",
            // Personal memory
            "scratchpad_get",
            "scratchpad_set",
            "scratchpad_list",
            "scratchpad_delete",
            // Project organisation
            "project_list",
            "project_switch",
            "project_current",
            "project_create",
            "project_chat_send",
            "recipe_list",
            "recipe_describe",
            // Documents (within active project)
            "doc_list",
            "doc_find",
            "doc_read",
            "doc_create_text",
            "doc_import_url",
            // Teams
            "team_list",
            "team_describe",
            // Inbox — for posting findings / outputs to the user
            "inbox_post",
            // Cross-hub sync — notify other Eddie sessions of the same user
            "peer_notify",
            // Manuals — recipe-configured folder list (eddie/manuals/ + manuals/)
            "manual_list",
            "manual_read");

    /**
     * Document-cascade paths for Eddie's main and small-model prompts.
     * Resolved through {@link de.mhus.vance.brain.thinkengine.EnginePromptResolver}
     * so a per-user override (in the {@code _user_<login>} project) or
     * tenant-wide override (in the {@code _vance} project) can replace
     * the bundled defaults shipped under {@code classpath:vance-defaults/prompts/}.
     */
    private static final String PROMPT_PATH = "prompts/eddie-prompt.md";
    private static final String PROMPT_SMALL_PATH = "prompts/eddie-prompt-small.md";

    /** Classpath path of the bundled fallback (kept loadable when no tenant context). */
    private static final String PROMPT_RESOURCE = "vance-defaults/prompts/eddie-prompt.md";
    private static final String PROMPT_SMALL_RESOURCE = "vance-defaults/prompts/eddie-prompt-small.md";

    private static final int DEFAULT_MAX_ITERATIONS = 4;
    private static final String DEFAULT_MODEL_ALIAS = "default:analyze";

    /** Loaded once on first {@link #bundledConfig()} call. */
    private volatile @org.jspecify.annotations.Nullable EngineBundledConfig cachedConfig;

    private final ArthurEngine arthur;
    private final ThinkProcessService thinkProcessService;
    private final EddieActivityService activityService;
    private final de.mhus.vance.shared.session.SessionService sessionService;
    private final de.mhus.vance.brain.thinkengine.EnginePromptResolver enginePromptResolver;

    // ──────────────────── Metadata ────────────────────

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String title() {
        return "Vance (Personal Hub)";
    }

    @Override
    public String description() {
        return "Personal hub agent. Lives in the per-user Home project; "
                + "creates, observes, and steers regular projects on the "
                + "user's behalf. Does not do content work itself.";
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public Set<String> allowedTools() {
        return ALLOWED_TOOLS;
    }

    @Override
    public boolean allowsCrossProjectSpawn() {
        // Vance's whole purpose: create / steer worker projects and
        // observe their Arthur chat-processes. See
        // specification/vance-engine.md §7.
        return true;
    }

    @Override
    public Optional<EngineBundledConfig> bundledConfig() {
        EngineBundledConfig cached = cachedConfig;
        if (cached == null) {
            cached = buildBundledConfig(loadResource(PROMPT_RESOURCE),
                    loadResource(PROMPT_SMALL_RESOURCE));
            cachedConfig = cached;
        }
        return Optional.of(cached);
    }

    /**
     * Tenant-aware variant: resolves both prompts via the document
     * cascade ({@code _user_<login>} → {@code _vance} →
     * {@code classpath:vance-defaults/prompts/eddie-prompt*.md}). The
     * classpath fallback is the same content that the cached default
     * loads from, so a missing override silently falls through to the
     * bundled prompt without an extra round-trip.
     */
    @Override
    public Optional<EngineBundledConfig> bundledConfig(
            String tenantId, @org.jspecify.annotations.Nullable String projectId) {
        String promptFallback = loadResource(PROMPT_RESOURCE);
        String promptSmallFallback = loadResource(PROMPT_SMALL_RESOURCE);
        String prompt = enginePromptResolver.resolveForTenant(
                tenantId, projectId, PROMPT_PATH, promptFallback);
        String promptSmall = enginePromptResolver.resolveForTenant(
                tenantId, projectId, PROMPT_SMALL_PATH, promptSmallFallback);
        return Optional.of(buildBundledConfig(prompt, promptSmall));
    }

    private EngineBundledConfig buildBundledConfig(String prompt, String promptSmall) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("model", DEFAULT_MODEL_ALIAS);
        params.put("validation", true);
        params.put("maxIterations", DEFAULT_MAX_ITERATIONS);
        // Eddie joins her hub-specific manuals with the general docs
        // — recipe order = precedence on duplicate filenames.
        params.put("manualPaths", java.util.List.of("eddie/manuals/", "manuals/"));

        return new EngineBundledConfig(
                params,
                prompt,
                promptSmall,
                PromptMode.OVERWRITE,
                /*intentCorrection*/ null,
                /*dataRelayCorrection*/ null,
                /*allowedTools*/ null);
    }

    private static String loadResource(String path) {
        ClassPathResource resource = new ClassPathResource(path);
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to load Vance prompt resource: " + path, e);
        }
    }

    // ──────────────────── Lifecycle ────────────────────

    @Override
    public void start(ThinkProcessDocument process, ThinkEngineContext ctx) {
        log.info("Vance.start tenant='{}' session='{}' id='{}'",
                process.getTenantId(), process.getSessionId(), process.getId());
        String greeting = composeGreetingWithRecap(process);
        ctx.chatMessageService().append(ChatMessageDocument.builder()
                .tenantId(process.getTenantId())
                .sessionId(process.getSessionId())
                .thinkProcessId(process.getId())
                .role(ChatRole.ASSISTANT)
                .content(greeting)
                .build());
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.IDLE);
    }

    @Override
    public void resume(ThinkProcessDocument process, ThinkEngineContext ctx) {
        log.debug("Eddie.resume id='{}'", process.getId());
        // Resume-Recap: was haben Peers oder ich selbst zuletzt getan?
        // Schreiben wir nur, wenn was Substantielles vorliegt — sonst
        // wirkt jedes Reconnect lärmig.
        String recap = buildPeerRecap(process);
        if (recap != null) {
            ctx.chatMessageService().append(ChatMessageDocument.builder()
                    .tenantId(process.getTenantId())
                    .sessionId(process.getSessionId())
                    .thinkProcessId(process.getId())
                    .role(ChatRole.ASSISTANT)
                    .content(recap)
                    .build());
        }
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.IDLE);
    }

    /**
     * Initial greeting plus, if there's recent peer activity, a
     * one-line recap. Keeps {@link #GREETING} stable for the
     * "nothing happened" case so cold starts feel snappy.
     */
    private String composeGreetingWithRecap(ThinkProcessDocument process) {
        String recap = buildPeerRecap(process);
        if (recap == null) return GREETING;
        return GREETING + " " + recap;
    }

    /**
     * Builds a voice-friendly recap line from the most recent peer
     * Activity-Log entries, or {@code null} if nothing is worth
     * mentioning.
     */
    private @org.jspecify.annotations.Nullable String buildPeerRecap(ThinkProcessDocument process) {
        // userId comes from the session — process doesn't carry it directly.
        var sessionOpt = sessionService.findBySessionId(process.getSessionId());
        if (sessionOpt.isEmpty()) return null;
        String userId = sessionOpt.get().getUserId();
        if (userId == null || userId.isBlank()) return null;

        java.util.List<EddieActivityEntry> peers = activityService.readPeerRecap(
                process.getTenantId(), userId, process.getId());
        if (peers.isEmpty()) return null;
        if (peers.size() == 1) {
            return "Kurzer Stand: " + peers.get(0).getSummary() + ".";
        }
        // 2..N entries — summarise top 3 inline, count the rest.
        int top = Math.min(3, peers.size());
        StringBuilder sb = new StringBuilder("Kurzer Stand: ");
        for (int i = 0; i < top; i++) {
            if (i > 0) sb.append(i == top - 1 ? " und " : ", ");
            sb.append(peers.get(i).getSummary());
        }
        if (peers.size() > top) {
            sb.append(" — plus ").append(peers.size() - top).append(" weitere");
        }
        sb.append(".");
        return sb.toString();
    }

    @Override
    public void suspend(ThinkProcessDocument process, ThinkEngineContext ctx) {
        log.debug("Vance.suspend id='{}'", process.getId());
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.SUSPENDED);
    }

    @Override
    public void stop(ThinkProcessDocument process, ThinkEngineContext ctx) {
        log.info("Eddie.stop id='{}'", process.getId());
        thinkProcessService.closeProcess(process.getId(), CloseReason.STOPPED);
    }

    @Override
    public void steer(ThinkProcessDocument process, ThinkEngineContext ctx, SteerMessage message) {
        arthur.steer(process, ctx, message);
    }

    @Override
    public void runTurn(ThinkProcessDocument process, ThinkEngineContext ctx) {
        arthur.runTurn(process, ctx);
    }

    @Override
    public ParentReport summarizeForParent(
            ThinkProcessDocument process, ProcessEventType eventType) {
        return arthur.summarizeForParent(process, eventType);
    }
}
