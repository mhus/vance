package de.mhus.vance.shared.project;

/**
 * Whether a project must be actively kept alive on a pod. See
 * {@code specification/cluster-project-management.md} §2 and
 * {@code planning/project-ownership-lease-design.md} §5.
 *
 * <p>{@link #HOMELESS} is hard-wired to the tenant project {@code _tenant}
 * ({@code ProjectService.isHomeless}) and immutable. Every other project —
 * the personal hubs {@code _user_<login>} included — starts as {@link #AUTO}.
 * For {@link ProjectKind#NORMAL} projects the other two are <b>operator
 * overrides</b>, switchable at runtime via {@code ProjectService.setLifecycleType};
 * SYSTEM projects keep the type they were created with.
 *
 * <p><b>Why a fourth value.</b> The old default was {@link #EPHEMERAL}, which
 * made "nobody chose" indistinguishable from "somebody chose never to
 * auto-start this". Since nothing in the tree ever wrote
 * {@link #PERMANENT} — {@code setLifecycleType} had no callers at all — every
 * project sat on a value that reads as an explicit opt-out, and both recovery
 * paths select on PERMANENT, so neither ever matched anything. {@link #AUTO}
 * gives "not decided" its own name and lets the derived
 * {@code ProjectDocument.ownerRequired} answer instead.
 */
public enum LifecycleType {

    /**
     * Never placed. The {@code homeNode} field stays {@code null} forever,
     * and nothing runs in the project: no sessions, no lane, no scheduler
     * runs of its own. Its documents are read straight from Mongo — the tenant
     * project is a storage place for the cascade. Set at create time for the
     * tenant project only.
     */
    HOMELESS,

    /**
     * Default for user projects: let {@code ProjectDocument.ownerRequired}
     * decide. A project holding scheduler entries, hooks, event triggers or a
     * kit-provisioning document is kept placed on a live pod; one holding only
     * documents waits for a {@code ProjectLocator} call.
     *
     * <p>The point of the default is that it cannot be forgotten — adding a
     * scheduler is the act that makes a project need an owner, so nobody has
     * to also remember a flag.
     */
    AUTO,

    /**
     * Operator override: never bring this online by itself, whatever it
     * contains. Only a {@code ProjectLocator} lookup with
     * {@code autoStart=true}, a session bind, or a direct client request
     * brings it up; after a pod death it lies dormant until something asks
     * again. Use for projects whose background work is deliberately paused.
     */
    EPHEMERAL,

    /**
     * Operator override: keep placed on a live pod even without any background
     * documents. Boot-Self-Pull greedily claims such projects up to
     * {@code resourcesStartupScore}; the Cluster-Master Distributor re-places
     * them when their lease expires.
     */
    PERMANENT
}
