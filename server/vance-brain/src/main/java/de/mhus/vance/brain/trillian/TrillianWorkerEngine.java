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
     * engineParamOverrides marker set by {@code trillian_ask}: a question
     * is open and unanswered.
     *
     * <p>Carried on the process rather than through the tool result,
     * because the result protocol is Frankie's and this must not touch
     * it.
     *
     * <p>It survives the park, and is cleared at the start of the next
     * turn — the worker running again <em>is</em> the answer arriving.
     * That makes it the honest answer to "is this IDLE worker waiting for
     * someone, or did it simply finish?", which a Nature needs before it
     * describes a parked worker or spends a re-check on it. Clearing it
     * on the way out (as the first version did) collapsed both cases into
     * one and left the obstacle markers speaking for a worker that had
     * long since carried on.</p>
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
            AttachedUserMessageComposer attachedUserMessageComposer,
            de.mhus.vance.brain.prompt.ClientTurnContextResolver clientTurnContextResolver) {
        super(thinkProcessService, properties, engineChatFactory, llmCallTracker,
                streamingProperties, objectMapper, enginePromptResolver, systemPromptComposer,
                skillResolver, skillPromptComposer, sessionService, promptDateContextResolver,
                scratchpadPromptContributor, memoryContextLoader, modelCatalog,
                memoryCompactionService, turnContextHandlers, completionGuardService,
                attachedUserMessageComposer, clientTurnContextResolver);
        this.processes = thinkProcessService;
    }

    @Override
    public String name() {
        return NAME;
    }

    /**
     * A turn starting means the worker is no longer waiting: whatever it
     * asked has been answered, or somebody nudged it to look again.
     * Either way the question is no longer open, so the marker goes
     * before Frankie's loop runs — a question raised <em>during</em> this
     * turn sets it again and is read on the way out.
     *
     * <p>The obstacle markers ({@code blocker}, probe budget) are
     * deliberately left standing: a re-ask of the same question has to
     * keep the budget it has already spent, and
     * {@code TrillianAskTool} resets them itself when the question
     * changes.
     */
    @Override
    public void runTurn(ThinkProcessDocument process,
                        de.mhus.vance.brain.thinkengine.ThinkEngineContext ctx) {
        if (askPending(process.getId())) {
            try {
                processes.setEngineParamOverride(process.getId(), PARAM_ASK_PENDING, null);
            } catch (RuntimeException e) {
                // A stale marker makes a later real termination park
                // instead of close — bad, but not worth losing the turn.
                log.warn("Trillian worker id='{}' could not clear its ask marker: {}",
                        process.getId(), e.toString());
            }
        }
        super.runTurn(process, ctx);
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
        if (!askPending(process.getId())) {
            return super.onWorkerTerminate(process);
        }
        log.info("Trillian worker id='{}' asked a question — staying IDLE with its context",
                process.getId());
        return ThinkProcessStatus.IDLE;
    }

    /** Whether an unanswered question is open on this worker. */
    private boolean askPending(String processId) {
        try {
            ThinkProcessDocument fresh = processes.findById(processId).orElse(null);
            Map<String, Object> overrides =
                    fresh == null ? null : fresh.getEngineParamOverrides();
            Object raw = overrides == null ? null : overrides.get(PARAM_ASK_PENDING);
            return Boolean.TRUE.equals(raw);
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
