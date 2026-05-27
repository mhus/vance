package de.mhus.vance.shared.audit;

/**
 * Severity classification of an audit event. Kept deliberately small —
 * audit events are not log levels; the dimension we care about is
 * "how interesting is this for a reviewer".
 */
public enum AuditSeverity {
    /** Routine event — login, settings read, normal CRUD. */
    INFO,
    /** Suspicious or noteworthy — failed auth, permission denied, quota hit. */
    WARN,
    /** Security-relevant or compliance-relevant — privilege escalation, key access, mass delete. */
    CRITICAL
}
