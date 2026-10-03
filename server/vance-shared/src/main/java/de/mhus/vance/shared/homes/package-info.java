/**
 * {@code @NullMarked} package — references are non-null by default unless
 * annotated {@code @Nullable}. See CLAUDE.md "Null-Safety mit JSpecify".
 *
 * <p>Per-scope {@code HOME} directories for agent-driven subprocesses. The
 * tree lives <b>outside</b> the workspace storage on purpose — see
 * {@code planning/home-isolation.md} for the design and the rejected
 * alternatives.
 */
@NullMarked
package de.mhus.vance.shared.homes;

import org.jspecify.annotations.NullMarked;
