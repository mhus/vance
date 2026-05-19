/**
 * Server-tool configuration types. Server tools live as YAML documents
 * under {@code server-tools/<name>.yaml} in the
 * {@link de.mhus.vance.shared.document.DocumentService} cascade
 * (project → {@code _vance} → {@code vance-defaults/} resource layer).
 *
 * <p>{@link de.mhus.vance.shared.servertool.ServerToolLoader} parses
 * those documents into {@link de.mhus.vance.shared.servertool.ServerToolConfig}.
 * The legacy {@link de.mhus.vance.shared.servertool.ServerToolDocument}
 * is the parameter shape consumed by {@code ToolFactory#create}
 * implementations — a plain DTO with no persistence behind it.
 */
@NullMarked
package de.mhus.vance.shared.servertool;

import org.jspecify.annotations.NullMarked;
