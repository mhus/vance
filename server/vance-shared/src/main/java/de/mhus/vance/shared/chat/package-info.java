/**
 * Persistence layer for chat messages.
 *
 * <p>Each message belongs to a think-process ({@code thinkProcessId}) inside
 * a session. Ordering is by {@code createdAt} ascending. The service is the
 * single access point; the repository is package-private per the data-
 * ownership rule.
 */
@NullMarked
package de.mhus.vance.shared.chat;

import org.jspecify.annotations.NullMarked;
