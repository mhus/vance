/**
 * Typed settings keyed by {@code (tenantId, referenceType, referenceId, key)}.
 *
 * <p>{@code referenceType} names the owning scope kind (e.g. {@code "tenant"},
 * {@code "project"}, {@code "user"}, {@code "think-process"}) and
 * {@code referenceId} is the owner's {@code name} (not the Mongo id), per the
 * CLAUDE.md entity convention. Scope-cascade resolution (project falls back
 * to tenant, etc.) is not implemented here — callers compose that on top.
 *
 * <p>Values are stored as strings plus a
 * {@link de.mhus.vance.api.settings.SettingType}. The
 * encrypted types ({@link de.mhus.vance.api.settings.SettingType#encrypted()} —
 * {@code PASSWORD} and {@code HIDDEN}) are encrypted at rest via
 * {@link de.mhus.vance.shared.crypto.AesEncryptionService} and never returned in
 * plaintext through generic read paths — use
 * {@link SettingService#getDecryptedPassword(String, String, String, String)}
 * explicitly.
 *
 * <p>{@code PASSWORD} vs. {@code HIDDEN} decides whether an authored
 * {@code {{secret:…}}} reference may resolve the value; that gate sits on the
 * reference-resolution path in {@code vance-brain}, not in this package.
 */
@NullMarked
package de.mhus.vance.shared.settings;

import org.jspecify.annotations.NullMarked;
