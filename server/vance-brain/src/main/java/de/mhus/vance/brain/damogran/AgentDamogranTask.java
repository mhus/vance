package de.mhus.vance.brain.damogran;

import static de.mhus.vance.brain.damogran.DamogranTaskSupport.intOr;
import static de.mhus.vance.brain.damogran.DamogranTaskSupport.requireString;

import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.brain.damogran.DamogranManifest.TaskSpec;
import de.mhus.vance.brain.enginemessage.EngineMessageRouter;
import de.mhus.vance.brain.scheduling.LaneScheduler;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.thinkprocess.PendingMessageDocument;
import de.mhus.vance.shared.thinkprocess.PendingMessageType;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Built-in {@code agent} task: delivers its {@code prompt} as a chat turn to the
 * compose's <b>session process</b> — which, when the {@code session:} section
 * carries a {@code recipe}, is a conversational agent (see
 * {@link DamogranProcessResolver#resolveComposeSession}). Unlike {@code spawn}
 * (a fresh fire-and-forget child per run), the agent task reuses the stable
 * session process, so successive runs continue one conversation.
 *
 * <p><b>Blocking</b>: the task waits for the turn to finish before returning, so
 * the compose behaves like every other compose task — the Run button stays busy
 * and the output appears when it is ready. Completion is a lane barrier: the
 * per-process lane is strictly serial, so a no-op enqueued right after the turn
 * runs only once the turn has (see {@code LaneScheduler}). The result then pins
 * the concrete answer message — {@code vance-process:<pid>/<msgId>} — rather than
 * a bare process ref that would drift to a later answer on the next run.
 */
@Slf4j
@Service
class AgentDamogranTask implements DamogranTask {

    /** Default upper bound on how long to wait for the agent's turn to finish. */
    private static final int DEFAULT_DEADLINE_SECONDS = 300;

    private final ObjectProvider<EngineMessageRouter> engineMessageRouterProvider;
    private final LaneScheduler laneScheduler;
    private final ThinkProcessService thinkProcessService;
    private final ChatMessageService chatMessageService;

    AgentDamogranTask(ObjectProvider<EngineMessageRouter> engineMessageRouterProvider,
                      LaneScheduler laneScheduler,
                      ThinkProcessService thinkProcessService,
                      ChatMessageService chatMessageService) {
        this.engineMessageRouterProvider = engineMessageRouterProvider;
        this.laneScheduler = laneScheduler;
        this.thinkProcessService = thinkProcessService;
        this.chatMessageService = chatMessageService;
    }

    @Override
    public String type() {
        return "agent";
    }

    @Override
    public DamogranTaskResult execute(DamogranContext ctx, TaskSpec spec) {
        String processId = ctx.processId();
        if (processId == null) {
            return DamogranTaskResult.failure(
                    "agent task requires an enabled session — add 'session: { enabled: true, recipe: ... }'");
        }
        ThinkProcessDocument process = thinkProcessService.findById(processId).orElse(null);
        if (process == null) {
            return DamogranTaskResult.failure("agent: session process not found");
        }
        String prompt = requireString(spec, "prompt");
        int deadlineSeconds = intOr(spec, "deadlineSeconds", DEFAULT_DEADLINE_SECONDS);

        // Anchor: only messages created after we dispatch count as this turn's
        // answer (the process is reused, so an earlier answer may already exist).
        Instant since = Instant.now();

        PendingMessageDocument turn = PendingMessageDocument.builder()
                .type(PendingMessageType.USER_CHAT_INPUT)
                .at(since)
                .fromUser("damogran:agent")
                .content(prompt)
                .build();
        boolean delivered = engineMessageRouterProvider.getObject().dispatch(null, processId, turn);
        if (!delivered) {
            return DamogranTaskResult.failure(
                    "agent: could not deliver prompt to session process " + processId);
        }

        // Lane barrier: the dispatch scheduled the turn on the process's serial
        // lane; a no-op enqueued now completes only after that turn has run.
        try {
            laneScheduler.submit(processId, () -> null).get(deadlineSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException te) {
            return DamogranTaskResult.failure(
                    "agent: no answer within " + deadlineSeconds + "s");
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return DamogranTaskResult.failure("agent: interrupted while waiting for the answer");
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause() == null ? ee : ee.getCause();
            return DamogranTaskResult.failure("agent: turn failed: " + cause.getMessage());
        }

        ChatMessageDocument answer = lastAnswerSince(
                process.getTenantId(), process.getSessionId(), processId, since);
        if (answer == null) {
            log.debug("Damogran agent: turn on '{}' produced no assistant answer", processId);
            return DamogranTaskResult.success(
                    List.of(OutputArtifact.process(processId)), "agent produced no answer");
        }
        log.debug("Damogran agent: process '{}' answered in message '{}'", processId, answer.getId());
        return DamogranTaskResult.success(
                List.of(OutputArtifact.process(processId, answer.getId())),
                "agent answered (" + answer.getId() + ")");
    }

    /** The latest ASSISTANT message created at/after {@code since} for this process. */
    private ChatMessageDocument lastAnswerSince(
            String tenantId, String sessionId, String processId, Instant since) {
        List<ChatMessageDocument> msgs = chatMessageService.activeHistory(tenantId, sessionId, processId);
        for (int i = msgs.size() - 1; i >= 0; i--) {
            ChatMessageDocument m = msgs.get(i);
            if (m.getRole() == ChatRole.ASSISTANT
                    && m.getContent() != null && !m.getContent().isBlank()
                    && m.getCreatedAt() != null && !m.getCreatedAt().isBefore(since)) {
                return m;
            }
        }
        return null;
    }
}
