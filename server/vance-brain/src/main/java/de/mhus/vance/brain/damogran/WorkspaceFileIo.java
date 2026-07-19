package de.mhus.vance.brain.damogran;

import de.mhus.vance.shared.workspace.WorkspaceService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * WORK {@link ComposeFileIo}: reads/writes bytes in the server-side managed
 * workspace (RootDir), confined via {@link WorkspaceService}. Faithful binary.
 */
final class WorkspaceFileIo implements ComposeFileIo {

    private final WorkspaceService workspaceService;
    private final String tenantId;
    private final String projectId;
    private final String dirName;

    WorkspaceFileIo(WorkspaceService workspaceService, String tenantId, String projectId, String dirName) {
        this.workspaceService = workspaceService;
        this.tenantId = tenantId;
        this.projectId = projectId;
        this.dirName = dirName;
    }

    @Override
    public boolean binaryCapable() {
        return true;
    }

    @Override
    public void writeBytes(String relativePath, byte[] bytes) {
        Path resolved = workspaceService.resolve(tenantId, projectId, dirName, relativePath);
        try {
            if (resolved.getParent() != null) {
                Files.createDirectories(resolved.getParent());
            }
            Files.write(resolved, bytes);
        } catch (IOException e) {
            throw new DamogranException("write failed for '" + relativePath + "': " + e.getMessage(), e);
        }
    }

    @Override
    public byte[] readBytes(String relativePath, long maxBytes) {
        return workspaceService.readBytes(tenantId, projectId, dirName, relativePath, maxBytes);
    }
}
