package de.mhus.vance.addon.brain.workspace;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * Entry point of the Workspace + WorkPage Brain addon. Discovered via
 * {@code META-INF/spring/.../AutoConfiguration.imports}; component-scans
 * both packages so the workpage-related services / tools (under
 * {@code de.mhus.vance.addon.brain.workpage}) and the workspace-related
 * ones (under {@code de.mhus.vance.addon.brain.workspace}) register
 * themselves into the Brain context.
 *
 * <p>The two domains live in one addon because Workspace is a container
 * for WorkPages — splitting them was premature; consolidating them
 * here removes duplicated wiring (one pom, one assembly, one federation
 * remote, one entry in {@code /face/addons}).
 */
@AutoConfiguration
@ComponentScan(basePackages = {
        "de.mhus.vance.addon.brain.workspace",
        "de.mhus.vance.addon.brain.workpage",
})
public class WorkspaceAddon {
}
