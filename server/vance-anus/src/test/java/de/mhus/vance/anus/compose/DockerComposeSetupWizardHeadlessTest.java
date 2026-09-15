package de.mhus.vance.anus.compose;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * End-to-end tests of the headless compose wizard: config in (stdin or file),
 * four stack files out — or a masked dry-run plan and nothing written. These
 * run the real entry point against a temp directory, capturing stdout/stderr
 * the way an agent harness would see them.
 */
class DockerComposeSetupWizardHeadlessTest {

    @TempDir
    Path dir;

    private final InputStream originalIn = System.in;
    private final PrintStream originalOut = System.out;
    private final PrintStream originalErr = System.err;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @BeforeEach
    void captureStreams() {
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void restoreStreams() {
        System.setIn(originalIn);
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    private void feedStdin(String yamlText) {
        System.setIn(new ByteArrayInputStream(yamlText.getBytes(StandardCharsets.UTF_8)));
    }

    private static final String VALID_CONFIG = """
            face-port: 1234
            language-name: German
            language-code: de
            fook-enabled: false
            """;

    @Test
    void stdinConfig_writesFourFiles_andRoundsThroughTheEnv() throws IOException {
        feedStdin(VALID_CONFIG);

        int exit = DockerComposeSetupWizard.runHeadless(dir, "-", false);

        assertThat(exit).isZero();
        assertThat(out.toString()).contains("Setup complete — wrote 4 file(s)");
        assertThat(Files.exists(dir.resolve("docker-compose.yml"))).isTrue();
        assertThat(Files.exists(dir.resolve("Caddyfile"))).isTrue();
        assertThat(Files.exists(dir.resolve("README.md"))).isTrue();

        Map<String, String> env = DotEnvFile.read(dir.resolve(".env"));
        assertThat(env).containsEntry("VANCE_PORT", "1234");
        assertThat(env).containsEntry("VANCE_DEFAULT_LANGUAGE", "German");
        assertThat(env).containsEntry("VANCE_FOOK_ENABLED", "false");
        // Secrets the config did not supply were generated, not left blank.
        assertThat(env.get("VANCE_ENCRYPTION_PASSWORD")).isNotBlank();
        assertThat(env.get("MONGO_INITDB_ROOT_PASSWORD")).isNotBlank();
    }

    @Test
    void dryRun_writesNothing_andMasksSecrets() throws IOException {
        feedStdin(VALID_CONFIG);

        int exit = DockerComposeSetupWizard.runHeadless(dir, "-", true);

        assertThat(exit).isZero();
        assertThat(out.toString()).contains("Dry run — nothing written.");
        assertThat(Files.notExists(dir.resolve("docker-compose.yml")));
        assertThat(Files.notExists(dir.resolve(".env")));
        // The dry-run plan shows the key with a mask marker, never the value.
        assertThat(out.toString()).contains("VANCE_ENCRYPTION_PASSWORD=<set>");
    }

    @Test
    void realRun_neverPrintsASecretValue() {
        feedStdin("mongo-password: literal-secret-value\n");

        int exit = DockerComposeSetupWizard.runHeadless(dir, "-", false);

        assertThat(exit).isZero();
        String allOutput = out.toString() + err.toString();
        assertThat(allOutput).doesNotContain("literal-secret-value");
        // The value did land in the file — that is its only destination.
        try {
            assertThat(Files.readString(dir.resolve(".env"), StandardCharsets.UTF_8))
                    .contains("literal-secret-value");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void invalidConfig_exitsOneAndNamesTheProblems() {
        feedStdin("face-potr: 9999\nexternal-access: true\n");

        int exit = DockerComposeSetupWizard.runHeadless(dir, "-", false);

        assertThat(exit).isEqualTo(1);
        assertThat(err.toString()).contains("Config rejected — 2 problem(s)");
        assertThat(err.toString()).contains("face-potr: unknown setting");
        assertThat(err.toString()).contains("external-url: required when external-access is true");
        assertThat(Files.notExists(dir.resolve("docker-compose.yml")));
    }

    @Test
    void reRun_keepsUnmanagedKeysAndUnconfiguredValues() throws IOException {
        // A previous run left a full .env plus one hand-added key.
        feedStdin(VALID_CONFIG);
        DockerComposeSetupWizard.runHeadless(dir, "-", false);
        Path envPath = dir.resolve(".env");
        Map<String, String> first = DotEnvFile.read(envPath);
        Files.writeString(envPath, Files.readString(envPath) + "MY_CUSTOM_KEY=kept\n", StandardCharsets.UTF_8);

        // Re-run with a minimal config: only the port changes.
        feedStdin("face-port: 4321\n");
        int exit = DockerComposeSetupWizard.runHeadless(dir, "-", false);

        assertThat(exit).isZero();
        Map<String, String> second = DotEnvFile.read(envPath);
        assertThat(second).containsEntry("VANCE_PORT", "4321");
        assertThat(second).containsEntry("MY_CUSTOM_KEY", "kept");
        // Unconfigured values keep their pre-fill — no secret rotation, no
        // language reset.
        assertThat(second).containsEntry("VANCE_DEFAULT_LANGUAGE", "German");
        assertThat(second).containsEntry("VANCE_FOOK_ENABLED", "false");
        assertThat(second.get("VANCE_ENCRYPTION_PASSWORD")).isEqualTo(first.get("VANCE_ENCRYPTION_PASSWORD"));
    }

    @Test
    void configFile_worksLikeStdin() {
        Path config = dir.resolve("in").resolve("setup.yaml");
        try {
            Files.createDirectories(config.getParent());
            Files.writeString(config, VALID_CONFIG, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }

        int exit = DockerComposeSetupWizard.runHeadless(dir, config.toString(), false);

        assertThat(exit).isZero();
        assertThat(Files.exists(dir.resolve("docker-compose.yml"))).isTrue();
    }

    @Test
    void emptyConfig_isRejected() {
        feedStdin("");
        int exit = DockerComposeSetupWizard.runHeadless(dir, "-", false);
        assertThat(exit).isEqualTo(1);
        assertThat(err.toString()).contains("config is empty");
    }

    @Test
    void nonMappingConfig_isRejected() {
        feedStdin("- just\n- a\n- list\n");
        int exit = DockerComposeSetupWizard.runHeadless(dir, "-", false);
        assertThat(exit).isEqualTo(1);
        assertThat(err.toString()).contains("must be a YAML mapping");
    }

    @Test
    void snakeyamlSafeConstructor_noArbitraryTypes() {
        // The parser must refuse !!java tags — agent input stays data.
        String hostile = "!!javax.script.ScriptEngineManager [\"nonsense\"]\n";
        feedStdin(hostile);
        int exit = DockerComposeSetupWizard.runHeadless(dir, "-", false);
        assertThat(exit).isEqualTo(1);
        // And nothing was written either way.
        assertThat(Files.notExists(dir.resolve("docker-compose.yml")));
    }

    @Test
    void fullYamlRoundTrip_throughTheRealParser() {
        // Sanity: the YAML the spec documents parses into plain types.
        Object root = new Yaml(new SafeConstructor(new LoaderOptions()))
                .load("face-port: 9999\nfook-enabled: true\nexpose-mongo-port: false\n");
        assertThat(root).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) root;
        assertThat(map).containsEntry("face-port", 9999).containsEntry("fook-enabled", true);
    }
}
