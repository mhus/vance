package de.mhus.vance.brain.ws.handlers;

import de.mhus.vance.api.skills.ActiveSkillRefDto;
import de.mhus.vance.api.skills.ProcessSkillCommand;
import de.mhus.vance.api.skills.ProcessSkillRequest;
import de.mhus.vance.api.skills.ProcessSkillResponse;
import de.mhus.vance.api.skills.ScriptParamDto;
import de.mhus.vance.api.skills.SkillArgumentDto;
import de.mhus.vance.api.skills.SkillReferenceDocDto;
import de.mhus.vance.api.skills.SkillScriptDto;
import de.mhus.vance.api.skills.SkillSummaryDto;
import de.mhus.vance.api.skills.SkillTriggerDto;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.brain.command.EngineCommand;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.brain.scheduling.LaneScheduler;
import de.mhus.vance.brain.skill.ResolvedSkill;
import de.mhus.vance.brain.skill.SkillSteerProcessor;
import de.mhus.vance.brain.skill.UnknownSkillException;
import de.mhus.vance.brain.ws.ConnectionContext;
import de.mhus.vance.brain.ws.WebSocketSender;
import de.mhus.vance.brain.ws.WsHandler;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.skill.ActiveSkillRefEmbedded;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

/**
 * Activate / clear / list skills on a named think-process. Mutations
 * write straight to the process document via {@link SkillSteerProcessor};
 * no LLM turn is triggered. The next chat-turn the user kicks off
 * (through {@code process-steer}) will pick the new skill set up
 * automatically.
 *
 * <p>For {@link ProcessSkillCommand#LIST}, the response carries both
 * the current {@code activeSkills} and the union of skills available
 * in the process's scope (cascade-deduped). Other commands return only
 * the post-mutation {@code activeSkills}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProcessSkillHandler implements WsHandler {

    private final ObjectMapper objectMapper;
    private final WebSocketSender sender;
    private final ThinkProcessService thinkProcessService;
    private final SkillSteerProcessor skillSteerProcessor;
    private final RequestAuthority authority;
    private final LaneScheduler laneScheduler;

    @Override
    public String type() {
        return MessageType.PROCESS_SKILL;
    }

    @Override
    public void handle(ConnectionContext ctx, WebSocketSession wsSession, WebSocketEnvelope envelope)
            throws IOException {
        ProcessSkillRequest request;
        try {
            request = objectMapper.convertValue(envelope.getData(), ProcessSkillRequest.class);
        } catch (IllegalArgumentException e) {
            sender.sendError(wsSession, envelope, 400, "Invalid process-skill payload: " + e.getMessage());
            return;
        }
        if (request == null || isBlank(request.getProcessName()) || request.getCommand() == null) {
            sender.sendError(wsSession, envelope, 400, "processName and command are required");
            return;
        }
        String tenantId = ctx.getTenantId();
        String sessionId = ctx.getSessionId();
        if (sessionId == null) {
            sender.sendError(wsSession, envelope, 500, "Session bound but sessionId missing");
            return;
        }
        Optional<ThinkProcessDocument> processOpt =
                thinkProcessService.findByName(tenantId, sessionId, request.getProcessName());
        if (processOpt.isEmpty()) {
            sender.sendError(
                    wsSession,
                    envelope,
                    404,
                    "Think-process '" + request.getProcessName() + "' not found in session '" + sessionId + "'");
            return;
        }
        ThinkProcessDocument process = processOpt.get();
        ProcessSkillCommand command = request.getCommand();
        Action action = command == ProcessSkillCommand.LIST ? Action.READ : Action.WRITE;
        authority.enforce(
                ctx,
                new Resource.ThinkProcess(
                        process.getTenantId(),
                        process.getProjectId(),
                        process.getSessionId(),
                        process.getId() == null ? "" : process.getId()),
                action);

        // Argument validation per command.
        switch (command) {
            case ACTIVATE, CLEAR -> {
                if (isBlank(request.getSkillName())) {
                    sender.sendError(wsSession, envelope, 400, "skillName is required for command " + command);
                    return;
                }
            }
            case CLEAR_ALL, LIST -> {
                // no extra args
            }
        }

        if (command == ProcessSkillCommand.LIST) {
            // Read-only — no lane needed.
            applyAndReply(wsSession, envelope, process, request, command, ctx.getUserId());
            return;
        }

        // ACTIVATE / CLEAR / CLEAR_ALL may fire a skill's activate/deactivate
        // engine-command sequence → run on the process lane so a command
        // can't race an in-flight turn (planning/engine-commands.md §4.2).
        String processId = process.getId();
        String userId = ctx.getUserId();
        laneScheduler.submit(processId, () -> {
            ThinkProcessDocument fresh = processId == null
                    ? null
                    : thinkProcessService.findById(processId).orElse(null);
            if (fresh == null) {
                trySendError(
                        wsSession,
                        envelope,
                        404,
                        "Think-process '" + request.getProcessName() + "' disappeared before skill op");
                return;
            }
            applyAndReply(wsSession, envelope, fresh, request, command, userId);
        });
    }

    /**
     * Runs the skill mutation and ships the reply (or an error frame).
     * Never throws — an {@link IOException} on send is logged.
     */
    private void applyAndReply(
            WebSocketSession wsSession,
            WebSocketEnvelope envelope,
            ThinkProcessDocument process,
            ProcessSkillRequest request,
            ProcessSkillCommand command,
            @Nullable String userId) {
        List<ActiveSkillRefEmbedded> active;
        de.mhus.vance.brain.skill.SkillSteerProcessor.ActivationResult activation = null;
        try {
            if (command == ProcessSkillCommand.ACTIVATE) {
                // The full result, not just the active list: "freshly
                // activated" (turn fired) and "already active" are different
                // sentences for the user.
                activation = skillSteerProcessor.activate(
                        process, request.getSkillName(), request.isOneShot(), request.getArgs(), userId);
                active = activation.activeAfter();
            } else {
                active = skillSteerProcessor.apply(
                        process, command, request.getSkillName(), request.isOneShot(), request.getArgs(), userId);
            }
        } catch (UnknownSkillException e) {
            trySendError(wsSession, envelope, 404, e.getMessage());
            return;
        } catch (de.mhus.vance.brain.skill.DisabledSkillException e) {
            trySendError(wsSession, envelope, 400, e.getMessage());
            return;
        } catch (IllegalArgumentException e) {
            trySendError(wsSession, envelope, 400, e.getMessage());
            return;
        } catch (RuntimeException e) {
            log.warn("process-skill failed process='{}' cmd={}", request.getProcessName(), command, e);
            trySendError(wsSession, envelope, 500, "Skill operation failed: " + e.getMessage());
            return;
        }

        ProcessSkillResponse.ProcessSkillResponseBuilder responseBuilder = ProcessSkillResponse.builder()
                .processName(request.getProcessName())
                .activeSkills(toActiveDtoList(active));

        if (activation != null) {
            responseBuilder
                    .newlyActivated(activation.newlyActivated())
                    .lifecycle(activation.skill().lifecycle().name().toLowerCase());
        }

        if (command == ProcessSkillCommand.LIST) {
            List<SkillSummaryDto> available = new ArrayList<>();
            for (ResolvedSkill skill : skillSteerProcessor.listAvailable(process)) {
                available.add(toSummary(skill));
            }
            responseBuilder.availableSkills(available);
        }

        try {
            sender.sendReply(wsSession, envelope, MessageType.PROCESS_SKILL, responseBuilder.build());
        } catch (IOException e) {
            log.warn("Failed to ship process-skill reply: {}", e.toString());
        }
    }

    private void trySendError(WebSocketSession wsSession, WebSocketEnvelope envelope, int code, String message) {
        try {
            sender.sendError(wsSession, envelope, code, message);
        } catch (IOException e) {
            log.warn("Failed to send process-skill error: {}", e.toString());
        }
    }

    /**
     * Maps the embedded active-skill refs for the wire. Package-private so
     * the DTO contract (notably {@code fromRecipe}) is unit-tested at the
     * mapping site, not through a full WS round-trip.
     */
    static List<ActiveSkillRefDto> toActiveDtoList(List<ActiveSkillRefEmbedded> active) {
        List<ActiveSkillRefDto> out = new ArrayList<>(active.size());
        for (ActiveSkillRefEmbedded ref : active) {
            out.add(ActiveSkillRefDto.builder()
                    .name(ref.getName())
                    .resolvedFromScope(ref.getResolvedFromScope())
                    .oneShot(ref.isOneShot())
                    .fromRecipe(ref.isFromRecipe())
                    .activatedAt(ref.getActivatedAt())
                    .args(ref.getArgs())
                    .build());
        }
        return out;
    }

    /**
     * Maps a resolved skill for the picker/listing. Package-private for the
     * same reason as {@link #toActiveDtoList} — the full projection
     * (triggers, lifecycle, arguments, command sequences) is DTO
     * contract, tested directly.
     */
    static SkillSummaryDto toSummary(ResolvedSkill skill) {
        return SkillSummaryDto.builder()
                .name(skill.name())
                .title(skill.title())
                .description(skill.description())
                .version(skill.version())
                .tags(skill.tags())
                .triggers(toTriggerDtos(skill.triggers()))
                .lifecycle(skill.lifecycle().name().toLowerCase())
                .tools(skill.tools())
                .manualPaths(skill.manualPaths())
                .arguments(toArgumentDtos(skill.arguments()))
                .referenceDocs(toReferenceDocDtos(skill.referenceDocs()))
                .scripts(toScriptDtos(skill.scripts()))
                .activate(renderCommands(skill.activate()))
                .deactivate(renderCommands(skill.deactivate()))
                .enabled(skill.enabled())
                .source(skill.source())
                .build();
    }

    private static List<SkillTriggerDto> toTriggerDtos(List<ResolvedSkill.Trigger> triggers) {
        List<SkillTriggerDto> out = new ArrayList<>(triggers.size());
        for (ResolvedSkill.Trigger trigger : triggers) {
            out.add(SkillTriggerDto.builder()
                    .type(trigger.type())
                    .pattern(trigger.pattern())
                    .keywords(trigger.keywords())
                    .build());
        }
        return out;
    }

    private static List<SkillArgumentDto> toArgumentDtos(List<ResolvedSkill.Argument> arguments) {
        List<SkillArgumentDto> out = new ArrayList<>(arguments.size());
        for (ResolvedSkill.Argument argument : arguments) {
            out.add(SkillArgumentDto.builder()
                    .name(argument.name())
                    .type(argument.type())
                    .description(argument.description())
                    .required(argument.required())
                    .build());
        }
        return out;
    }

    private static List<SkillReferenceDocDto> toReferenceDocDtos(List<ResolvedSkill.ReferenceDoc> referenceDocs) {
        List<SkillReferenceDocDto> out = new ArrayList<>(referenceDocs.size());
        for (ResolvedSkill.ReferenceDoc doc : referenceDocs) {
            out.add(SkillReferenceDocDto.builder()
                    .title(doc.title())
                    .summary(doc.summary())
                    .loadMode(doc.loadMode())
                    .build());
        }
        return out;
    }

    private static List<SkillScriptDto> toScriptDtos(List<ResolvedSkill.Script> scripts) {
        List<SkillScriptDto> out = new ArrayList<>(scripts.size());
        for (ResolvedSkill.Script script : scripts) {
            out.add(SkillScriptDto.builder()
                    .name(script.name())
                    .target(script.target())
                    .description(script.description())
                    .params(toScriptParamDtos(script.params()))
                    .build());
        }
        return out;
    }

    private static List<ScriptParamDto> toScriptParamDtos(List<ResolvedSkill.Script.ScriptParam> params) {
        List<ScriptParamDto> out = new ArrayList<>(params.size());
        for (ResolvedSkill.Script.ScriptParam param : params) {
            out.add(ScriptParamDto.builder()
                    .name(param.name())
                    .type(param.type())
                    .description(param.description())
                    .required(param.required())
                    .build());
        }
        return out;
    }

    /**
     * Renders the parsed command back to the canonical {@code verb rest…}
     * string the author wrote (skills.md §2a) — the display form for
     * read-only listings.
     */
    private static List<String> renderCommands(List<EngineCommand> commands) {
        List<String> out = new ArrayList<>(commands.size());
        for (EngineCommand command : commands) {
            Object text = command.args().get("text");
            out.add(text == null ? command.name() : command.name() + " " + text);
        }
        return out;
    }

    private static boolean isBlank(@org.jspecify.annotations.Nullable String s) {
        return s == null || s.isBlank();
    }
}
