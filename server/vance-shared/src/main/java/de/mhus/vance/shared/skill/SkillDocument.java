package de.mhus.vance.shared.skill;

import de.mhus.vance.api.skills.SkillScope;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
 * Persistent skill record. User, tenant, and project skills live in
 * this collection; bundled defaults stay in the classpath
 * {@code skills/<name>/SKILL.md} resources and never see Mongo.
 *
 * <p>Cascade resolution walks USER → PROJECT → TENANT → BUNDLED;
 * first-hit-wins. See {@code specification/skills.md}.
 *
 * <p>The persisted {@link #scope} value is always one of
 * {@link SkillScope#USER}, {@link SkillScope#PROJECT} or
 * {@link SkillScope#TENANT} — never {@link SkillScope#BUNDLED}.
 */
@Document(collection = "skills")
@CompoundIndexes({
        @CompoundIndex(
                name = "tenant_scope_name_idx",
                def = "{ 'tenantId': 1, 'scope': 1, 'name': 1 }",
                unique = true,
                partialFilter = "{ 'scope': 'TENANT' }"),
        @CompoundIndex(
                name = "tenant_project_name_idx",
                def = "{ 'tenantId': 1, 'projectId': 1, 'name': 1 }",
                unique = true,
                partialFilter = "{ 'scope': 'PROJECT' }"),
        @CompoundIndex(
                name = "tenant_user_name_idx",
                def = "{ 'tenantId': 1, 'userId': 1, 'name': 1 }",
                unique = true,
                partialFilter = "{ 'scope': 'USER' }")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillDocument {

    @Id
    private @Nullable String id;

    private String tenantId = "";

    private SkillScope scope = SkillScope.TENANT;

    /** Set only when {@link #scope} is {@link SkillScope#PROJECT}. */
    private @Nullable String projectId;

    /** Set only when {@link #scope} is {@link SkillScope#USER}. */
    private @Nullable String userId;

    /** Unique within (tenantId, scope, owner) — lowercase-kebab. */
    private String name = "";

    private String title = "";

    private String description = "";

    /** Manually-vended semver, e.g. {@code "1.0.0"}. */
    private String version = "1.0.0";

    @Builder.Default
    private List<SkillTriggerEmbedded> triggers = new ArrayList<>();

    /** Markdown appended to the system prompt at activation time. */
    private @Nullable String promptExtension;

    /**
     * Tool names the skill needs. Added to the engine/recipe whitelist
     * at turn time; never removed.
     */
    @Builder.Default
    private List<String> tools = new ArrayList<>();

    @Builder.Default
    private List<SkillReferenceDocEmbedded> referenceDocs = new ArrayList<>();

    @Builder.Default
    private List<String> tags = new ArrayList<>();

    private boolean enabled = true;

    @Version
    private @Nullable Long mongoVersion;

    @CreatedDate
    private @Nullable Instant createdAt;

    @LastModifiedDate
    private @Nullable Instant updatedAt;
}
