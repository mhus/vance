/**
 * Brain-side integration for the Anus shell. Bootstraps the {@code _vance}
 * tenant and the per-tenant {@code _vance-admin} service account used as
 * Anus's identity, mints short-lived admin JWTs from the tenant signing
 * key, and wraps the Brain HTTP API with a thin {@code AnusBrainClient}
 * that attaches a fresh token to every call.
 */
@org.jspecify.annotations.NullMarked
package de.mhus.vance.anus.brain;
