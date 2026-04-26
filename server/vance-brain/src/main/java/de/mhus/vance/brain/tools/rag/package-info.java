/**
 * RAG tools — project-scoped retrieval over per-project named
 * indexes. All four read+write tools route through
 * {@link de.mhus.vance.brain.rag.RagService}; the workspace-file
 * tool composes with {@link
 * de.mhus.vance.brain.tools.workspace.WorkspaceService} to ingest
 * existing project files without requiring the LLM to read them
 * separately.
 *
 * <p>All tools are <em>secondary</em> by design — the LLM finds
 * them via {@code find_tools} when it needs them and they stay
 * out of the prompt otherwise.
 */
@NullMarked
package de.mhus.vance.brain.tools.rag;

import org.jspecify.annotations.NullMarked;
