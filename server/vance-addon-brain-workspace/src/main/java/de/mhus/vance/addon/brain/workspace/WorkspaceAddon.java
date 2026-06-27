package de.mhus.vance.addon.brain.workspace;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * Entry point of the Workspace Brain addon. Discovered via
 * {@code META-INF/spring/.../AutoConfiguration.imports}; component-scans
 * this package so {@link WorkspaceApplication}, the REST controller (if
 * added later) and the {@code workspace_*} server tools register
 * themselves into the Brain context.
 */
@AutoConfiguration
@ComponentScan(basePackageClasses = WorkspaceAddon.class)
public class WorkspaceAddon {
}
