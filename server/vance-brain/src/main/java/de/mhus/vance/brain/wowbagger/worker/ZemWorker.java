package de.mhus.vance.brain.wowbagger.worker;

import de.mhus.vance.api.zem.ZemDecisionDto;
import de.mhus.vance.brain.recipe.ResolvedRecipe;
import de.mhus.vance.brain.zem.ZemDecideRequest;
import de.mhus.vance.brain.zem.ZemDecisionJson;
import de.mhus.vance.brain.zem.ZemException;
import de.mhus.vance.brain.zem.ZemPreparedCall;
import de.mhus.vance.brain.zem.ZemService;
import de.mhus.vance.brain.zem.ZemUsageAccumulator;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Decision worker: the worker recipe is a Zem profile ({@code engine: zem}),
 * every record is one decision. System-One providers have no batch API, so a
 * chunk is one {@code decide} per record — sequential inside the chunk; the
 * run's concurrency is the pool's thread count, capped per provider instance
 * by the Zem throttle.
 *
 * <p>Output line per record: the answers keyed by question name
 * ({@link ZemDecisionJson}, without distributions), or — with
 * {@code mergeIntoRecord} and JSONL input — the record itself with the answers
 * under {@code "_zem"}. The run's free-text {@code task} plays no part: the
 * profile's questions are the task.
 *
 * <p>Usage is collected in a {@link ZemUsageAccumulator} and booked at every
 * chunk commit — a handful of ledger writes per run instead of one per record.
 */
@Component
public class ZemWorker implements WowbaggerWorker {

    public static final String KIND = "zem";
    public static final String MERGE_KEY = "_zem";

    private final ZemService zemService;
    private final ObjectMapper objectMapper;

    public ZemWorker(ZemService zemService, ObjectMapper objectMapper) {
        this.zemService = zemService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public String describe() {
        return "a Zem decision profile — recipe with engine: zem";
    }

    @Override
    public boolean supports(ResolvedRecipe recipe) {
        return ZemService.ENGINE_NAME.equals(recipe.engine());
    }

    @Override
    public PreparedWorker prepare(WorkerSetup setup) {
        ThinkProcessDocument process = setup.process();
        ZemPreparedCall call;
        try {
            call = zemService.prepare(ZemDecideRequest.builder()
                    .tenantId(process.getTenantId())
                    .projectId(process.getProjectId())
                    .processId(process.getId())
                    .profile(setup.recipe().name())
                    .build());
        } catch (ZemException e) {
            throw new IllegalStateException(
                    "Zem worker profile '" + setup.recipe().name() + "' ["
                            + e.reason().wire() + "]: " + e.getMessage(),
                    e);
        }
        return new Prepared(zemService, objectMapper, call, zemService.openAccumulator(call), setup.spec());
    }

    private record Prepared(
            ZemService service,
            ObjectMapper objectMapper,
            ZemPreparedCall call,
            ZemUsageAccumulator usage,
            WorkerSpec spec)
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
        public String resolvedModel() {
            return call.config().providerInstance() + ":" + call.config().modelName();
        }

        @Override
        public WorkerReply process(List<String> records, ChunkContext chunk) {
            List<String> lines = new ArrayList<>(records.size());
            long tokens = 0;
            for (int i = 0; i < records.size(); i++) {
                Object state = state(records.get(i));
                ZemDecisionDto decision;
                try {
                    decision = service.decide(call, state, usage);
                } catch (ZemException e) {
                    throw new IllegalStateException(
                            "record " + (chunk.startRecord() + i) + ": ["
                                    + e.reason().wire() + "] " + e.getMessage(),
                            e);
                }
                if (decision.getInputTokens() != null) {
                    tokens += decision.getInputTokens();
                }
                lines.add(line(state, decision));
            }
            return new WorkerReply(lines, records.size(), tokens, 0);
        }

        @Override
        public boolean usesTask() {
            return false;
        }

        @Override
        public void onCommit() {
            service.flush(usage);
        }

        /** JSONL records are objects (the pool validated them), lines are text. */
        private Object state(String record) {
            return spec.inputJsonl() ? objectMapper.readValue(record, Map.class) : record;
        }

        private String line(Object state, ZemDecisionDto decision) {
            Map<String, Object> answers = ZemDecisionJson.answers(decision, false);
            if (spec.mergeIntoRecord() && state instanceof Map<?, ?> record) {
                Map<String, Object> merged = new LinkedHashMap<>();
                record.forEach((k, v) -> merged.put(String.valueOf(k), v));
                merged.put(MERGE_KEY, answers);
                return objectMapper.writeValueAsString(merged);
            }
            return objectMapper.writeValueAsString(answers);
        }

        @Override
        public String toString() {
            return "ZemWorker[" + spec.recipeName() + " → " + resolvedModel() + "]";
        }
    }
}
