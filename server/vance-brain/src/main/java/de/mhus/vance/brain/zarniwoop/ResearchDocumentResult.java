package de.mhus.vance.brain.zarniwoop;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Outcome of {@link ResearchDocumentService#createDocument} — the pointer a
 * caller (LLM tool or trigger) gets back after a research pass has been
 * synthesized into a persisted document.
 *
 * <p>Deliberately a <em>handle</em>, not the body: {@code summary} is the
 * cheap in-context stand-in, {@code path} is what the caller re-reads (with
 * ranges / grep) when it needs detail, and the source URLs live on the
 * document as sticky-notes rather than being echoed here.
 */
public record ResearchDocumentResult(
        String docId,
        String projectId,
        String path,
        String title,
        @Nullable String summary,
        int sourceCount,
        List<String> tags,
        List<String> gaps) {}
