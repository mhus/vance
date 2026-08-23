/**
 * The LLM surface of mounted documents — two tools, both {@code deferred}.
 *
 * <p>They exist because {@code _ext/} is invisible to the ordinary document
 * tools: {@code doc_find}, {@code doc_grep}, {@code memory_search} and
 * {@code doc_list_in_folder} scope to {@code documents/}, so an agent that
 * never learns the namespace exists answers "no such document" about a file
 * that is right there.
 *
 * <ul>
 *   <li>{@link de.mhus.vance.brain.tools.jaglan.MountListTool} — which sources
 *       are mounted, and what is inside one.</li>
 *   <li>{@link de.mhus.vance.brain.tools.jaglan.MountSearchTool} — asks the
 *       sources to search their own catalogues, and names the ones that could
 *       not be asked. An empty result from a mount that was never searched is
 *       not "not found".</li>
 * </ul>
 *
 * <p>Spec: {@code specification/public/jaglan-system.md} §8.
 *
 * <p>{@code @NullMarked} package — references are non-null by default unless
 * annotated {@code @Nullable}. See CLAUDE.md "Null-Safety mit JSpecify".
 */
@NullMarked
package de.mhus.vance.brain.tools.jaglan;

import org.jspecify.annotations.NullMarked;
