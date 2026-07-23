package de.mhus.vance.brain.magrathea;

import java.util.UUID;

/**
 * Full 32-hex-char (128-bit) run identifiers. An earlier version used
 * only the first 8 hex chars (32 bit); at ~77k concurrent runs that has
 * a 50% birthday-collision chance, and because journal reads key on the
 * run id, a collision across two tenants merged their journals
 * (cross-scope data leak — code-review Phase 2 HIGH #5). A full UUID
 * makes collision astronomically unlikely; journal reads are
 * additionally tenant/project-scoped as defence in depth.
 */
public final class MagratheaRunIdGenerator {

    private MagratheaRunIdGenerator() {}

    /** Returns a fresh UUIDv4 as 32 hex chars (no dashes). */
    public static String fresh() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
