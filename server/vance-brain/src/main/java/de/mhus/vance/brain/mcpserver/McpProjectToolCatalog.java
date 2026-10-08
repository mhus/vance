package de.mhus.vance.brain.mcpserver;

import de.mhus.vance.toolpack.Tool;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The fixed tool inventory of the project-scoped MCP surface
 * ({@code POST /brain/{tenant}/mcp/{project}}) — deliberately <b>not</b>
 * the configurable catalogue: an external agent gets a deterministic,
 * document-shaped surface, not a second door to the whole tool tree.
 * The inventory is picked from the built-in {@link Tool} beans by name
 * and fails the boot when one is missing — a renamed tool must stop the
 * server here, not silently shrink the external contract.
 *
 * <p>Two properties per entry drive the surface's regulation:
 * <ul>
 *   <li>{@code write} — only {@code rw} config entries see write
 *       tools; write tools also enforce per-document WRITE themselves
 *       (reserved {@code _vance/} paths need ADMIN, rule R4), so a
 *       {@code rw} config can never outgrow the caller's grants.</li>
 *   <li>{@code pathParams} — the argument names that carry a document
 *       path or path-prefix scope. The surface validates <em>every</em>
 *       one the call supplies against the config's path prefixes, not
 *       just the first — a tool with aliased params
 *       ({@code pathPrefix}/{@code folder}) picks its own winner, and
 *       the check must hold whichever that is. Every other argument
 *       passes through untouched.</li>
 * </ul>
 *
 * <p>Deliberately absent: {@code doc_delete}, {@code doc_move},
 * {@code doc_copy} and friends — destructive or structural operations
 * are not part of v1 of this surface. Id-style addressing ({@code id}
 * / {@code documentId} params) is rejected by the surface, not by the
 * catalogue, because {@code doc_read} by id checks only the tenant —
 * path addressing is what makes path-prefix confinement meaningful.
 */
@Component
public class McpProjectToolCatalog {

    /** One fixed surface entry. */
    public record CatalogEntry(Tool tool, boolean write, List<String> pathParams) {}

    /** Ordered surface definition: name → (writes?, path-carrying params). */
    private static final List<FixedTool> FIXED = List.of(
            new FixedTool("doc_read", false, List.of("path")),
            new FixedTool("doc_read_lines", false, List.of("path")),
            new FixedTool("doc_list_in_folder", false, List.of("pathPrefix", "folder")),
            new FixedTool("doc_list_folders", false, List.of("pathPrefix")),
            new FixedTool("doc_grep_path", false, List.of("pathPrefix")),
            new FixedTool("doc_write", true, List.of("path")),
            new FixedTool("doc_edit", true, List.of("path")),
            new FixedTool("doc_replace_lines", true, List.of("path")));

    /** Name lookup over the fixed surface, for the ambiguity check. */
    private static final Set<String> FIXED_NAMES =
            FIXED.stream().map(FixedTool::name).collect(java.util.stream.Collectors.toUnmodifiableSet());

    private record FixedTool(String name, boolean write, List<String> pathParams) {}

    private final Map<String, CatalogEntry> byName;

    /**
     * @param tools all {@link Tool} beans on the classpath — filtered
     *              down to the fixed surface; missing or duplicated
     *              surface names fail the boot
     */
    public McpProjectToolCatalog(List<Tool> tools) {
        Map<String, Integer> fixedNameCount = new LinkedHashMap<>();
        Map<String, Tool> available = new LinkedHashMap<>();
        for (Tool tool : tools) {
            // Ambiguity matters only where the surface depends on the
            // name; other bean-name clashes outside the fixed set are
            // none of this surface's business.
            if (FIXED_NAMES.contains(tool.name())) {
                fixedNameCount.merge(tool.name(), 1, Integer::sum);
            }
            available.putIfAbsent(tool.name(), tool);
        }
        Map<String, CatalogEntry> entries = new LinkedHashMap<>();
        for (FixedTool fixed : FIXED) {
            Integer count = fixedNameCount.get(fixed.name());
            if (count != null && count > 1) {
                throw new IllegalStateException("McpProjectToolCatalog: " + count + " built-in tools"
                        + " compete for the surface name '" + fixed.name()
                        + "' — the external MCP contract cannot be built");
            }
            Tool tool = available.get(fixed.name());
            if (tool == null) {
                throw new IllegalStateException("McpProjectToolCatalog: surface tool '" + fixed.name()
                        + "' is not on the classpath — the external MCP contract "
                        + "cannot be built");
            }
            entries.put(fixed.name(), new CatalogEntry(tool, fixed.write, fixed.pathParams));
        }
        this.byName = Map.copyOf(entries);
    }

    public Optional<CatalogEntry> find(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    /**
     * The catalogue a config entry may see: {@code ro} gets the read
     * tools, {@code rw} gets everything. Order is the fixed definition
     * order, so listings are stable across calls.
     */
    public List<CatalogEntry> forMode(McpProjectAccessConfig.Mode mode) {
        return FIXED.stream()
                .filter(fixed -> !fixed.write() || mode == McpProjectAccessConfig.Mode.RW)
                .map(fixed -> byName.get(fixed.name()))
                .toList();
    }
}
