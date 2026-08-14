package de.mhus.vance.brain.trillian;

import tools.jackson.databind.ObjectMapper;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.brain.ai.EngineChatFactory;
import de.mhus.vance.brain.progress.LlmCallTracker;
import de.mhus.vance.brain.ai.ModelCatalog;
import de.mhus.vance.brain.events.StreamingProperties;
import de.mhus.vance.brain.frankie.FrankieEngine;
import de.mhus.vance.brain.frankie.FrankieProperties;
import de.mhus.vance.brain.memory.MemoryCompactionService;
import de.mhus.vance.brain.memory.MemoryContextLoader;
import de.mhus.vance.brain.ai.attachment.AttachedUserMessageComposer;
import de.mhus.vance.brain.skill.SkillPromptComposer;
import de.mhus.vance.brain.skill.SkillResolver;
import de.mhus.vance.brain.thinkengine.EnginePromptResolver;
import de.mhus.vance.brain.thinkengine.SystemPromptComposer;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The per-task worker of a Trillian: Frankie's loop, with one thing
 * added — it may ask a question and stay alive.
 *
 * <p><b>Why an engine and not a prompt.</b> The first attempt told the
 * worker to ask by replying in plain text and calling nothing, because a
 * natural stop leaves the process IDLE while a terminating tool closes
 * it. The model asked its question through {@code trillian_done}
 * instead, and closed itself — the answer could then never reach it, and
 * everything it had worked out was gone. That was not disobedience: in a
 * tool-calling loop, "end the turn by calling nothing" is the hardest
 * instruction there is, because every other route is a tool. Arthur's
 * structured actions exist for exactly this reason — one discriminated
 * slot instead of text and tools competing.
 *
 * <p><b>What changes.</b> Nothing in Frankie's protocol. {@code
 * _terminate} still means "leave the loop"; only the consequence differs,
 * and that is the one seam Frankie exposes ({@code onWorkerTerminate}).
 * A worker of this engine that asked a question parks IDLE with its
 * context intact, and {@code process_steer} carries the answer straight
 * back into it. Everything else — the Pi-style loop, the tools, the
 * wallclock net, the guard — is Frankie's, unchanged.
 */
@Component
@Slf4j
public class TrillianWorkerEngine extends FrankieEngine {

    public static final String NAME = "trillian-worker";

    /**
     * engineParamOverrides marker set by {@code trillian_ask}: this exit
     * is a question, not a result.
     *
     * <p>Carried on the process rather than through the tool result,
     * because the result protocol is Frankie's and this must not touch
     * it. Cleared as it is read, so a later real termination closes
     * normally.
     */
    public static final String PARAM_ASK_PENDING = "trillianAskPending";

    private final ThinkProcessService processes;

    public TrillianWorkerEngine(
            ThinkProcessService thinkProcessService,
            FrankieProperties properties,
            EngineChatFactory engineChatFactory,
            LlmCallTracker llmCallTracker,
            StreamingProperties streamingProperties,
            ObjectMapper objectMapper,
            EnginePromptResolver enginePromptResolver,
            SystemPromptComposer systemPromptComposer,
            SkillResolver skillResolver,
            SkillPromptComposer skillPromptComposer,
            SessionService sessionService,
            de.mhus.vance.brain.context.PromptDateContextResolver promptDateContextResolver,
            de.mhus.vance.brain.prompt.ScratchpadPromptContributor scratchpadPromptContributor,
            MemoryContextLoader memoryContextLoader,
            ModelCatalog modelCatalog,
            MemoryCompactionService memoryCompactionService,
            de.mhus.vance.brain.thinkengine.TurnContextHandlerRegistry turnContextHandlers,
            de.mhus.vance.brain.guard.CompletionGuardService completionGuardService,
            AttachedUserMessageComposer attachedUserMessageComposer) {
        super(thinkProcessService, properties, engineChatFactory, llmCallTracker,
                streamingProperties, objectMapper, enginePromptResolver, systemPromptComposer,
                skillResolver, skillPromptComposer, sessionService, promptDateContextResolver,
                scratchpadPromptContributor, memoryContextLoader, modelCatalog,
                memoryCompactionService, turnContextHandlers, completionGuardService,
                attachedUserMessageComposer);
        this.processes = thinkProcessService;
    }

    @Override
    public String name() {
        return NAME;
    }

    /**
     * A question parks the worker; anything else closes it as Frankie
     * would.
     *
     * <p>The marker is read from a fresh copy: the tool set it during
     * this very turn, so the document this method was handed is older
     * than the fact it needs.
     */
    @Override
    protected @Nullable ThinkProcessStatus onWorkerTerminate(ThinkProcessDocument process) {
        if (!consumeAskPending(process.getId())) {
            return super.onWorkerTerminate(process);
        }
        log.info("Trillian worker id='{}' asked a question — staying IDLE with its context",
                process.getId());
        return ThinkProcessStatus.IDLE;
    }

    /** Reads and clears the marker; {@code false} when it was not set. */
    private boolean consumeAskPending(String processId) {
        try {
            ThinkProcessDocument fresh = processes.findById(processId).orElse(null);
            Map<String, Object> overrides =
                    fresh == null ? null : fresh.getEngineParamOverrides();
            Object raw = overrides == null ? null : overrides.get(PARAM_ASK_PENDING);
            if (!Boolean.TRUE.equals(raw)) {
                return false;
            }
            processes.setEngineParamOverride(processId, PARAM_ASK_PENDING, null);
            return true;
        } catch (RuntimeException e) {
            // Closing is the safe reading of an unclear state: a worker
            // wrongly kept alive waits forever, one wrongly closed costs
            // a re-spawn.
            log.warn("Trillian worker id='{}' could not read its ask marker: {}",
                    processId, e.toString());
            return false;
        }
    }
}
