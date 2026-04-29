package de.mhus.vance.brain.tools.types;

import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.shared.servertool.ServerToolDocument;
import java.util.Map;

/**
 * Expands a persisted {@link ServerToolDocument} into a runnable
 * {@link Tool}. Each implementation is a Spring bean discovered by
 * {@code ToolFactoryRegistry}; its {@link #typeId()} is the value
 * stored in {@code ServerToolDocument#type}.
 *
 * <p>Factories are stateless — the document carries all per-instance
 * configuration. The returned {@link Tool} captures the document by
 * value: later edits to the document do not affect already-built
 * tools (callers re-resolve through the cascade).
 */
public interface ToolFactory {

    /** Stable identifier — matches {@code ServerToolDocument#type}. */
    String typeId();

    /**
     * JSON-Schema for {@code ServerToolDocument#parameters}. Used by
     * admin UIs and by {@code ServerToolService.create(...)} to reject
     * malformed documents at write time. Object-shaped, like
     * {@link Tool#paramsSchema()}.
     */
    Map<String, Object> parametersSchema();

    /**
     * Build the runtime tool. The factory is free to validate the
     * document and throw {@link IllegalArgumentException} on bad
     * configuration; the cascade-lookup will surface the failure on
     * read.
     */
    Tool create(ServerToolDocument document);
}
