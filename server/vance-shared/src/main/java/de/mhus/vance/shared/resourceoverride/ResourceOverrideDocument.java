package de.mhus.vance.shared.resourceoverride;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One version of a centrally managed resource area ({@code type}, e.g. {@code model}).
 * The valid version of a type is the highest {@code version} with {@code enabled}
 * set; a valid version with {@code overwrite=false} means "use the bundled
 * resources". Global — there is deliberately no tenant field.
 *
 * <p>A version is immutable except for {@link #enabled}. Written through
 * {@link ResourceOverrideService}.
 */
@Document(collection = "resource_override")
@CompoundIndex(name = "type_version_idx", def = "{ 'type': 1, 'version': 1 }", unique = true)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResourceOverrideDocument {

    @Id
    private @Nullable String id;

    /** Registered area name, see {@link ResourceOverrideSections}. */
    @Builder.Default
    private String type = "";

    /** Counts up per {@link #type}. */
    private long version;

    /** Only enabled versions take part in the choice of the valid one. */
    private boolean enabled;

    /** {@code false}: this version means "bundled resources"; {@link #files} is empty. */
    private boolean overwrite;

    @Builder.Default
    private List<ResourceOverrideFile> files = new ArrayList<>();

    /** Number of {@link #files}; kept so listings can skip loading them. */
    private int fileCount;

    /** SHA-256 over the sorted files, see {@link ResourceOverrideDigest}. */
    @Builder.Default
    private String digest = "";

    private @Nullable Instant createdAt;

    private @Nullable String createdBy;

    private @Nullable String note;
}
