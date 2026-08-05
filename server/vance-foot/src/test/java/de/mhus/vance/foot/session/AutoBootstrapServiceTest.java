package de.mhus.vance.foot.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.thinkprocess.SessionBootstrapResponse;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.SessionResumeRequest;
import de.mhus.vance.api.ws.SessionResumeResponse;
import de.mhus.vance.foot.auth.SessionAnchorStore;
import de.mhus.vance.foot.auth.VancePaths;
import de.mhus.vance.foot.config.FootConfig;
import de.mhus.vance.foot.connection.BrainException;
import de.mhus.vance.foot.connection.BrainRestClientService;
import de.mhus.vance.foot.connection.ConnectionService;
import de.mhus.vance.foot.ui.ChatTerminal;
import java.nio.file.Path;
import java.time.Duration;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Covers {@code resolveClientName()} — the client name shown in the startup
 * announcement and persisted in the session anchor must be the explicit
 * {@code --name} when set, otherwise a generated random name, and crucially
 * the <em>same</em> value for both the announcement and the anchor (a separate
 * {@code nameGenerator.generate()} call would yield a different name).
 */
class AutoBootstrapServiceTest {

    private static final Pattern RANDOM_NAME = Pattern.compile("^[a-z]+-[a-z]+$");

    private final ConnectionService connection = mock(ConnectionService.class);
    private final SessionService sessions = mock(SessionService.class);
    private final ChatTerminal terminal = mock(ChatTerminal.class);
    private final VancePaths paths = mock(VancePaths.class);
    private final SessionAnchorStore anchorStore = mock(SessionAnchorStore.class);
    private final BrainRestClientService rest = mock(BrainRestClientService.class);
    private final RandomSessionNameGenerator nameGenerator = new RandomSessionNameGenerator();

    @BeforeEach
    void setUp() {
        when(paths.activeDir()).thenReturn(Path.of("/tmp/vance-test"));
    }

    private AutoBootstrapService service(FootConfig config) {
        return new AutoBootstrapService(
                config, connection, rest, sessions, terminal,
                paths, anchorStore, nameGenerator);
    }

    private FootConfig configWithBootstrap() {
        FootConfig config = new FootConfig();
        config.getBootstrap().setProjectId("p1");
        return config;
    }

    private void stubBootstrap(SessionBootstrapResponse response) throws Exception {
        when(connection.request(
                eq(MessageType.SESSION_BOOTSTRAP),
                any(),
                eq(SessionBootstrapResponse.class),
                any(Duration.class)))
                .thenReturn(response);
    }

    private void stubResume(SessionResumeResponse response) throws Exception {
        when(connection.request(
                eq(MessageType.SESSION_RESUME),
                any(SessionResumeRequest.class),
                eq(SessionResumeResponse.class),
                any(Duration.class)))
                .thenReturn(response);
    }

    // ── bootstrap (create) ──

    @Test
    void bootstrap_usesExplicitName_whenConfigured() throws Exception {
        stubBootstrap(SessionBootstrapResponse.builder()
                .sessionId("s1").projectId("p1").sessionCreated(true).build());
        FootConfig config = configWithBootstrap();
        config.getClient().setName("explicit-name");

        service(config).triggerNow();

        ArgumentCaptor<String> line = ArgumentCaptor.forClass(String.class);
        verify(terminal, timeout(2_000)).info(line.capture());
        assertThat(line.getValue()).contains("name=explicit-name");
        verify(anchorStore, timeout(2_000))
                .upsertSession(any(Path.class), eq("s1"), eq("p1"), eq("explicit-name"));
    }

    @Test
    void bootstrap_generatesRandomName_whenNotConfigured() throws Exception {
        stubBootstrap(SessionBootstrapResponse.builder()
                .sessionId("s1").projectId("p1").sessionCreated(true).build());
        // config.getClient().getName() stays null (default)

        service(configWithBootstrap()).triggerNow();

        ArgumentCaptor<String> anchorName = ArgumentCaptor.forClass(String.class);
        verify(anchorStore, timeout(2_000))
                .upsertSession(any(Path.class), eq("s1"), eq("p1"), anchorName.capture());
        assertThat(anchorName.getValue()).matches(RANDOM_NAME);

        ArgumentCaptor<String> line = ArgumentCaptor.forClass(String.class);
        verify(terminal, timeout(2_000)).info(line.capture());
        assertThat(line.getValue()).contains("name=" + anchorName.getValue());
    }

    @Test
    void bootstrap_usesSameNameForAnnouncementAndAnchor() throws Exception {
        // The whole point of resolveClientName(): one resolved value feeds both
        // the startup line and the anchor — two independent generate() calls
        // would yield different names.
        stubBootstrap(SessionBootstrapResponse.builder()
                .sessionId("s1").projectId("p1").sessionCreated(true).build());

        service(configWithBootstrap()).triggerNow();

        ArgumentCaptor<String> anchorName = ArgumentCaptor.forClass(String.class);
        verify(anchorStore, timeout(2_000))
                .upsertSession(any(Path.class), eq("s1"), eq("p1"), anchorName.capture());

        ArgumentCaptor<String> line = ArgumentCaptor.forClass(String.class);
        verify(terminal, timeout(2_000)).info(line.capture());
        // the "Bootstrap → session created: ..." line carries name=<anchorName>
        assertThat(line.getValue()).contains("name=" + anchorName.getValue());
    }

    // ── reconnect resume ──

    @Test
    void reconnectResume_usesExplicitName_whenConfigured() throws Exception {
        stubResume(SessionResumeResponse.builder()
                .sessionId("s1").projectId("p1").chatProcessName("chat").build());
        FootConfig config = new FootConfig();
        config.getClient().setName("explicit-name");

        service(config).triggerReconnectResume(
                new ConnectionService.ReconnectTarget("s1", "p1", null));

        ArgumentCaptor<String> line = ArgumentCaptor.forClass(String.class);
        verify(terminal, timeout(2_000)).info(line.capture());
        assertThat(line.getValue()).contains("name=explicit-name");
        verify(anchorStore, timeout(2_000))
                .upsertSession(any(Path.class), eq("s1"), eq("p1"), eq("explicit-name"));
    }

    @Test
    void reconnectResume_generatesRandomName_whenNotConfigured() throws Exception {
        stubResume(SessionResumeResponse.builder()
                .sessionId("s1").projectId("p1").chatProcessName("chat").build());

        service(new FootConfig()).triggerReconnectResume(
                new ConnectionService.ReconnectTarget("s1", "p1", null));

        ArgumentCaptor<String> anchorName = ArgumentCaptor.forClass(String.class);
        verify(anchorStore, timeout(2_000))
                .upsertSession(any(Path.class), eq("s1"), eq("p1"), anchorName.capture());
        assertThat(anchorName.getValue()).matches(RANDOM_NAME);

        ArgumentCaptor<String> line = ArgumentCaptor.forClass(String.class);
        verify(terminal, timeout(2_000)).info(line.capture());
        assertThat(line.getValue()).contains("name=" + anchorName.getValue());
    }
}
