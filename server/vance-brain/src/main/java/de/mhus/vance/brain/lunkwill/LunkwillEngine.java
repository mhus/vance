package de.mhus.vance.brain.lunkwill;

import de.mhus.vance.api.thinkprocess.CloseReason;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.brain.thinkengine.SteerMessage;
import de.mhus.vance.brain.thinkengine.ThinkEngine;
import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Lunkwill — focused-worker engine. Pi-style loop: drain → LLM →
 * tools → repeat until natural stop, tool-terminate, external
 * interrupt, or safety net. No {@code maxIterations} cap — endless
 * by design.
 *
 * <p><b>This is the skeleton.</b> The actual LLM-driven loop lands
 * in a follow-up change; today's {@link #runTurn} only drains the
 * pending inbox and returns. Lets us register the engine, ship the
 * default recipe, and verify the spawn path before wiring up
 * {@code AiChat} + tool execution.
 *
 * <p>See {@code planning/lunkwill-engine.md} for the design and
 * {@code planning/coding-recipe.md} for the first validating recipe.
 */
@Component
@EnableConfigurationProperties(LunkwillProperties.class)
@RequiredArgsConstructor
@Slf4j
public class LunkwillEngine implements ThinkEngine {

    public static final String NAME = "lunkwill";
    public static final String VERSION = "0.1.0";

    private final ThinkProcessService thinkProcessService;
    private final LunkwillProperties properties;

    // ──────────────────── Metadata ────────────────────

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String title() {
        return "Lunkwill (Focused Worker)";
    }

    @Override
    public String description() {
        return "Pi-style focused worker — drain, LLM, tools, repeat until done. "
                + "First validating recipe: coding.";
    }

    @Override
    public String version() {
        return VERSION;
    }

    // ──────────────────── Lifecycle ────────────────────

    @Override
    public void start(ThinkProcessDocument process, ThinkEngineContext ctx) {
        log.info("Lunkwill.start tenant='{}' session='{}' id='{}'",
                process.getTenantId(), process.getSessionId(), process.getId());
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.IDLE);
    }

    @Override
    public void resume(ThinkProcessDocument process, ThinkEngineContext ctx) {
        log.debug("Lunkwill.resume id='{}'", process.getId());
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.IDLE);
    }

    @Override
    public void suspend(ThinkProcessDocument process, ThinkEngineContext ctx) {
        log.debug("Lunkwill.suspend id='{}'", process.getId());
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.SUSPENDED);
    }

    @Override
    public void steer(ThinkProcessDocument process, ThinkEngineContext ctx, SteerMessage message) {
        // Single-message entry — defer to runTurn which drains the rest of the inbox.
        runTurn(process, ctx);
    }

    /**
     * Lunkwill loop entry. <b>Skeleton:</b> drains the inbox and
     * returns. The full Pi-style loop (LLM call, tool execution,
     * stop-condition checks, safety nets) lands in the next change.
     */
    @Override
    public void runTurn(ThinkProcessDocument process, ThinkEngineContext ctx) {
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.RUNNING);
        try {
            List<SteerMessage> drained = ctx.drainPending();
            log.info("Lunkwill.runTurn id='{}' drained={} (skeleton — LLM loop not yet wired)",
                    process.getId(), drained.size());
        } finally {
            thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.IDLE);
        }
    }

    @Override
    public void stop(ThinkProcessDocument process, ThinkEngineContext ctx) {
        log.info("Lunkwill.stop id='{}'", process.getId());
        thinkProcessService.closeProcess(process.getId(), CloseReason.STOPPED);
    }
}
