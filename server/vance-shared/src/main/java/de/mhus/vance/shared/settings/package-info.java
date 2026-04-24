/**
 * Typed settings keyed by {@code (tenantId, referenceType, referenceId, key)}.
 *
 * <p>{@code referenceType} names the owning scope kind (e.g. {@code "tenant"},
 * {@code "project"}, {@code "user"}, {@code "think-process"}) and
 * {@code referenceId} is the owner's {@code name} (not the Mongo id), per the
 * CLAUDE.md entity convention. Scope-cascade resolution (project falls back
 * to tenant, etc.) is not implemented here — callers compose that on top.
 *
 * <p>Values are stored as strings plus a {@link SettingType}. The
 * {@link SettingType#PASSWORD} type is encrypted at rest via
 * {@link de.mhus.vance.shared.crypto.AesEncryptionService} and never returned
 * in plaintext through generic read paths — use
 * {@link SettingService#getDecryptedPassword(String, String, String, String)}
 * explicitly.
 */
@NullMarked
package de.mhus.vance.shared.settings;

import org.jspecify.annotations.NullMarked;
