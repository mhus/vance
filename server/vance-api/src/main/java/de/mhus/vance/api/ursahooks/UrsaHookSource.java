package de.mhus.vance.api.ursahooks;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * Cascade tier a hook document was resolved from. Mirrors the
 * Project → {@code _vance} → Resource cascade used elsewhere; resource
 * layer is intentionally unused in v1 (no bundled hooks — see
 * {@code specification/ursahooks.md} §2).
 */
@GenerateTypeScript("ursahooks")
public enum UrsaHookSource {
    PROJECT,
    VANCE
}
