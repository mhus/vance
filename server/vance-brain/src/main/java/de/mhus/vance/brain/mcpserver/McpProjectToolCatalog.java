package de.mhus.vance.brain.mcpserver;

import de.mhus.vance.toolpack.Tool;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The fixed tool inventory of the project-scoped MCP surface
 * ({@code POST /brain/{tenant}/mcp/{project}}) — deliberately <b>not</b>
 * the configurable catalogue: an external agent gets a deterministic
 * surface of a few named families ({@link McpProjectCapability}: documents,
 * workflows), not a second door to the whole tool tree.
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
 * <p>{@code doc_move}, {@code doc_copy} and {@code doc_delete} are on
 * the surface because none of them destroys anything: {@code doc_delete}
 * is a soft delete into {@code _vance/trash/}. Both paths of a move or
 * copy ({@code path} and {@code newPath}) are path params, so neither
 * end can leave the configured prefixes — and since the trash lives
 * under the reserved {@code _vance/} area, which no write tool here may
 * touch, the trash itself is out of reach: no second delete, no move
 * out of it, no copy into it. Deliberately absent: {@code doc_purge},
 * {@code doc_restore} and {@code doc_list_trash} — emptying or working
 * the trash stays with the owner's surfaces. Id-style addressing ({@code id}
 * / {@code documentId} params) is rejected by the surface, not by the
 * catalogue, because {@code doc_read} by id checks only the tenant —
 * path addressing is what makes path-prefix confinement meaningful.
 */
@Component
@Slf4j
public class McpProjectToolCatalog {

    /**
     * One fixed surface entry.
     *
     * @param capability     the family an access entry must grant to see this tool
     * @param write          only {@code rw} entries see it
     * @param pathParams     arguments carrying a document path, checked against the entry's prefixes
     * @param rejectedParams arguments this surface refuses outright (see {@link FixedTool})
     * @param dirParam       the argument naming the workspace RootDir; <b>required</b> on the call and
     *                       checked against the entry's {@code dirs}. {@code null} for tools without one
     * @param jobParam       the argument naming an exec job, whose RootDir must be one of the entry's
     *                       {@code dirs}. {@code null} for tools without one
     */
    public record CatalogEntry(
            Tool tool,
            McpProjectCapability capability,
            boolean write,
            List<String> pathParams,
            List<String> rejectedParams,
            @Nullable String dirParam,
            @Nullable String jobParam) {}

    /**
     * Ordered surface definition: name → (family, writes?, path-carrying params, refused params,
     * RootDir param, job param).
     *
     * <p>The list is explicit on purpose. The tools also carry labels, but a label is a convenience for
     * recipes — here a new labelled tool would reach external agents without anybody deciding it should.
     * A test keeps the two in step instead.
     *
     * <p>{@code workflow_start} refuses {@code path}: a plan is started on this door <b>by name</b>
     * (from {@code _vance/workflows/}, where writing needs ADMIN). Starting an arbitrary project
     * document is a decision for a person at the run screen, and the document prefixes of an entry do
     * not apply to workflow tools.
     *
     * <p>{@code file} and {@code exec} are the {@code work_*} backends, not the {@code file_*}/{@code
     * exec_*} wrappers: the wrappers resolve a per-process work target, and this door has no process.
     * The backends take an explicit {@code dirName}, which is what makes a directory confinement
     * possible at all. Deliberately absent: {@code work_file_delete} (a hard delete — the document
     * door's delete is a soft one into the trash, this has no trash) and {@code
     * execute_work_javascript} (running code belongs to {@code exec} and its rules).
     */
    private static final List<FixedTool> FIXED = List.of(
            FixedTool.doc("doc_read", false, "path"),
            FixedTool.doc("doc_read_lines", false, "path"),
            FixedTool.doc("doc_list_in_folder", false, "pathPrefix", "folder"),
            FixedTool.doc("doc_list_folders", false, "pathPrefix"),
            FixedTool.doc("doc_grep_path", false, "pathPrefix"),
            FixedTool.doc("doc_write", true, "path"),
            FixedTool.doc("doc_edit", true, "path"),
            FixedTool.doc("doc_replace_lines", true, "path"),
            FixedTool.doc("doc_move", true, "path", "newPath"),
            FixedTool.doc("doc_copy", true, "path", "newPath"),
            FixedTool.doc("doc_delete", true, "path"),
            FixedTool.workflow("workflow_list", false),
            FixedTool.workflow("workflow_get", false),
            FixedTool.workflow("workflow_runs", false),
            FixedTool.workflow("workflow_run_get", false),
            FixedTool.workflow("workflow_start", true, "path"),
            FixedTool.workflow("workflow_control", true),
            FixedTool.workflow("workflow_breakpoints", true),
            FixedTool.workflow("workflow_simulate", true),
            FixedTool.workflow("workflow_restart", true),
            FixedTool.file("work_file_read", false),
            FixedTool.file("work_file_list", false),
            FixedTool.file("work_file_find", false),
            FixedTool.file("work_file_grep", false),
            FixedTool.file("work_file_head_tail", false),
            FixedTool.file("work_file_count", false),
            FixedTool.file("work_file_write", true),
            FixedTool.file("work_file_edit", true),
            FixedTool.execRun("work_exec_run"),
            FixedTool.execJob("work_exec_status", false),
            FixedTool.execJob("work_exec_tail", false),
            FixedTool.execJob("work_exec_stat", false),
            FixedTool.execJob("work_exec_check", true),
            FixedTool.execJob("work_exec_kill", true));

    /** Name lookup over the fixed surface, for the ambiguity check. */
    private static final Set<String> FIXED_NAMES =
            FIXED.stream().map(FixedTool::name).collect(java.util.stream.Collectors.toUnmodifiableSet());

    private record FixedTool(
            String name,
            McpProjectCapability capability,
            boolean write,
            List<String> pathParams,
            List<String> rejectedParams,
            @Nullable String dirParam,
            @Nullable String jobParam) {

        static FixedTool doc(String name, boolean write, String... pathParams) {
            return new FixedTool(name, McpProjectCapability.DOC, write, List.of(pathParams), List.of(), null, null);
        }

        static FixedTool workflow(String name, boolean write, String... rejectedParams) {
            return new FixedTool(
                    name, McpProjectCapability.WORKFLOW, write, List.of(), List.of(rejectedParams), null, null);
        }

        static FixedTool file(String name, boolean write) {
            return new FixedTool(name, McpProjectCapability.FILE, write, List.of(), List.of(), "dirName", null);
        }

        /** Starting a command: names the RootDir it runs in. */
        static FixedTool execRun(String name) {
            return new FixedTool(name, McpProjectCapability.EXEC, true, List.of(), List.of(), "dirName", null);
        }

        /** Following or ending a command by job id: the job's RootDir must be one of the entry's. */
        static FixedTool execJob(String name, boolean write) {
            return new FixedTool(name, McpProjectCapability.EXEC, write, List.of(), List.of(), null, "id");
        }
    }

    private final Map<String, CatalogEntry> byName;

    /** Surface tool names in definition order, so listings are stable across calls. */
    private final List<String> order;

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
        for (McpProjectCapability capability : McpProjectCapability.values()) {
            List<FixedTool> family =
                    FIXED.stream().filter(f -> f.capability() == capability).toList();
            long present =
                    family.stream().filter(f -> available.containsKey(f.name())).count();
            // A family that exists only where a feature is switched on (workflow: Magrathea) is simply
            // not offered where the feature is off. A family that is half there is a renamed tool, and
            // that must stop the server, not shrink the external contract.
            if (present == 0 && !capability.alwaysPresent()) {
                log.info(
                        "McpProjectToolCatalog: capability '{}' is not available on this Brain — not offered",
                        capability.wire());
                continue;
            }
            for (FixedTool fixed : family) {
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
                entries.put(
                        fixed.name(),
                        new CatalogEntry(
                                tool,
                                capability,
                                fixed.write(),
                                fixed.pathParams(),
                                fixed.rejectedParams(),
                                fixed.dirParam(),
                                fixed.jobParam()));
            }
        }
        this.byName = Map.copyOf(entries);
        this.order =
                FIXED.stream().map(FixedTool::name).filter(entries::containsKey).toList();
    }

    /** The tool names the surface lists for a capability, in definition order — whether or not they exist here. */
    public static List<String> surfaceToolNames(McpProjectCapability capability) {
        return FIXED.stream()
                .filter(f -> f.capability() == capability)
                .map(FixedTool::name)
                .toList();
    }

    public Optional<CatalogEntry> find(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    /**
     * The catalogue an access entry may see: the tools of the capabilities it grants, of those
     * {@code ro} gets the read tools and {@code rw} everything. An entry without capabilities sees
     * nothing. Order is the fixed definition order.
     */
    public List<CatalogEntry> visibleTo(McpProjectAccessConfig.Entry entry) {
        return order.stream()
                .map(byName::get)
                .filter(e -> entry.allows(e.capability()))
                .filter(e -> !e.write() || entry.mode() == McpProjectAccessConfig.Mode.RW)
                .toList();
    }
}
