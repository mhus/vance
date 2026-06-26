/**
 * Model-catalog discovery — scans tenant/project provider-credentials,
 * calls each backend's listing endpoint, and writes auto-managed
 * per-model YAML docs under {@code _vance/model-auto/**}.
 *
 * <p>The auto layer is intentionally separated from the manual layer
 * ({@code _vance/model/**}) by path, not by flag — overwriting auto
 * docs is always safe because operator edits live elsewhere and
 * survive untouched.
 */
@NullMarked
package de.mhus.vance.brain.ai.discovery;

import org.jspecify.annotations.NullMarked;
