/**
 * Bundled-documentation tools.
 *
 * <p>Exposes Markdown files under the {@code docs/} cascade
 * (project → {@code _vance} → {@code classpath:vance-defaults/docs/*.md})
 * to the LLM so it can look up "how does Vance work" answers on demand
 * instead of carrying everything in the prompt. {@code docs_list}
 * is primary so the model knows the resource exists;
 * {@code docs_read} is secondary, reached when the model has decided
 * which doc it wants.
 */
@NullMarked
package de.mhus.vance.brain.tools.docs;

import org.jspecify.annotations.NullMarked;
