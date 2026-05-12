/**
 * Python tooling for the brain — creates and manages workspace RootDirs
 * of type {@link de.mhus.vance.shared.workspace.PythonHandler#TYPE}
 * (local venv plus optional git-persisted sources), runs pip and python
 * inside them through {@link de.mhus.vance.brain.tools.exec.ExecManager}.
 *
 * <p>Surface: {@code python_create}, {@code python_install},
 * {@code python_uninstall}, {@code python_run},
 * {@code python_set_interpreter}.
 *
 * <p>Spec: {@code specification/workspace-management.md} §5.3.
 */
@NullMarked
package de.mhus.vance.brain.tools.python;

import org.jspecify.annotations.NullMarked;
