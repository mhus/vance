package de.mhus.vance.foot.tools.pack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.foot.auth.VancePaths;
import de.mhus.vance.foot.ui.ChatTerminal;
import de.mhus.vance.foot.ui.PendingLinePrompt;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The gate in front of project-layer packs. A project pack carries a
 * command line from the working directory, so it needs the user's
 * consent; a global pack is the user's own and never asks.
 */
class ProjectPackConsentTest {

    private Path home;
    private Path project;
    private VancePaths paths;
    private TrustedPacksStore trustedPacks;
    private PendingLinePrompt prompt;
    private ProjectPackConsent consent;

    @BeforeEach
    void setUp() throws IOException {
        home = Files.createTempDirectory("consent-home-");
        project = Files.createTempDirectory("consent-project-");
        paths = new VancePaths(project.toString(), home.toString());
        trustedPacks = new TrustedPacksStore();
        prompt = mock(PendingLinePrompt.class);
        consent = new ProjectPackConsent(paths, trustedPacks, prompt, mock(ChatTerminal.class));
    }

    @AfterEach
    void tearDown() throws IOException {
        for (Path root : List.of(home, project)) {
            if (root == null || !Files.exists(root)) continue;
            try (var stream = Files.walk(root)) {
                stream.sorted(Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(java.io.File::delete);
            }
        }
    }

    private LoadedPack pack(PackOrigin origin) {
        FootToolPackConfig config = new FootToolPackConfig(
                "chrome", "mcp_server", null, null, null, null, null, null,
                Map.of("transport", "stdio",
                        "command", List.of("npx", "-y", "chrome-devtools-mcp@latest")));
        return new LoadedPack(config, project.resolve("foot-tools/chrome.json"), origin);
    }

    private void answerWith(String answer) {
        when(prompt.canAsk()).thenReturn(true);
        when(prompt.ask(anyString(), anyBoolean(), anyLong())).thenReturn(answer);
    }

    @Test
    void globalPack_isNeverGated() {
        assertThat(consent.isAllowed(pack(PackOrigin.GLOBAL))).isTrue();
        verify(prompt, never()).ask(anyString(), anyBoolean(), anyLong());
    }

    @Test
    void projectPack_withoutInteractiveTerminal_isDenied() {
        // Daemon / --no-ui / dumb terminal: nobody can answer, and
        // loading foreign code unasked is the wrong default here.
        when(prompt.canAsk()).thenReturn(false);

        assertThat(consent.isAllowed(pack(PackOrigin.PROJECT))).isFalse();
        verify(prompt, never()).ask(anyString(), anyBoolean(), anyLong());
    }

    @Test
    void answerOnce_loadsWithoutRemembering() {
        answerWith("1");

        assertThat(consent.isAllowed(pack(PackOrigin.PROJECT))).isTrue();
        assertThat(trustedPacks.isTrusted(home, project, pack(PackOrigin.PROJECT))).isFalse();
    }

    @Test
    void answerAlways_loadsAndRemembers() {
        answerWith("2");

        assertThat(consent.isAllowed(pack(PackOrigin.PROJECT))).isTrue();
        assertThat(trustedPacks.isTrusted(home, project, pack(PackOrigin.PROJECT))).isTrue();
    }

    @Test
    void answerNo_denies() {
        answerWith("3");

        assertThat(consent.isAllowed(pack(PackOrigin.PROJECT))).isFalse();
    }

    @Test
    void timeout_denies() {
        // PendingLinePrompt returns null on timeout.
        answerWith(null);

        assertThat(consent.isAllowed(pack(PackOrigin.PROJECT))).isFalse();
    }

    @Test
    void garbledAnswer_denies() {
        answerWith("maybe");

        assertThat(consent.isAllowed(pack(PackOrigin.PROJECT))).isFalse();
    }

    @Test
    void alreadyTrustedPack_loadsWithoutAsking() {
        trustedPacks.trust(home, project, pack(PackOrigin.PROJECT));
        when(prompt.canAsk()).thenReturn(true);

        assertThat(consent.isAllowed(pack(PackOrigin.PROJECT))).isTrue();
        verify(prompt, never()).ask(anyString(), anyBoolean(), anyLong());
    }

    @Test
    void interactiveExpected_waitsForTheTerminalToAttach() {
        // Packs load on a background thread that starts before
        // ChatRepl.run() attaches the live region — the gate must not
        // lose that race and deny.
        when(prompt.canAsk()).thenReturn(false, false, true);
        when(prompt.ask(anyString(), anyBoolean(), anyLong())).thenReturn("1");
        consent.setInteractiveExpected(true);

        assertThat(consent.isAllowed(pack(PackOrigin.PROJECT))).isTrue();
        verify(prompt).ask(anyString(), anyBoolean(), anyLong());
    }
}
