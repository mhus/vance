/**
 * MongoDB-backed key store.
 *
 * Stores asymmetric key pairs (currently ECC secp256r1) grouped by
 * {@code tenantId + purpose}. Supports rotation: multiple enabled keys can coexist
 * for the same pair; {@link de.mhus.vance.shared.keystore.KeyService#getLatestPrivateKey(String, String)}
 * returns the newest, {@link de.mhus.vance.shared.keystore.KeyService#getPublicKeys(String, String)}
 * returns all enabled ones for verification.
 *
 * Repository and document are package-private; callers go through
 * {@link de.mhus.vance.shared.keystore.KeyService}.
 */
@NullMarked
package de.mhus.vance.shared.keystore;

import org.jspecify.annotations.NullMarked;
