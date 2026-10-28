package de.mhus.vance.brain.audio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.tools.runner.RunnerWork;
import de.mhus.vance.runner.RunnerConfig;
import de.mhus.vance.runner.RunnerIdentity;
import de.mhus.vance.runner.RunnerMain;
import de.mhus.vance.runner.RunnerServer;
import de.mhus.vance.runner.RunnerWorkspace;
import de.mhus.vance.shared.jwt.JwtService;
import de.mhus.vance.shared.keystore.KeyPurpose;
import de.mhus.vance.shared.keystore.KeyService;
import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.shared.runner.RunnerBinding;
import de.mhus.vance.shared.runner.RunnerBindingService;
import de.mhus.vance.shared.runner.RunnerClient;
import de.mhus.vance.shared.tenant.TenantDocument;
import de.mhus.vance.shared.tenant.TenantService;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

/** The audio tools' runner engine against a real runner and a real ffmpeg, over HTTP and the age envelope. */
@EnabledIf("ffmpegAvailable")
class RunnerAudioEngineTest {

    private static final String TENANT = "acme";
    private static final int RATE = 24000;

    @TempDir
    Path tmp;

    private RunnerWorkspace workspace;
    private RunnerServer server;
    private AudioEngine engine;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair tenantKey = generator.generateKeyPair();
        KeyService keys = mock(KeyService.class);
        when(keys.getLatestPrivateKey(TENANT, KeyPurpose.JWT_SIGNING)).thenReturn(Optional.of(tenantKey.getPrivate()));
        RunnerIdentity identity = RunnerIdentity.loadOrCreate(tmp.resolve("runner.identity"));
        RunnerConfig config = new RunnerConfig(
                "runner-acme",
                TENANT,
                0,
                tmp.resolve("data"),
                tmp.resolve("runner.identity"),
                tmp.resolve("k.pem"),
                64L << 20);
        workspace = new RunnerWorkspace(tmp.resolve("data"), null);
        server = new RunnerServer(
                config,
                identity,
                List.of(tenantKey.getPublic()),
                RunnerMain.operations(config, workspace),
                Clock.systemUTC());
        server.start();

        TenantService tenants = mock(TenantService.class);
        RunnerBinding binding = RunnerBinding.builder()
                .runnerId("runner-acme")
                .url("http://localhost:" + server.port())
                .recipient(identity.recipient())
                .build();
        when(tenants.findByName(TENANT))
                .thenReturn(Optional.of(
                        TenantDocument.builder().name(TENANT).runner(binding).build()));
        RunnerBindingService bindings = new RunnerBindingService(tenants, mock(ProjectService.class));
        RunnerWork work = new RunnerWork(new RunnerClient(bindings, new JwtService(keys)), bindings);
        engine = new RunnerAudioEngine(work, TENANT, "p1");
    }

    @AfterEach
    void tearDown() {
        server.close();
        workspace.close();
    }

    @Test
    void everyOperationRunsOnTheRunnerAndComesBackAsBytes() {
        byte[] wav = sineWav(1.0);

        AudioEngine.ProbeData probe = engine.probe(wav);
        byte[] trimmed = engine.trim(wav, 0.25, 0.75, 0, 0, "wav");
        byte[] mixed = engine.mix(wav, sineWav(0.4), 1.0, 0.5, 0.1, false, 0, "wav");
        byte[] joined = engine.concat(List.of(sineWav(0.5), sineWav(0.5)), 0, "wav");
        byte[] converted = engine.convert(wav, 8000, 1, null, false, "wav");

        assertThat(probe.durationSeconds()).isCloseTo(1.0, within(0.05));
        assertThat(probe.format()).isEqualTo("wav");
        assertThat(probe.sampleRate()).isEqualTo(RATE);
        assertThat(engine.probe(trimmed).durationSeconds()).isCloseTo(0.5, within(0.05));
        assertThat(engine.probe(mixed).durationSeconds()).isCloseTo(1.0, within(0.05));
        assertThat(engine.probe(joined).durationSeconds()).isCloseTo(1.0, within(0.05));
        assertThat(engine.probe(converted).sampleRate()).isEqualTo(8000);
    }

    @Test
    void aFailureOnTheRunnerArrivesWithTheAudioToolsReason() {
        assertThatThrownBy(() -> engine.trim(sineWav(1.0), 0, null, 0, 0, "ogg"))
                .isInstanceOfSatisfying(
                        AudioManipulationException.class,
                        e -> assertThat(e.getReason()).isEqualTo(AudioManipulationException.Reason.FORMAT_UNSUPPORTED));
        assertThatThrownBy(() -> engine.concat(List.of(sineWav(1.0)), 0, "wav"))
                .isInstanceOfSatisfying(
                        AudioManipulationException.class,
                        e -> assertThat(e.getReason()).isEqualTo(AudioManipulationException.Reason.PARAMETER_INVALID));
        assertThatThrownBy(() -> engine.probe("not audio".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOfSatisfying(
                        AudioManipulationException.class,
                        e -> assertThat(e.getReason()).isEqualTo(AudioManipulationException.Reason.PROCESSING_ERROR));
    }

    @Test
    void aRunnerThatIsGoneIsAProcessingErrorTheCallerMayRetry() {
        server.close();

        assertThatThrownBy(() -> engine.probe(sineWav(0.2)))
                .isInstanceOfSatisfying(AudioManipulationException.class, e -> {
                    assertThat(e.getReason()).isEqualTo(AudioManipulationException.Reason.PROCESSING_ERROR);
                    assertThat(e.getMessage()).contains("not reachable");
                });
    }

    private static byte[] sineWav(double seconds) {
        int n = (int) Math.round(seconds * RATE);
        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        for (int i = 0; i < n; i++) {
            short v = (short) (Math.sin(2 * Math.PI * 440 * i / RATE) * 12000);
            pcm.write(v & 0xff);
            pcm.write((v >> 8) & 0xff);
        }
        return de.mhus.vance.brain.ai.audio.PcmWav.wrap(pcm.toByteArray(), RATE, 1);
    }

    static boolean ffmpegAvailable() {
        try {
            for (String program : new String[] {"ffmpeg", "ffprobe"}) {
                Process p = new ProcessBuilder(program, "-version")
                        .redirectErrorStream(true)
                        .start();
                p.getInputStream().readAllBytes();
                if (p.waitFor() != 0) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
