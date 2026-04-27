package de.mhus.vance.shared.recipe;

import de.mhus.vance.api.thinkprocess.PromptMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Persistent recipe override / addition. Tenant and project recipes
 * live in this collection; bundled defaults stay in the classpath
 * YAML and never see Mongo.
 *
 * <p>Cascade resolution prefers {@link RecipeScope#PROJECT} entries
 * over {@link RecipeScope#TENANT}; if neither matches, the bundled
 * registry is consulted. See {@code specification/recipes.md}.
 */
@Document(collection = "recipes")
@CompoundIndexes({
        @CompoundIndex(
                name = "tenant_scope_name_idx",
                def = "{ 'tenantId': 1, 'scope': 1, 'name': 1 }",
                unique = true),
        @CompoundIndex(
                name = "tenant_project_name_idx",
                def = "{ 'tenantId': 1, 'projectId': 1, 'name': 1 }")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecipeDocument {

    @Id
    private @Nullable String id;

    private String tenantId = "";

    private RecipeScope scope = RecipeScope.TENANT;

    /** Set only when {@link #scope} is {@link RecipeScope#PROJECT}. */
    private @Nullable String projectId;

    /** Unique within (tenantId, scope) — lowercase-kebab. */
    private String name = "";

    private String description = "";

    /** Engine-name from the registry, e.g. {@code "zaphod"}. */
    private String engine = "";

    /**
     * Default {@code engineParams} for processes spawned via this
     * recipe. Caller-supplied {@code params} override per-key,
     * unless {@link #locked} is {@code true}.
     */
    @Builder.Default
    private Map<String, Object> params = new LinkedHashMap<>();

    /** Optional system-prompt fragment carried into the spawned process. */
    private @Nullable String promptPrefix;

    @Builder.Default
    private PromptMode promptMode = PromptMode.APPEND;

    /** Tools added to the engine's allowed-set. */
    @Builder.Default
    private List<String> allowedToolsAdd = new ArrayList<>();

    /** Tools subtracted from the engine's allowed-set. */
    @Builder.Default
    private List<String> allowedToolsRemove = new ArrayList<>();

    /** When {@code true}, caller {@code params} are ignored on spawn. */
    private boolean locked;

    /** Free-form discovery hints — e.g. {@code [research, code]}. */
    @Builder.Default
    private List<String> tags = new ArrayList<>();

    @Version
    private @Nullable Long version;

    @CreatedDate
    private @Nullable Instant createdAt;

    @LastModifiedDate
    private @Nullable Instant updatedAt;
}
