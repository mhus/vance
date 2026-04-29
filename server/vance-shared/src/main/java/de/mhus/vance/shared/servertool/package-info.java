/**
 * Server-tool persistence — configurable tool instances stored as
 * {@link de.mhus.vance.shared.servertool.ServerToolDocument}. Each
 * document binds a {@code type} (resolved by a {@code ToolFactory} bean
 * in {@code vance-brain}) to a parameter map plus per-instance flags
 * ({@code enabled}, {@code primary}, {@code labels}).
 *
 * <p>Lookup goes through the project cascade
 * {@code project → _vance → built-in beans} — the
 * {@code _vance} system project carries tenant-wide defaults, project
 * documents shadow them by {@code name}. Cascade resolution lives in
 * {@code vance-brain}'s {@code ServerToolService}; this module owns
 * only the document and repository.
 *
 * <p>Colocated: document + package-private repository.
 */
@NullMarked
package de.mhus.vance.shared.servertool;

import org.jspecify.annotations.NullMarked;
