package de.mhus.vance.brain.mcpserver;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * A family of tools the project-scoped MCP surface can offer — the unit an
 * {@code mcp-access.yaml} entry grants in its {@code capabilities:} list.
 *
 * <p>A capability is a <b>ceiling</b> like everything else in that file: it decides which fixed tool
 * list a caller may see on this door, never what the account may do. The tool list of each
 * capability stays an explicit list in {@link McpProjectToolCatalog} — a door to external agents
 * must not widen because somebody gave a new tool a label.
 */
public enum McpProjectCapability {

    /** Document tools ({@code doc_*}); confined by the entry's path prefixes. Always on the classpath. */
    DOC("doc", "document", true),

    /**
     * Workflow inspection and debugging ({@code workflow_*}). Project-wide by nature — a run has no
     * document path — so an entry that grants it must not carry path prefixes. Only present where
     * Magrathea runs ({@code vance.services.magrathea}); on a Brain without it the capability is
     * simply not offered.
     */
    WORKFLOW("workflow", "workflow", false),

    /**
     * Files in named workspace RootDirs of the project ({@code work_file_*}). Not documents: the
     * RootDirs are the Brain-side working trees agents build, edit and run things in. Confined to the
     * RootDirs the entry names in {@code dirs:}; asking for one outside that list, or for none, is
     * refused. Needs {@code Project EXECUTE} like every workspace tool on the normal path.
     */
    FILE("file", "work-file", true),

    /**
     * Running commands in those RootDirs ({@code work_exec_*}). The highest trust level of this door:
     * arbitrary shell code in the project's sandbox. See the entry rules in {@link
     * McpProjectAccessConfig.Entry}: {@code rw} only, a named caller, explicit {@code dirs:}.
     */
    EXEC("exec", "work-exec", true);

    private final String wire;
    private final String familyLabel;
    private final boolean alwaysPresent;

    McpProjectCapability(String wire, String familyLabel, boolean alwaysPresent) {
        this.wire = wire;
        this.familyLabel = familyLabel;
        this.alwaysPresent = alwaysPresent;
    }

    /**
     * The tool label of this family. The surface list stays explicit; a test holds the two in step
     * (every listed tool carries the label, and no labelled tool is left off a family that is listed
     * whole — see {@code McpProjectSurfaceLabelsTest}).
     */
    public String familyLabel() {
        return familyLabel;
    }

    /**
     * Whether using this family needs {@code Project EXECUTE} (what the tool dispatcher asks of every
     * call on the normal path). Workspace files and commands do; the document and workflow tools
     * enforce the finer {@code READ}/{@code WRITE} themselves.
     */
    public boolean requiresExecute() {
        return this == FILE || this == EXEC;
    }

    /** The name used in {@code capabilities:}. */
    public String wire() {
        return wire;
    }

    /** Whether its tools must exist at boot (a missing one then fails the boot). */
    boolean alwaysPresent() {
        return alwaysPresent;
    }

    /** Parses the YAML wire form, fail-closed: an unknown name is a load error, never ignored. */
    static McpProjectCapability fromWire(String raw) {
        String name = raw.trim().toLowerCase(Locale.ROOT);
        for (McpProjectCapability c : values()) {
            if (c.wire.equals(name)) return c;
        }
        throw new IllegalStateException("unknown capability '" + raw + "' — known: "
                + Arrays.stream(values()).map(McpProjectCapability::wire).collect(Collectors.joining(", ")));
    }
}
