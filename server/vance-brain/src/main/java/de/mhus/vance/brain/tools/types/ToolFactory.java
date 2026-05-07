package de.mhus.vance.brain.tools.types;

import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.shared.servertool.ServerToolDocument;
import java.util.Collection;
import java.util.Map;

/**
 * Expands a persisted {@link ServerToolDocument} into one or more
 * runnable {@link Tool}s. Each implementation is a Spring bean
 * discovered by {@code ToolFactoryRegistry}; its {@link #typeId()}
 * is the value stored in {@code ServerToolDocument#type}.
 *
 * <p>Two flavours of factory:
 * <ul>
 *   <li><b>Singleton</b> — {@link #create} returns exactly one tool
 *       whose {@link Tool#name()} matches {@link ServerToolDocument#getName()}.
 *       The historical 1:1 model. Example: {@code DocLookupToolFactory}
 *       (one document path → one lookup tool).</li>
 *   <li><b>Pack</b> — {@link #create} returns multiple tools, each named
 *       {@code <packName>__<localName>} where {@code packName} is
 *       {@code document.getName()}. Used for OpenAPI-spec-driven REST
 *       endpoints, MCP-server tool lists, plugin bundles, and any
 *       other source where one configuration produces N tools. See
 *       {@code planning/server-tool-providers.md}.
 * </ul>
 *
 * <p>Factories are stateless with respect to the document — the
 * document carries all per-instance configuration. The returned tools
 * may capture the document by value (singleton-style) or hold a live
 * connection / cached spec (pack-style).
 */
public interface ToolFactory {

    /** Reserved separator between pack name and sub-tool name. */
    String PACK_SEPARATOR = "__";

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
     * Build the runtime tool(s) for {@code document}. Singleton
     * factories return a one-element collection; pack factories return
     * the full sub-tool list with vollqualifizierten names.
     *
     * <p>The factory is free to validate the document and throw
     * {@link IllegalArgumentException} on bad configuration; the
     * cascade-lookup will surface the failure on read.
     */
    Collection<Tool> create(ServerToolDocument document);
}
