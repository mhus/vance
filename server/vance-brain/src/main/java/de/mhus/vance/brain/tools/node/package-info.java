/**
 * Node tooling for the brain — creates and manages workspace RootDirs
 * of type {@link de.mhus.vance.shared.workspace.NodeHandler#TYPE} (a
 * local {@code node_modules} plus optional git-persisted package.json
 * + lockfile), runs {@code npm install} / {@code npm uninstall}
 * synchronously through {@link de.mhus.vance.shared.workspace.NodeHandler}.
 *
 * <p>Sister to {@code python}. Same shape, same idempotency rules.
 * Workspaces created here power Hactar-generated scripts that
 * use {@code require()} — see {@code GraaljsScriptExecutor}'s
 * sandboxed CommonJS pathway, gated by {@code vance.script.require.enabled}.
 *
 * <p>Surface: {@code node_create}, {@code node_install},
 * {@code node_uninstall}.
 */
@NullMarked
package de.mhus.vance.brain.tools.node;

import org.jspecify.annotations.NullMarked;
