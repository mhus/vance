package de.mhus.vance.shared.document;

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * One relation between two documents — parsed entry from a {@code kind: relations}
 * YAML file. The truth lives in the source file; this is the in-memory shape
 * that {@link DocumentRelationsService} produces on parse.
 *
 * <p>{@link #source} and {@link #target} are document paths inside the project
 * (e.g. {@code notes/thesis.md}). They are not normalised here — the source
 * file is the spelling the user wrote.
 *
 * <p>{@link #type} aligns with the relation enum from the knowledge-graph
 * spec ({@code relates_to}, {@code cites}, {@code extracted_from}, …).
 * Unknown values are passed through; consumers may choose to ignore them.
 *
 * <p>{@link #definedIn} captures which document carried the entry — handy
 * for the editor UI and the agent when it wants to point the user back at
 * the right YAML file. {@link #extras} keeps every non-canonical field from
 * the YAML record (confidence, evidence, custom metadata) so that callers
 * who care about a future field don't lose it on parse.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentRelation {

    public static final String DEFAULT_TYPE = "relates_to";

    /** Source-document path. */
    private String source = "";

    /** Relation kind ({@link #DEFAULT_TYPE} when unspecified). */
    private String type = DEFAULT_TYPE;

    /** Target-document path. */
    private String target = "";

    /** Optional free-text comment from the YAML record's {@code note} field. */
    private @Nullable String note;

    /**
     * Document that defined the relation — its {@code path} as stored on
     * {@link DocumentDocument}. Useful to round-trip back to the file where
     * the entry lives.
     */
    private @Nullable String definedIn;

    /**
     * Every non-canonical scalar field from the YAML record. Forward-compat
     * for {@code confidence}, {@code evidence}, {@code tags}, etc.
     */
    @Builder.Default
    private Map<String, Object> extras = new LinkedHashMap<>();
}
