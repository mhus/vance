/**
 * Vance CLI — Picocli entry point and subcommands.
 *
 * Speaks to the Brain via the REST token-mint endpoint and the WebSocket wire
 * protocol defined in {@code vance-api}. Knows nothing about server-internal
 * types — it is a pure consumer of the API contract.
 */
@NullMarked
package de.mhus.vance.cli;

import org.jspecify.annotations.NullMarked;
