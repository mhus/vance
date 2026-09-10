package de.mhus.vance.brain.tools.aimodels;

import de.mhus.vance.brain.ai.AiChatConfig;
import de.mhus.vance.brain.ai.AiModelResolver;
import de.mhus.vance.brain.ai.ChatBehavior;
import de.mhus.vance.brain.ai.ChatBehaviorBuilder;
import de.mhus.vance.brain.ai.ProviderType;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Reports which concrete provider and model the current chat process is
 * configured to run on — the same resolution the engine performs for every
 * run ({@link ChatBehaviorBuilder#fromProcess}), so "which model am I
 * talking to" stops being a trace-log question.
 *
 * <p>Answers the spec as written (e.g. {@code default:analyze}) and what
 * it resolves to (provider, provider instance, wire model name, base URL)
 * for the primary entry and every configured fallback. Reports the
 * <em>configuration</em>, not the transient state: the resilient layer may
 * switch to a fallback mid-run on a provider failure, and that switch is
 * not what this tool shows.
 *
 * <p>The API key is resolved as part of building the behavior (a missing
 * key is itself the diagnostic — the call fails with the exact setting
 * key), but its value never enters the result. No additional permission
 * gate: the dispatcher's EXECUTE check on the process resource already
 * scopes the call, and the tool reads nothing beyond its own process's
 * configuration.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AiModelCurrentTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(),
            "required", List.of());

    private final ThinkProcessService thinkProcessService;
    private final SettingService settingService;
    private final AiModelResolver resolver;

    @Override
    public String name() {
        return "ai_model_current";
    }

    @Override
    public String description() {
        return "Report which concrete provider and model this chat process "
                + "is configured to run on: the model spec as written "
                + "(e.g. 'default:analyze', from the recipe or process "
                + "override) and what it resolves to — provider, provider "
                + "instance, wire model name and base URL — for the primary "
                + "and every configured fallback. Shows the configuration; "
                + "a mid-run switch to a fallback after a provider failure "
                + "is not reflected here. Use to answer 'which model am I "
                + "on?' or to verify an alias/provider setup took effect.";
    }

    @Override
    public boolean primary() {
        return false;
    }

    @Override
    public Set<String> labels() {
        return Set.of("aimodels");
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        if (ctx.processId() == null || ctx.processId().isBlank()) {
            throw new ToolException("ai_model_current requires a process scope");
        }
        ThinkProcessDocument process = thinkProcessService
                .findById(ctx.processId())
                .orElseThrow(() -> new ToolException("Process '" + ctx.processId() + "' not found"));
        ChatBehavior behavior;
        try {
            // The exact call the engine makes before every run — same
            // resolver, same cascade, same pinned-scope handling.
            behavior = ChatBehaviorBuilder.fromProcess(process, settingService, resolver);
        } catch (RuntimeException e) {
            // A chat that cannot be built is the diagnosis itself (missing
            // alias, unknown provider, no API key) — surface the cause, it
            // already names the exact setting to fix.
            throw new ToolException("This process has no usable model configuration: " + e.getMessage(), e);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("processId", process.getId());
        out.put("recipe", process.getRecipeName());
        out.put("spec", ChatBehaviorBuilder.readModelSpec(process));
        List<String> fallbackSpecs = ChatBehaviorBuilder.readFallbackAliases(process);
        out.put("fallbackSpecs", fallbackSpecs);

        List<Map<String, Object>> entries = new ArrayList<>();
        for (ChatBehavior.Entry entry : behavior.entries()) {
            entries.add(describe(entry));
        }
        out.put(
                "primary",
                behavior.entries().isEmpty()
                        ? null
                        : describe(behavior.entries().get(0)));
        out.put("chain", entries);
        return out;
    }

    private static Map<String, Object> describe(ChatBehavior.Entry entry) {
        AiChatConfig config = entry.config();
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("label", entry.label());
        e.put("provider", config.provider());
        e.put("providerInstance", config.providerInstance());
        e.put("model", config.modelName());
        e.put("fullName", config.fullName());
        // null = the provider's built-in default endpoint; not a secret —
        // the llm setting forms show it as a plain string field too.
        e.put("baseUrl", config.baseUrl());
        ProviderType type = ProviderType.fromWireName(config.provider()).orElse(null);
        e.put("keyless", type != null && !type.requiresApiKey());
        return e;
    }
}
