package de.mhus.vance.brain.wowbagger.worker;

import de.mhus.vance.brain.ai.AiModelResolver;
import de.mhus.vance.brain.ai.light.LightLlmRequest;
import de.mhus.vance.brain.ai.light.LightLlmService;
import de.mhus.vance.brain.ai.light.LightLlmTextAnswer;
import de.mhus.vance.brain.recipe.ResolvedRecipe;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The original Wowbagger worker: one LightLlm call per chunk — the run's task
 * plus the chunk's records in, one output line per record out. Runs every
 * {@code internal: true} profile that is not claimed by a more specific type
 * (bundled default: {@code wowbagger-worker}).
 *
 * <p>The worker model is the profile's {@code params.model}, resolved through
 * the alias cascade exactly as the LightLlm call resolves it — that is the
 * model the pool's cost gate approves.
 */
@Component
public class LightLlmWorker implements WowbaggerWorker {

    public static final String KIND = "llm";

    /** Engines whose internal profiles belong to a more specific worker type. */
    private static final Set<String> CLAIMED_ENGINES = Set.of("zem", "hactar");

    private final LightLlmService lightLlmService;
    private final AiModelResolver aiModelResolver;

    public LightLlmWorker(LightLlmService lightLlmService, AiModelResolver aiModelResolver) {
        this.lightLlmService = lightLlmService;
        this.aiModelResolver = aiModelResolver;
    }

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public String describe() {
        return "a LightLlm profile — recipe with internal: true, e.g. wowbagger-worker";
    }

    @Override
    public boolean supports(ResolvedRecipe recipe) {
        return recipe.internal() && !CLAIMED_ENGINES.contains(recipe.engine());
    }

    @Override
    public PreparedWorker prepare(WorkerSetup setup) {
        ThinkProcessDocument process = setup.process();
        @Nullable String spec = AiModelResolver.parseModelSpec(setup.recipe().params());
        AiModelResolver.Resolved resolved =
                aiModelResolver.resolveOrDefault(spec, process.getTenantId(), process.getProjectId(), process.getId());
        return new Prepared(
                lightLlmService, process, setup.spec(), resolved.providerInstance() + ":" + resolved.modelName());
    }

    private record Prepared(
            LightLlmService service, ThinkProcessDocument process, WorkerSpec spec, String resolvedModel)
            implements PreparedWorker {

        @Override
        public String kind() {
            return KIND;
        }

        @Override
        public String recipeName() {
            return spec.recipeName();
        }

        @Override
        public WorkerReply process(List<String> records, ChunkContext chunk) {
            StringBuilder prompt = new StringBuilder("## Task\n")
                    .append(spec.task())
                    .append("\n\n## Records (")
                    .append(records.size())
                    .append(" total, one per line, in order)\n")
                    .append(String.join("\n", records));
            LightLlmTextAnswer answered = service.callWithUsage(LightLlmRequest.builder()
                    .recipeName(spec.recipeName())
                    .maxTokens(spec.maxTokens() != null && spec.maxTokens() > 0 ? spec.maxTokens() : null)
                    .userPrompt(prompt.toString())
                    .tenantId(process.getTenantId())
                    .projectId(process.getProjectId())
                    .processId(process.getId())
                    .build());
            List<String> lines = answered.text()
                    .lines()
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
            return new WorkerReply(lines, 1, tokens(answered, true), tokens(answered, false));
        }

        private static long tokens(LightLlmTextAnswer answered, boolean input) {
            if (answered.usage() == null) {
                return 0;
            }
            @Nullable
            Integer v =
                    input ? answered.usage().inputTokens() : answered.usage().outputTokens();
            return v == null ? 0 : v;
        }

        @Override
        public String toString() {
            return "LightLlmWorker[" + spec.recipeName() + " → " + resolvedModel + "]";
        }
    }
}
