/**
 * LLM tool surface for the AI-model catalog's automation side — the tool
 * twins of {@code /brain/{tenant}/admin/ai-models/*}. Deliberately small:
 * the catalog's <em>content</em> is edited as ordinary documents (see the
 * {@code ai-model} creator manual), only the tenant-wide automation runs
 * that the REST admin controller owns get an LLM-callable counterpart.
 */
@NullMarked
package de.mhus.vance.brain.tools.aimodels;

import org.jspecify.annotations.NullMarked;
