package de.mhus.vance.brain.script;

import de.mhus.vance.brain.tools.workspace.TypedRootDirProvisioner;
import de.mhus.vance.shared.workspace.NodeHandler;
import de.mhus.vance.shared.workspace.NpmPackageSpec;
import de.mhus.vance.shared.workspace.RootDirHandle;
import de.mhus.vance.shared.workspace.WorkspaceService;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

/**
 * Makes a script's {@code @requires} packages available before it runs: provisions
 * the labelled Node RootDir when absent and installs every package that is not in
 * its {@code node_modules} yet. The library store does not survive a pod change
 * ({@code planning/multi-tenant-isolation.md} §4.6); this puts it back on first
 * use, also for runs without an agent (triggers, workflows).
 *
 * <p>Installs go through {@link NodeHandler#install}, so the same rules apply as
 * for {@code node_install}: registry specs only, fixed npm binary and registry,
 * size cap. A package that is present counts as satisfied — its version is not
 * compared against the range.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ScriptLibraryProvisioner {

    private final WorkspaceService workspaceService;
    private final NodeHandler nodeHandler;

    /**
     * Returns the RootDir path for {@code label} with all {@code requires}
     * installed. Serialised per RootDir, so two runs never drive npm in the same
     * folder at once.
     *
     * @throws de.mhus.vance.shared.workspace.WorkspaceException on an invalid spec,
     *     a failed install or an exceeded size cap
     * @throws ToolException when the scope cannot provision a RootDir
     */
    public Path ensure(ToolInvocationContext ctx, String label, Set<String> requires) {
        String projectId = ctx.projectId();
        if (StringUtils.isBlank(projectId)) {
            throw new ToolException("Script libraries need a project-scoped invocation context");
        }
        return workspaceService.withLabelLock(ctx.tenantId(), projectId, label, () -> {
            RootDirHandle handle =
                    TypedRootDirProvisioner.ensure(workspaceService, ctx, NodeHandler.TYPE, label, Map.of());
            for (String raw : requires) {
                NpmPackageSpec spec = NpmPackageSpec.parse(raw);
                if (!NodeHandler.isInstalled(handle.getPath(), spec.name())) {
                    log.info(
                            "Installing script library {} into {}/{} ({})",
                            spec.toArgument(),
                            ctx.tenantId(),
                            projectId,
                            handle.getDirName());
                    nodeHandler.install(handle, spec.toArgument());
                }
            }
            return handle.getPath().toAbsolutePath().normalize();
        });
    }
}
