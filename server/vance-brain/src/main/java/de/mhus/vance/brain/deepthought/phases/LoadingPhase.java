package de.mhus.vance.brain.deepthought.phases;

import de.mhus.vance.api.deepthought.DeepThoughtState;
import de.mhus.vance.api.deepthought.DeepThoughtStatus;
import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.LookupResult;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * LOADING — fetches the script content from
 * {@code state.scriptPath} via the document cascade
 * (project → {@code _vance} → bundled). The fetched body lands on
 * {@code state.generatedCode} and the engine transitions straight
 * to VALIDATING; FRAMING and DRAFTING are skipped.
 *
 * <p>Load-mode is the entry point Script Cortex uses for its
 * "validate this file" and "run this file" buttons — and the cheapest
 * way for a recipe to "run a known-good script with these args"
 * without burning LLM tokens on re-generation.
 *
 * <p>On miss / empty doc: transitions to FAILED with a clear reason.
 * No recovery — the user explicitly named the file.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LoadingPhase {

    private final DocumentService documentService;

    public DeepThoughtStatus execute(
            DeepThoughtState state,
            ThinkProcessDocument process,
            ThinkEngineContext ctx) {
        String path = state.getScriptPath();
        if (path == null || path.isBlank()) {
            state.setFailureReason(
                    "LOADING entered without a scriptPath — "
                            + "buildInitialState should have caught this");
            return DeepThoughtStatus.FAILED;
        }

        Optional<LookupResult> hit = documentService.lookupCascade(
                process.getTenantId(), process.getProjectId(), path);
        if (hit.isEmpty()) {
            state.setFailureReason("Script document not found: " + path);
            log.warn("DeepThought.runLoading id='{}' document not found at '{}'",
                    process.getId(), path);
            return DeepThoughtStatus.FAILED;
        }

        String content = hit.get().content();
        if (content == null || content.isBlank()) {
            state.setFailureReason("Script document is empty: " + path);
            log.warn("DeepThought.runLoading id='{}' document at '{}' is empty",
                    process.getId(), path);
            return DeepThoughtStatus.FAILED;
        }

        state.setGeneratedCode(content);
        log.info("DeepThought.runLoading id='{}' loaded {} chars from '{}' "
                        + "(source={})",
                process.getId(), content.length(), path,
                hit.get().source().name().toLowerCase());
        return DeepThoughtStatus.VALIDATING;
    }
}
