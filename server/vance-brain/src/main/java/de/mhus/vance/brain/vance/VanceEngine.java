package de.mhus.vance.brain.vance;

import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.api.thinkprocess.ProcessEventType;
import de.mhus.vance.api.thinkprocess.PromptMode;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.brain.arthur.ArthurEngine;
import de.mhus.vance.brain.thinkengine.EngineBundledConfig;
import de.mhus.vance.brain.thinkengine.ParentReport;
import de.mhus.vance.brain.thinkengine.SteerMessage;
import de.mhus.vance.brain.thinkengine.ThinkEngine;
import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
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
public class VanceEngine implements ThinkEngine {

    public static final String NAME = "vance";
    public static final String VERSION = "0.1.0";

    public static final String GREETING =
            "Hi, I'm Vance. What can I take off your plate?";

    /**
     * Phase-2 tool cut. The {@code organizational} category arrives in
     * phase 3; for now Vance has access only to recipe discovery and
     * docs — enough to compile and run, not enough to actually create
     * projects yet.
     */
    private static final Set<String> ALLOWED_TOOLS = Set.of(
            "whoami",
            "recipe_list",
            "recipe_describe",
            "docs_list",
            "docs_read");

    private static final String PROMPT_RESOURCE = "vance/vance-prompt.md";
    private static final String PROMPT_SMALL_RESOURCE = "vance/vance-prompt-small.md";

    private static final int DEFAULT_MAX_ITERATIONS = 4;
    private static final String DEFAULT_MODEL_ALIAS = "default:analyze";

    /** Loaded once on first {@link #bundledConfig()} call. */
    private volatile @org.jspecify.annotations.Nullable EngineBundledConfig cachedConfig;

    private final ArthurEngine arthur;
    private final ThinkProcessService thinkProcessService;

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
    public Optional<EngineBundledConfig> bundledConfig() {
        EngineBundledConfig cached = cachedConfig;
        if (cached == null) {
            cached = buildBundledConfig();
            cachedConfig = cached;
        }
        return Optional.of(cached);
    }

    private EngineBundledConfig buildBundledConfig() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("model", DEFAULT_MODEL_ALIAS);
        params.put("validation", true);
        params.put("maxIterations", DEFAULT_MAX_ITERATIONS);

        return new EngineBundledConfig(
                params,
                loadResource(PROMPT_RESOURCE),
                loadResource(PROMPT_SMALL_RESOURCE),
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
        ctx.chatMessageService().append(ChatMessageDocument.builder()
                .tenantId(process.getTenantId())
                .sessionId(process.getSessionId())
                .thinkProcessId(process.getId())
                .role(ChatRole.ASSISTANT)
                .content(GREETING)
                .build());
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.READY);
    }

    @Override
    public void resume(ThinkProcessDocument process, ThinkEngineContext ctx) {
        log.debug("Vance.resume id='{}'", process.getId());
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.READY);
    }

    @Override
    public void suspend(ThinkProcessDocument process, ThinkEngineContext ctx) {
        log.debug("Vance.suspend id='{}'", process.getId());
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.SUSPENDED);
    }

    @Override
    public void stop(ThinkProcessDocument process, ThinkEngineContext ctx) {
        log.info("Vance.stop id='{}'", process.getId());
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.STOPPED);
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
