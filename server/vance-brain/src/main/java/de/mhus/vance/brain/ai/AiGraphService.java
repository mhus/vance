package de.mhus.vance.brain.ai;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Entry point for langgraph4j-based agent flows.
 *
 * <p>v1 is a deliberate placeholder: the {@code langgraph4j} dependency is
 * wired, the bean exists, but no concrete graph is shipped yet. Ford
 * operates as a single-turn chat and does not use graphs. Concrete graph
 * factories will land here when Arthur needs orchestration and DeepThink
 * gets its task-tree execution.
 *
 * <p>Kept intentionally thin so the first real graph user can shape the API
 * around its needs instead of an unfit pre-carved abstraction.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AiGraphService {

    private final AiModelService aiModelService;

    /** Exposed for graph factories that need to instantiate chats inside nodes. */
    public AiModelService getAiModelService() {
        return aiModelService;
    }
}
