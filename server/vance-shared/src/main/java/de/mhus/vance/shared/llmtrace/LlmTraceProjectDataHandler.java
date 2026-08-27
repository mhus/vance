package de.mhus.vance.shared.llmtrace;

import de.mhus.vance.shared.project.maintenance.MappedProjectDataHandler;
import java.util.List;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/** Recorded LLM round-trips — prompts and completions made inside the project. */
@Component
public class LlmTraceProjectDataHandler extends MappedProjectDataHandler {

    public LlmTraceProjectDataHandler(MongoTemplate mongoTemplate) {
        super(mongoTemplate);
    }

    @Override
    public String id() {
        return "llm-traces";
    }

    @Override
    public int order() {
        return 1500;
    }

    @Override
    protected List<Class<?>> entityTypes() {
        return List.of(LlmTraceDocument.class);
    }
}
