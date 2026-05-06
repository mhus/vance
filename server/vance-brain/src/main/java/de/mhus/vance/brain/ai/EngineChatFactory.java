package de.mhus.vance.brain.ai;

import de.mhus.vance.api.progress.StatusTag;
import de.mhus.vance.brain.progress.ProgressEmitter;
import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * One-stop shop for engines spawning an {@link AiChat} for the current
 * process. Bundles three concerns that every engine duplicated before:
 *
 * <ol>
 *   <li>Recipe-driven {@link ChatBehavior} resolution via
 *       {@link ChatBehaviorBuilder#fromProcess}.</li>
 *   <li>Default {@code userNotifier} that surfaces resilience events
 *       (retry / chain-advance) to the user via the
 *       {@link de.mhus.vance.api.progress.StatusTag#PROVIDER} side-channel.</li>
 *   <li>Default {@code llmTraceWriter} that persists every round-trip
 *       to {@link de.mhus.vance.shared.llmtrace.LlmTraceService} when
 *       {@link ThinkEngineContext#traceLlm()} is enabled — only attached
 *       when tracing is on, so engines pay no overhead otherwise.</li>
 * </ol>
 *
 * <p>Engines call {@link #forProcess(ThinkProcessDocument, ThinkEngineContext, String)}
 * — one line replaces the boilerplate that used to sit at the top of
 * every engine's chat-spawn path. When an engine needs to override one
 * of the defaults (different notifier tag, custom trace path), it
 * passes a pre-built {@link AiChatOptions} through the four-arg overload;
 * fields the caller set are kept verbatim, only unset fields get the
 * defaults.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EngineChatFactory {

    private final AiModelResolver aiModelResolver;
    private final ProgressEmitter progressEmitter;

    /**
     * Build the {@link EngineChatBundle} the engine should drive its
     * turn against. Defaults the notifier and (conditionally) the
     * trace-writer.
     */
    public EngineChatBundle forProcess(
            ThinkProcessDocument process,
            ThinkEngineContext ctx,
            String engineName) {
        return forProcess(process, ctx, engineName, AiChatOptions.builder().build());
    }

    /**
     * Variant for callers that want to keep one of the default hooks
     * but customise something else (e.g. provide their own
     * {@code systemMessage} / {@code temperature}). Default lambdas are
     * only attached when the corresponding fields are still
     * {@code null} on {@code baseOptions}.
     */
    public EngineChatBundle forProcess(
            ThinkProcessDocument process,
            ThinkEngineContext ctx,
            String engineName,
            AiChatOptions baseOptions) {
        ChatBehavior behavior = ChatBehaviorBuilder.fromProcess(
                process, ctx.settingService(), aiModelResolver);
        AiChatOptions options = applyDefaults(baseOptions, process, ctx, engineName);
        AiChat chat = ctx.aiModelService().createChat(behavior, options);
        return new EngineChatBundle(chat, behavior);
    }

    /**
     * Pair of resolved chat and the underlying behavior chain — engines
     * need both because the {@code ModelCatalog} lookup (size tier,
     * context window) keys off the primary {@link AiChatConfig} that
     * lives inside {@link ChatBehavior}.
     */
    public record EngineChatBundle(AiChat chat, ChatBehavior behavior) {
        /** Convenience: the {@link AiChatConfig} of the primary entry. */
        public AiChatConfig primaryConfig() {
            return behavior.entries().get(0).config();
        }
    }

    private AiChatOptions applyDefaults(
            AiChatOptions base,
            ThinkProcessDocument process,
            ThinkEngineContext ctx,
            String engineName) {
        // Default user-notifier — only fires for resilience events
        // (retry, chain-advance). Caller's notifier always wins.
        if (base.getUserNotifier() == null) {
            base.setUserNotifier(msg -> progressEmitter.emitStatus(
                    process, StatusTag.PROVIDER, msg));
        }
        // Default trace-writer — only attached when persistence is on,
        // so the wrappers truly skip the path when tracing is off.
        if (base.getLlmTraceWriter() == null && ctx.traceLlm()) {
            base.setLlmTraceWriter((req, resp, ms) -> LlmTraceRecorder.record(
                    ctx.llmTraceService(), process, engineName, req, resp, ms));
        }
        // Recipe-level cache kill — `params.disableCache: true` on the
        // applied recipe lands on the spawned process's engineParams.
        // Honored on top of the global vance.ai.cache.enabled flag,
        // which the AnthropicProvider enforces on its own. This second
        // level lets a single recipe opt out without disabling caching
        // for the whole tenant.
        if (recipeDisablesCache(process)) {
            base.setCacheBoundary(CacheBoundary.NONE);
        }
        return base;
    }

    private static boolean recipeDisablesCache(ThinkProcessDocument process) {
        Map<String, Object> params = process.getEngineParams();
        if (params == null) {
            return false;
        }
        Object v = params.get("disableCache");
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof String s) {
            return "true".equalsIgnoreCase(s.trim());
        }
        return false;
    }
}
