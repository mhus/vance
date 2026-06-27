package de.mhus.vance.api.documents;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * Writer-classes used by the soft document-lock mechanism. A document's
 * {@code lockedFor} field carries a {@code Set<WriterRole>}; a write
 * arriving with a matching role is rejected with
 * {@code DocumentLockedException}.
 *
 * <p>The lock is intentionally soft — it gates against accidental
 * overwrites, not against malicious or privileged writers. Real
 * authorisation lives in the membership / role model and is unaffected.
 *
 * <p>Plausibility rule (enforced by service-side normalisation): when
 * {@link #USER} or {@link #KIT} is in the set, {@link #AI} is auto-added.
 * Reasoning: a caller that does not trust the human user with a write
 * trusts the LLM even less; a caller that freezes against kit-updates
 * does not want an engine spawn to slip a write through.
 */
@GenerateTypeScript("documents")
public enum WriterRole {
    /** LLM tools, engine-driven writes, script-API writes. */
    AI,
    /** Manual user write — Cortex save, Foot, REST {@code PUT /content}. */
    USER,
    /** {@code KitApplyService} content writes during install or update. */
    KIT
}
