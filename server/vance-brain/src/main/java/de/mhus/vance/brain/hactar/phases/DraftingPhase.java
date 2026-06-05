package de.mhus.vance.brain.hactar.phases;

import static de.mhus.vance.brain.hactar.phases.HactarContextRenderer.paramString;

import de.mhus.vance.api.hactar.HactarState;
import de.mhus.vance.api.hactar.HactarState.ValidationError;
import de.mhus.vance.api.hactar.HactarStatus;
import de.mhus.vance.brain.ai.EngineChatFactory;
import de.mhus.vance.brain.progress.LlmCallTracker;
import de.mhus.vance.brain.prompt.PromptContextBuilder;
import de.mhus.vance.brain.thinkengine.SystemPromptComposer;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * DRAFTING — one LLM call that produces a {@code ```javascript}
 * fenced body. System prompt is cascade-resolved + Pebble-rendered
 * with tool/manual/skill inventory variables. User message carries
 * the goal plus (optional) approved plan sketch + (on a recovery
 * attempt) the previous draft and the validation errors that killed it.
 *
 * <p>Always transitions to VALIDATING. A reply without a parseable
 * fence is stored verbatim and queued as a synthetic validation error
 * — the regular recovery loop kicks in.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DraftingPhase {

    public static final String ENGINE_NAME = "hactar";

    private static final String DRAFTING_PROMPT_PATH = "_vance/prompts/hactar-drafting.md";
    private static final String DRAFTING_FALLBACK_PROMPT =
            "You are the DRAFTING node of the Hactar engine. "
                    + "Reply with EXACTLY one ```javascript fenced block "
                    + "containing an IIFE that fulfils the goal: {{ goal }}";

    private static final Pattern JS_FENCE = Pattern.compile(
            "```(?:javascript|js)?\\s*\\R([\\s\\S]*?)\\R```",
            Pattern.MULTILINE);

    private final EngineChatFactory engineChatFactory;
    private final EnginePromptResolver enginePromptResolver;
    private final SystemPromptComposer composer;
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

        String basePath = paramString(process, "promptDocument", DRAFTING_PROMPT_PATH);
        String systemTpl = enginePromptResolver.resolve(
                process, basePath, DRAFTING_FALLBACK_PROMPT);
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
        ctxMap.put("addonSections", composer.renderAddons(ENGINE_NAME, ctxMap));
        String renderedSystem = composer.render(systemTpl, ctxMap);

        String userMessage = buildUserMessage(state);

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(renderedSystem == null ? "" : renderedSystem));
        messages.add(UserMessage.from(userMessage));

        long startMs = System.currentTimeMillis();
        ChatRequest request = ChatRequest.builder().messages(messages).build();
        ChatResponse response = bundle.chat().chatModel().chat(request);
        llmCallTracker.record(process, request, response,
                System.currentTimeMillis() - startMs, modelAlias);

        String reply = response.aiMessage() == null
                ? null : response.aiMessage().text();
        String body = extractJsBody(reply == null ? "" : reply);
        if (body == null || body.isBlank()) {
            log.warn("Hactar.runDrafting id='{}' reply had no parseable "
                            + "```javascript fence (reply chars={})",
                    process.getId(), reply == null ? 0 : reply.length());
            state.setGeneratedCode(reply == null ? "" : reply);
            List<ValidationError> errs = new ArrayList<>();
            errs.add(ValidationError.builder()
                    .sourceName("draft.js")
                    .line(0).column(0)
                    .message("LLM reply contained no ```javascript fenced "
                            + "block — re-emit with proper fences.")
                    .build());
            state.setValidationErrors(errs);
            return HactarStatus.VALIDATING;
        }

        state.setGeneratedCode(body);
        state.getValidationErrors().clear();
        log.info("Hactar.runDrafting id='{}' attempt {} drafted {} chars",
                process.getId(), state.getRecoveryCount() + 1, body.length());
        return HactarStatus.VALIDATING;
    }

    private static String buildUserMessage(HactarState state) {
        StringBuilder sb = new StringBuilder();
        // Approved plan sketch (when FRAMING ran) comes first — gives
        // the DRAFTING LLM the structural anchor. On a DRAFTING
        // recovery the previous-draft block sits below this so the
        // plan stays the dominant context.
        if (state.getPlanSketch() != null && !state.getPlanSketch().isBlank()) {
            sb.append("## Approved plan sketch\n\n")
                    .append(state.getPlanSketch())
                    .append("\n\n");
            if (state.getReviewerNotes() != null
                    && !state.getReviewerNotes().isBlank()
                    && "APPROVED".equals(state.getReviewerVerdict())) {
                sb.append("## Reviewer notes (incorporate these)\n\n")
                        .append(state.getReviewerNotes())
                        .append("\n\n");
            }
        }
        if (state.getRecoveryCount() > 0 && !state.getValidationErrors().isEmpty()) {
            sb.append("================================================\n");
            sb.append("⚠  PREVIOUS DRAFT FAILED VALIDATION ⚠\n");
            sb.append("================================================\n\n");
            if (state.getGeneratedCode() != null && !state.getGeneratedCode().isBlank()) {
                sb.append("Previous draft:\n```javascript\n")
                        .append(state.getGeneratedCode())
                        .append("\n```\n\n");
            }
            sb.append("Errors that must be fixed:\n");
            for (ValidationError e : state.getValidationErrors()) {
                sb.append("  - ");
                if (e.getLine() > 0 || e.getColumn() > 0) {
                    sb.append("line ").append(e.getLine())
                            .append(", col ").append(e.getColumn())
                            .append(": ");
                }
                sb.append(e.getMessage() == null ? "(no message)" : e.getMessage())
                        .append('\n');
            }
            sb.append("\nRe-emit the COMPLETE corrected script. Keep the "
                    + "structure of the previous draft; only fix the listed "
                    + "errors.\n");
            sb.append("================================================\n\n");
        }
        sb.append("Goal:\n").append(state.getGoal() == null ? "" : state.getGoal());
        sb.append("\n\nReply ONLY with a single ```javascript fenced block. "
                + "No prose before or after.");
        return sb.toString();
    }

    static @Nullable String extractJsBody(@Nullable String reply) {
        if (reply == null || reply.isBlank()) return null;
        Matcher m = JS_FENCE.matcher(reply);
        if (!m.find()) return null;
        String body = m.group(1);
        return body == null || body.isBlank() ? null : body;
    }
}
