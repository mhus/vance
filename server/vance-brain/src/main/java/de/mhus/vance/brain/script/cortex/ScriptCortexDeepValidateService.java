package de.mhus.vance.brain.script.cortex;

import de.mhus.vance.api.scripts.ScriptDeepValidateResponse;
import de.mhus.vance.api.scripts.ScriptDeepWarning;
import de.mhus.vance.brain.ai.AiChat;
import de.mhus.vance.brain.ai.AiChatConfig;
import de.mhus.vance.brain.ai.AiModelResolver;
import de.mhus.vance.brain.ai.AiModelService;
import de.mhus.vance.brain.ai.ChatBehaviorBuilder;
import de.mhus.vance.shared.settings.SettingService;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * One-shot LLM review of a Script-Cortex JavaScript file. The model is
 * asked to flag infinite-loop suspects, blocking I/O, missing returns,
 * header-tag problems and obvious style issues, and to reply as a
 * compact JSON object the service can parse.
 *
 * <p>Uses {@code default:fast} as the model alias — Deep-Validate is a
 * convenience check, not a structured-reasoning pass; the cheap model
 * is plenty.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ScriptCortexDeepValidateService {

    private static final String MODEL_ALIAS = "default:fast";

    private static final String SYSTEM_PROMPT = """
            You are a senior JavaScript reviewer for Vance Script Cortex,
            a small editor where users author short JavaScript snippets
            that run in a sandboxed GraalJS engine. Your job: catch
            problems before the user clicks Execute.

            Look for:
            - infinite or unbounded loops (no termination, depends on
              never-changing variable, recursion without base case)
            - blocking I/O or sleep loops (busy-waits)
            - missing return / unreachable code
            - undefined identifiers (likely typos)
            - header-tag mistakes when the script starts with a JSDoc
              block (e.g. `@timeout 10s` written as `@timeout 10 s`)
            - obvious style issues that would surprise a reader

            DO NOT flag:
            - the use of `console.log` (it is the supported output API)
            - the use of host bindings `vance.*` (they are intentional)
            - missing `'use strict'`
            - cosmetic preferences (var vs let, function vs arrow)

            Reply with a single JSON object — no prose around it, no
            code fences. Schema:

            {
              "summary": "one-sentence overall verdict",
              "warnings": [
                {
                  "severity": "info|warn|error",
                  "category": "infinite-loop|blocking-io|missing-return|undefined|header|style",
                  "message": "human-readable",
                  "line": 0
                }
              ]
            }

            An empty warnings array is fine — that means the script
            looks OK.
            """;

    private final AiModelResolver aiModelResolver;
    private final SettingService settingService;
    private final AiModelService aiModelService;
    private final ObjectMapper objectMapper;

    public ScriptDeepValidateResponse review(
            String tenantId,
            @Nullable String projectId,
            String code,
            @Nullable String sourceName) {
        AiChatConfig config = ChatBehaviorBuilder.resolveOne(
                MODEL_ALIAS, tenantId, projectId, /*processId*/ null,
                settingService, aiModelResolver);
        AiChat chat = aiModelService.createChat(config);

        String user = "Review the following JavaScript. Source name: `"
                + (sourceName == null ? "draft.js" : sourceName) + "`.\n\n```js\n"
                + code + "\n```";

        String reply;
        try {
            reply = chat.ask(SYSTEM_PROMPT + "\n\n" + user);
        } catch (RuntimeException e) {
            log.warn("Deep-validate LLM call failed for tenant='{}' project='{}': {}",
                    tenantId, projectId, e.toString());
            return ScriptDeepValidateResponse.builder()
                    .reviewedAtMs(System.currentTimeMillis())
                    .summary("LLM call failed: " + e.getMessage())
                    .warnings(List.of(ScriptDeepWarning.builder()
                            .severity("warn")
                            .category("review-failed")
                            .message("Deep-validate could not reach the model — "
                                    + "Quick-Validate result still applies.")
                            .line(0)
                            .build()))
                    .build();
        }

        return parseReply(reply);
    }

    /**
     * Best-effort parse of the model's JSON reply. Wrong shape → a single
     * synthetic warning that surfaces the raw reply for diagnostics.
     * Doesn't try to be strict — Script Cortex is interactive, the user
     * can re-run the review if the model went off-script.
     */
    private ScriptDeepValidateResponse parseReply(String reply) {
        String trimmed = reply == null ? "" : reply.trim();
        // Strip code fences if the model ignored the "no fences" rule.
        if (trimmed.startsWith("```")) {
            int firstNl = trimmed.indexOf('\n');
            if (firstNl > 0) trimmed = trimmed.substring(firstNl + 1);
            if (trimmed.endsWith("```")) trimmed = trimmed.substring(0, trimmed.length() - 3);
        }
        // Some models prefix prose before the JSON — find the first '{'.
        int start = trimmed.indexOf('{');
        if (start > 0) trimmed = trimmed.substring(start);

        try {
            DeepReviewPayload payload = objectMapper.readValue(trimmed, DeepReviewPayload.class);
            List<ScriptDeepWarning> warnings = new ArrayList<>();
            if (payload.warnings != null) {
                for (RawWarning w : payload.warnings) {
                    if (w == null) continue;
                    warnings.add(ScriptDeepWarning.builder()
                            .severity(w.severity == null ? "warn" : w.severity)
                            .category(w.category == null ? "style" : w.category)
                            .message(w.message == null ? "" : w.message)
                            .line(w.line)
                            .build());
                }
            }
            return ScriptDeepValidateResponse.builder()
                    .reviewedAtMs(System.currentTimeMillis())
                    .summary(payload.summary)
                    .warnings(warnings)
                    .build();
        } catch (RuntimeException parseErr) {
            log.warn("Deep-validate reply was not parseable JSON: {}", parseErr.toString());
            return ScriptDeepValidateResponse.builder()
                    .reviewedAtMs(System.currentTimeMillis())
                    .summary("Model reply was not parseable JSON — showing raw text.")
                    .warnings(List.of(ScriptDeepWarning.builder()
                            .severity("info")
                            .category("raw")
                            .message(reply == null ? "(empty)" : reply.trim())
                            .line(0)
                            .build()))
                    .build();
        }
    }

    /** Wire shape — mirrors the prompt's JSON schema. Plain POJO,
     *  Jackson-friendly. Public so the test path can refer to it
     *  symbolically if ever needed. */
    public static class DeepReviewPayload {
        public @Nullable String summary;
        public @Nullable List<RawWarning> warnings;
    }

    public static class RawWarning {
        public @Nullable String severity;
        public @Nullable String category;
        public @Nullable String message;
        public int line;
    }
}
