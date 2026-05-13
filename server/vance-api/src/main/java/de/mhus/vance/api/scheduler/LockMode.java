package de.mhus.vance.api.scheduler;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * Per-scheduler gate that controls what the LLM-facing agent tools may
 * do with this entry. See {@code specification/scheduler.md} §10b.
 *
 * <p>REST and the Web-UI never apply this gate — it is exclusively a
 * defence layer for the agent tool surface. Admin operators always see
 * and edit every entry.
 */
@GenerateTypeScript("scheduler")
public enum LockMode {
    /** Default — agent has full CRUD access. */
    FULL,
    /** Agent sees the entry in list / get with a {@code locked} marker, but cannot mutate it. */
    PROTECTED,
    /** Agent does not see the entry at all; list filters it out, get answers like "not found", mutations are still denied. */
    HIDDEN
}
