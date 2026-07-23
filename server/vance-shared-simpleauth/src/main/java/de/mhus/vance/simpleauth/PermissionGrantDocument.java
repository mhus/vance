package de.mhus.vance.simpleauth;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One role grant: subject {@code (subjectType, subjectId)} holds {@code role}
 * on scope {@code (scopeType, scopeId)} within {@code tenantId}. Exactly one
 * grant per subject/scope (unique key) — a higher role replaces a lower one.
 * No deny grants; authorization is additive, max-role wins.
 */
@Document(collection = "permission_grants")
@CompoundIndexes({
    @CompoundIndex(name = "grant_key_idx",
        def = "{ 'tenantId':1, 'scopeType':1, 'scopeId':1, 'subjectType':1, 'subjectId':1 }",
        unique = true),
    @CompoundIndex(name = "grant_scope_idx",
        def = "{ 'tenantId':1, 'scopeType':1, 'scopeId':1 }"),
    @CompoundIndex(name = "grant_subject_idx",
        def = "{ 'tenantId':1, 'subjectType':1, 'subjectId':1 }")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PermissionGrantDocument {

    @Id
    private @Nullable String id;

    /** {@code TenantDocument.name}. */
    private String tenantId = "";

    private GrantScopeType scopeType = GrantScopeType.PROJECT;

    /** PROJECT: {@code ProjectDocument.name}. TENANT: equals {@code tenantId} (self-reference). */
    private String scopeId = "";

    private GrantSubjectType subjectType = GrantSubjectType.USER;

    /** {@code UserDocument.name} or {@code TeamDocument.name}. */
    private String subjectId = "";

    private GrantRole role = GrantRole.READER;

    /** Username of the grant's creator (audit only). */
    private @Nullable String createdBy;

    @CreatedDate
    private @Nullable Instant createdAt;
}
