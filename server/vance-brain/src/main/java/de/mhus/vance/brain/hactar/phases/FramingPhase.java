package de.mhus.vance.brain.hactar.phases;

import static de.mhus.vance.brain.hactar.phases.HactarContextRenderer.paramString;

import de.mhus.vance.api.hactar.HactarState;
import de.mhus.vance.api.hactar.HactarStatus;
import de.mhus.vance.brain.ai.EngineChatFactory;
import de.mhus.vance.brain.progress.LlmCallTracker;
import de.mhus.vance.brain.prompt.PromptContextBuilder;
import de.mhus.vance.brain.prompt.PromptTemplateRenderer;
import de.mhus.vance.brain.skill.ResolvedSkill;
import de.mhus.vance.brain.thinkengine.EnginePromptResolver;
import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * FRAMING — single LLM call that produces a Markdown plan sketch
 * (Goal recap / Approach / Steps / Tools called / Edge cases /
 * Return value). No code in this phase. Same prompt enrichment as
 * DRAFTING (toolInventory, manualInventory, skillGuidance) plus a
 * {@code recoveryHint} when re-framing after a REJECTED review.
 *
 * <p>Always transitions to REVIEWING. The REVIEWING phase then
 * decides whether to advance to DRAFTING (APPROVED / no reviewer
 * configured) or loop back here (REJECTED).
 */
@Component("deepThoughtFramingPhase")
@RequiredArgsConstructor
@Slf4j
public class FramingPhase {

    public static final String ENGINE_NAME = "hactar";

    private static final String FRAMING_PROMPT_PATH = "_vance/prompts/hactar-framing.md";
    private static final String FRAMING_FALLBACK_PROMPT =
            "You are the FRAMING node of Hactar. Write a "
                    + "structured plan sketch for a JavaScript orchestrator "
                    + "script that fulfils the goal: {{ goal }}. Do not "
                    + "write code yet — just the plan.";

    private final EngineChatFactory engineChatFactory;
    private final EnginePromptResolver enginePromptResolver;
    private final PromptTemplateRenderer promptTemplateRenderer;
    private final LlmCallTracker llmCallTracker;
    private final HactarContextRenderer contextRenderer;

    public HactarStatus execute(
            HactarState state,
            ThinkProcessDocument process,
            ThinkEngineContext ctx) {
        EngineChatFactory.EngineChatBundle bundle =
                engineChatFactory.forProcess(process, ctx, ENGINE_NAME);
        String modelAlias = bundle.primaryConfig().provider()
                + ":" + bundle.primaryConfig().modelName();

        String basePath = paramString(process, "framingPromptDocument", FRAMING_PROMPT_PATH);
        String systemTpl = enginePromptResolver.resolve(
                process, basePath, FRAMING_FALLBACK_PROMPT);
        List<ResolvedSkill> architectSkills =
                contextRenderer.resolveScriptArchitectSkills(process);
        Map<String, Object> ctxMap = new LinkedHashMap<>(
                PromptContextBuilder.forProcess(process, null)
                        .engine(ENGINE_NAME)
                        .build());
        ctxMap.put("goal", state.getGoal() == null ? "" : state.getGoal());
        ctxMap.put("toolInventory", contextRenderer.renderToolInventory(process, ctx));
        ctxMap.put("manualInventory",
                contextRenderer.renderManualInventory(process, architectSkills));
        ctxMap.put("skillGuidance", contextRenderer.renderSkillGuidance(architectSkills));
        ctxMap.put("recoveryHint",
                state.getFramingRecoveryCount() > 0 && state.getReviewerNotes() != null
                        ? state.getReviewerNotes() : "");
        String renderedSystem = promptTemplateRenderer.render(systemTpl, ctxMap);

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(renderedSystem == null ? "" : renderedSystem));
        messages.add(UserMessage.from(
                "Goal:\n" + (state.getGoal() == null ? "" : state.getGoal())
                        + "\n\nProduce the plan sketch now. Follow the "
                        + "section structure exactly."));

        long startMs = System.currentTimeMillis();
        ChatRequest request = ChatRequest.builder().messages(messages).build();
        ChatResponse response = bundle.chat().chatModel().chat(request);
        llmCallTracker.record(process, request, response,
                System.currentTimeMillis() - startMs, modelAlias);

        String reply = response.aiMessage() == null
                ? null : response.aiMessage().text();
        if (reply == null || reply.isBlank()) {
            state.setFailureReason(
                    "FRAMING returned an empty reply from the LLM");
            return HactarStatus.FAILED;
        }
        state.setPlanSketch(reply.trim());
        // Clear the previous reviewer fields — REVIEWING repopulates.
        state.setReviewerVerdict(null);
        state.setReviewerNotes(null);
        log.info("Hactar.runFraming id='{}' attempt {} produced {} chars",
                process.getId(), state.getFramingRecoveryCount() + 1,
                reply.length());
        return HactarStatus.REVIEWING;
    }
}
