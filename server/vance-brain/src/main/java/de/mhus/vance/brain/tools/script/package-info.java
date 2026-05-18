/**
 * LLM tools that wrap the unified
 * {@link de.mhus.vance.brain.action.ScriptActionExecutor} so models can
 * run JS scripts from the document cascade or a workspace RootDir
 * without re-implementing source loading. See
 * {@code planning/trigger-actions.md} §4.5 / §12.4.
 */
@NullMarked
package de.mhus.vance.brain.tools.script;

import org.jspecify.annotations.NullMarked;
