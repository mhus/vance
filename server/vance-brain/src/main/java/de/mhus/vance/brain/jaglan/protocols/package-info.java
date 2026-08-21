/**
 * Jaglan protocol implementations — one Spring bean per wire format.
 *
 * <p>{@code local} serves a directory on the brain's own machine. It exists
 * first because it makes the whole path testable without a foreign system,
 * and because pointing Vance at a folder of PDFs is a real use on its own.
 * The {@code ode} protocol — the contract any foreign software can implement
 * to expose files — follows.
 *
 * <p>{@code @NullMarked} package — references are non-null by default unless
 * annotated {@code @Nullable}. See CLAUDE.md "Null-Safety mit JSpecify".
 */
@NullMarked
package de.mhus.vance.brain.jaglan.protocols;

import org.jspecify.annotations.NullMarked;
