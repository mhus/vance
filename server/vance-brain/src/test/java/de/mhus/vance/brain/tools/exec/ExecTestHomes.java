package de.mhus.vance.brain.tools.exec;

import static org.mockito.Mockito.mock;

import de.mhus.vance.shared.homes.HomesProperties;
import de.mhus.vance.shared.homes.HomesService;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.workspace.WorkspaceProperties;
import java.nio.file.Path;

/**
 * Test factory for the homes-backed environment builder: a real
 * {@link HomesService} rooted at a temp directory with the settings lookup
 * stubbed (no setting = project scope). Shared by the {@code ExecManager} test
 * suite so each test does not have to wire three collaborators for a HOME it
 * only needs to exist.
 */
final class ExecTestHomes {

    private ExecTestHomes() {}

    static HomesService homes(Path root) {
        HomesProperties properties = new HomesProperties();
        properties.setRoot(root.toString());
        SettingService settings = mock(SettingService.class);
        return new HomesService(properties, new WorkspaceProperties(), settings);
    }
}
