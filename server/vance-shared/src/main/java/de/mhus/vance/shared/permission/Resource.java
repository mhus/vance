package de.mhus.vance.shared.permission;

/**
 * The thing a permission check is about. Sealed so resolvers can pattern-match
 * exhaustively and so a misuse (passing a stringly-typed key) is impossible.
 *
 * <p>Resources carry the names of their parents (tenant, project, ...) so the
 * resolver does not have to look anything up to evaluate a check. The fields
 * are the same {@code name} values used as foreign keys elsewhere — never the
 * Mongo {@code id}.
 *
 * <p>Add new resource kinds here when a new entity type starts gating access.
 * Don't be afraid to add — the price is one new record.
 */
public sealed interface Resource {

    /** The whole tenant; check this for cross-tenant operations. */
    record Tenant(String tenantId) implements Resource {}

    /** A project inside a tenant. */
    record Project(String tenantId, String projectName) implements Resource {}

    /**
     * A document inside a project. {@code path} is the slash-separated logical
     * path including any leading {@code _vance/} segment for system docs.
     */
    record Document(String tenantId, String projectName, String path) implements Resource {}

    /**
     * A typed setting entry. {@code referenceType} mirrors
     * {@code SettingDocument.referenceType} (e.g. {@code "tenant"},
     * {@code "project"}, {@code "user"}). {@code key} is the dot-notation key.
     */
    record Setting(String tenantId, String referenceType, String referenceId, String key) implements Resource {}

    /** A session inside a project. */
    record Session(String tenantId, String projectName, String sessionName) implements Resource {}

    /** A think-process inside a session. */
    record ThinkProcess(String tenantId, String projectName, String sessionName, String processId) implements Resource {}

    /** A team inside a tenant. */
    record Team(String tenantId, String teamName) implements Resource {}

    /** A user inside a tenant. */
    record User(String tenantId, String username) implements Resource {}

    /**
     * An inbox item. {@code assignedToUserId} is the canonical assignee
     * (a username); future resolvers will compare it against the caller's
     * own user / team memberships to decide visibility.
     */
    record InboxItem(String tenantId, String itemId, String assignedToUserId) implements Resource {}
}
