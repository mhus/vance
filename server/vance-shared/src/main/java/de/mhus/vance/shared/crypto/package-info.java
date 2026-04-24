/**
 * Symmetric encryption primitives.
 *
 * <p>Used wherever the server needs to store a value it must be able to read
 * back later (API keys in settings, OAuth tokens, ...). Distinct from
 * {@code password} (one-way hashing for login) and {@code keystore}
 * (asymmetric signing keys).
 *
 * <p>The master secret is pulled from configuration
 * ({@code vance.encryption.password}) and is expected to come from a managed
 * secret store in production.
 */
@NullMarked
package de.mhus.vance.shared.crypto;

import org.jspecify.annotations.NullMarked;
