package de.mhus.vance.brain.ws.handlers;

import de.mhus.vance.api.skills.ActiveSkillRefDto;
import de.mhus.vance.api.skills.ProcessSkillCommand;
import de.mhus.vance.api.skills.ProcessSkillRequest;
import de.mhus.vance.api.skills.ProcessSkillResponse;
import de.mhus.vance.api.skills.SkillSummaryDto;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.brain.skill.ResolvedSkill;
import de.mhus.vance.brain.permission.RequestAuthority;
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

    @Override
    public String type() {
        return MessageType.PROCESS_SKILL;
    }

    @Override
    public void handle(
            ConnectionContext ctx, WebSocketSession wsSession, WebSocketEnvelope envelope)
            throws IOException {
        ProcessSkillRequest request;
        try {
            request = objectMapper.convertValue(envelope.getData(), ProcessSkillRequest.class);
        } catch (IllegalArgumentException e) {
            sender.sendError(wsSession, envelope, 400,
                    "Invalid process-skill payload: " + e.getMessage());
            return;
        }
        if (request == null || isBlank(request.getProcessName()) || request.getCommand() == null) {
            sender.sendError(wsSession, envelope, 400,
                    "processName and command are required");
            return;
        }
        String tenantId = ctx.getTenantId();
        String sessionId = ctx.getSessionId();
        if (sessionId == null) {
            sender.sendError(wsSession, envelope, 500, "Session bound but sessionId missing");
            return;
        }
        Optional<ThinkProcessDocument> processOpt = thinkProcessService.findByName(
                tenantId, sessionId, request.getProcessName());
        if (processOpt.isEmpty()) {
            sender.sendError(wsSession, envelope, 404,
                    "Think-process '" + request.getProcessName() + "' not found in session '"
                            + sessionId + "'");
            return;
        }
        ThinkProcessDocument process = processOpt.get();
        ProcessSkillCommand command = request.getCommand();
        Action action = command == ProcessSkillCommand.LIST ? Action.READ : Action.WRITE;
        authority.enforce(ctx,
                new Resource.ThinkProcess(process.getTenantId(), process.getProjectId(),
                        process.getSessionId(), process.getId() == null ? "" : process.getId()),
                action);

        // Argument validation per command.
        switch (command) {
            case ACTIVATE, CLEAR -> {
                if (isBlank(request.getSkillName())) {
                    sender.sendError(wsSession, envelope, 400,
                            "skillName is required for command " + command);
                    return;
                }
            }
            case CLEAR_ALL, LIST -> {
                // no extra args
            }
        }

        List<ActiveSkillRefEmbedded> active;
        try {
            active = skillSteerProcessor.apply(
                    process,
                    command,
                    request.getSkillName(),
                    request.isOneShot());
        } catch (UnknownSkillException e) {
            sender.sendError(wsSession, envelope, 404, e.getMessage());
            return;
        } catch (IllegalArgumentException e) {
            sender.sendError(wsSession, envelope, 400, e.getMessage());
            return;
        } catch (RuntimeException e) {
            log.warn("process-skill failed tenant='{}' session='{}' process='{}' cmd={}",
                    tenantId, sessionId, request.getProcessName(), command, e);
            sender.sendError(wsSession, envelope, 500,
                    "Skill operation failed: " + e.getMessage());
            return;
        }

        ProcessSkillResponse.ProcessSkillResponseBuilder responseBuilder =
                ProcessSkillResponse.builder()
                        .processName(request.getProcessName())
                        .activeSkills(toActiveDtoList(active));

        if (command == ProcessSkillCommand.LIST) {
            List<SkillSummaryDto> available = new ArrayList<>();
            for (ResolvedSkill skill : skillSteerProcessor.listAvailable(process)) {
                available.add(toSummary(skill));
            }
            responseBuilder.availableSkills(available);
        }

        sender.sendReply(wsSession, envelope, MessageType.PROCESS_SKILL, responseBuilder.build());
    }

    private static List<ActiveSkillRefDto> toActiveDtoList(List<ActiveSkillRefEmbedded> active) {
        List<ActiveSkillRefDto> out = new ArrayList<>(active.size());
        for (ActiveSkillRefEmbedded ref : active) {
            out.add(ActiveSkillRefDto.builder()
                    .name(ref.getName())
                    .resolvedFromScope(ref.getResolvedFromScope())
                    .oneShot(ref.isOneShot())
                    .activatedAt(ref.getActivatedAt())
                    .build());
        }
        return out;
    }

    private static SkillSummaryDto toSummary(ResolvedSkill skill) {
        return SkillSummaryDto.builder()
                .name(skill.name())
                .title(skill.title())
                .description(skill.description())
                .version(skill.version())
                .tags(skill.tags())
                .enabled(skill.enabled())
                .source(skill.source())
                .build();
    }

    private static boolean isBlank(@org.jspecify.annotations.Nullable String s) {
        return s == null || s.isBlank();
    }
}
