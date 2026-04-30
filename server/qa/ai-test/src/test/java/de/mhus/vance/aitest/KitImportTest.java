package de.mhus.vance.aitest;

import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.client.model.Filters;
import de.mhus.vance.brain.VanceBrainApplication;
import de.mhus.vance.shared.crypto.AesEncryptionService;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * End-to-end test for the Kit subsystem (Phase 1: import only).
 *
 * <p>Imports the static fixture under {@code qa/kits/test-kit/} into
 * {@code acme/giant-slingshot} via the Admin REST API and verifies that
 * documents, settings (including a vault-encrypted PASSWORD), tools, and
 * the kit-manifest land in MongoDB with the expected shape.
 *
 * <p>Phase 2 (export round-trip) is deferred — see
 * {@code readme/kit-test.md} for the open points.
 *
 * <p>Setup:
 * <ul>
 *   <li>Copy {@code qa/kits/test-kit/} into a per-run scratch dir
 *       ({@code target/ai-test/test-kit-source/}) so we can rewrite
 *       {@code settings/api_token.yaml} with a fresh AES-GCM ciphertext
 *       (random IV → not stable across runs).</li>
 *   <li>Mint a JWT for {@code acme/wile.coyote} via
 *       {@link BrainAuthClient}.</li>
 *   <li>POST {@code /brain/acme/admin/kits/giant-slingshot/install} with
 *       a {@code file://} URL pointing at the scratch copy and the
 *       vault password.</li>
 * </ul>
 */
@SpringBootTest(
        classes = VanceBrainApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ActiveProfiles("aitest")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KitImportTest {

    private static final String TENANT = "acme";
    private static final String USERNAME = "wile.coyote";
    private static final String PASSWORD = "acme-rocket";
    private static final String PROJECT_ID = "giant-slingshot";

    /** Vault password used by the test only; must match what the test
     *  uses to encrypt {@code api_token.yaml}. */
    private static final String VAULT_PASSWORD = "test-kit-vault-secret";

    /** Plaintext value that the test puts behind the PASSWORD setting. */
    private static final String SECRET_PLAINTEXT = "test-kit-secret-value";

    private static final String BASE_URL = "http://localhost:18080";

    @Autowired
    private MongoTemplate mongo;

    private final ObjectMapper json = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @DynamicPropertySource
    static void mongoProperties(DynamicPropertyRegistry registry) {
        AbstractAiTest.wipeAiTestArtifacts();
        MongoFixture.start();
        registry.add("spring.mongodb.uri", MongoFixture::uri);
        registry.add("spring.mongodb.database", () -> MongoFixture.DATABASE);
    }

    @Test
    void installKitFromLocalDirectory() throws Exception {
        // 1. Build a per-run scratch copy of the static fixture so we can
        //    plug in a fresh vault-encrypted PASSWORD setting.
        Path fixtureDir = Path.of("..", "kits", "test-kit")
                .toAbsolutePath()
                .normalize();
        assertThat(Files.isDirectory(fixtureDir))
                .as("kit fixture should exist at %s — see readme/kit-test.md",
                        fixtureDir)
                .isTrue();

        Path scratchDir = Path.of("target", "ai-test", "test-kit-source")
                .toAbsolutePath()
                .normalize();
        copyTree(fixtureDir, scratchDir);

        // Overwrite settings/api_token.yaml with a freshly vault-encrypted
        // value. The fixture-on-disk file holds a placeholder so a careless
        // human import (without the test runner) fails clean instead of
        // pretending to work.
        String vaultCiphertext = AesEncryptionService.encryptWith(
                SECRET_PLAINTEXT, VAULT_PASSWORD);
        String tokenYaml = """
                type: PASSWORD
                value: "%s"
                description: "API token used by the test-kit; vault-encrypted on import."
                """.formatted(vaultCiphertext);
        Files.writeString(
                scratchDir.resolve("settings").resolve("api_token.yaml"),
                tokenYaml,
                StandardCharsets.UTF_8);

        // 2. Auth.
        BrainAuthClient auth = new BrainAuthClient(BASE_URL, TENANT, USERNAME, PASSWORD);
        auth.mint();

        // 3. Install.
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("projectId", PROJECT_ID);
        requestBody.put("source", Map.of("url", scratchDir.toUri().toString()));
        requestBody.put("mode", "INSTALL");
        requestBody.put("vaultPassword", VAULT_PASSWORD);
        // Explicit primitive booleans — DTO uses Lombok @Builder defaults
        // but Jackson rejects an absent/null field on a primitive type.
        requestBody.put("prune", false);
        requestBody.put("keepPasswords", false);

        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create(
                                BASE_URL + "/brain/" + TENANT + "/admin/kits/"
                                        + PROJECT_ID + "/install"))
                        .timeout(Duration.ofMinutes(2))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + auth.token())
                        .POST(HttpRequest.BodyPublishers.ofString(
                                json.writeValueAsString(requestBody)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode())
                .as("install endpoint should return 200 — body: %s", response.body())
                .isEqualTo(200);

        Map<String, Object> result = json.readValue(response.body(), Map.class);
        System.out.println("[KitImportTest] result: " + result);

        // 4. Response asserts.
        assertThat(result.get("kitName")).isEqualTo("test-kit");
        assertThat(asList(result.get("documentsAdded")))
                .as("documentsAdded should include intro.md")
                .anySatisfy(p -> assertThat((String) p).endsWith("intro.md"));
        assertThat(asList(result.get("settingsAdded")))
                .as("settingsAdded should include api_endpoint and api_token")
                .contains("api_endpoint", "api_token");
        assertThat(asList(result.get("toolsAdded")))
                .as("toolsAdded should include echo_tool")
                .contains("echo_tool");
        assertThat(asList(result.get("skippedPasswords")))
                .as("vault-encrypted password should decrypt cleanly — empty skippedPasswords")
                .isEmpty();

        // 5. MongoDB state.
        // Documents: at least the intro.md user-doc + the kit-manifest doc.
        long docsForProject = mongo.getCollection("documents").countDocuments(
                Filters.and(
                        Filters.eq("tenantId", TENANT),
                        Filters.eq("projectId", PROJECT_ID)));
        assertThat(docsForProject)
                .as("documents collection should hold at least intro.md + kit-manifest")
                .isGreaterThanOrEqualTo(2);

        Document intro = mongo.getCollection("documents").find(
                        Filters.and(
                                Filters.eq("tenantId", TENANT),
                                Filters.eq("projectId", PROJECT_ID),
                                Filters.eq("path", "intro.md")))
                .first();
        assertThat(intro)
                .as("intro.md should land at path 'intro.md' in giant-slingshot")
                .isNotNull();

        Document manifestDoc = mongo.getCollection("documents").find(
                        Filters.and(
                                Filters.eq("tenantId", TENANT),
                                Filters.eq("projectId", PROJECT_ID),
                                Filters.eq("path", "_vance/kit-manifest.yaml")))
                .first();
        assertThat(manifestDoc)
                .as("kit-manifest.yaml should land under _vance/ in the project")
                .isNotNull();

        // Settings: 2 entries with referenceType=project, referenceId=giant-slingshot.
        Document apiEndpoint = mongo.getCollection("settings").find(
                        Filters.and(
                                Filters.eq("tenantId", TENANT),
                                Filters.eq("referenceType", "project"),
                                Filters.eq("referenceId", PROJECT_ID),
                                Filters.eq("key", "api_endpoint")))
                .first();
        assertThat(apiEndpoint).as("api_endpoint setting should exist").isNotNull();
        assertThat(apiEndpoint.getString("type")).isEqualTo("STRING");
        assertThat(apiEndpoint.getString("value"))
                .isEqualTo("https://api.example.com/v1");

        Document apiToken = mongo.getCollection("settings").find(
                        Filters.and(
                                Filters.eq("tenantId", TENANT),
                                Filters.eq("referenceType", "project"),
                                Filters.eq("referenceId", PROJECT_ID),
                                Filters.eq("key", "api_token")))
                .first();
        assertThat(apiToken).as("api_token setting should exist").isNotNull();
        assertThat(apiToken.getString("type")).isEqualTo("PASSWORD");
        // The stored value must be a server-key-encrypted blob — i.e. NOT
        // identical to the vault-encrypted ciphertext we shipped in the
        // kit file, AND not the plaintext.
        String storedCiphertext = apiToken.getString("value");
        assertThat(storedCiphertext)
                .as("PASSWORD setting must be re-encrypted with the server key on import")
                .isNotNull()
                .isNotEqualTo(vaultCiphertext)
                .isNotEqualTo(SECRET_PLAINTEXT);

        // Tools: 1 entry with name=echo_tool.
        Document echoTool = mongo.getCollection("server_tools").find(
                        Filters.and(
                                Filters.eq("tenantId", TENANT),
                                Filters.eq("projectId", PROJECT_ID),
                                Filters.eq("name", "echo_tool")))
                .first();
        assertThat(echoTool).as("echo_tool should land in server_tools").isNotNull();
        assertThat(echoTool.getString("type")).isEqualTo("doc_lookup");

        // ──────────────────── Export round-trip ────────────────────
        // Folder-mode export: write the kit tree back to a fresh
        // directory under target/ai-test/ and verify on disk.
        Path exportDir = Path.of("target", "ai-test", "test-kit-export")
                .toAbsolutePath()
                .normalize();
        if (Files.exists(exportDir)) {
            try (Stream<Path> walk = Files.walk(exportDir)) {
                walk.sorted((a, b) -> b.compareTo(a))
                        .forEach(p -> {
                            try { Files.delete(p); } catch (IOException ignored) {}
                        });
            }
        }
        Map<String, Object> exportBody = new LinkedHashMap<>();
        exportBody.put("projectId", PROJECT_ID);
        exportBody.put("url", exportDir.toUri().toString());
        exportBody.put("vaultPassword", VAULT_PASSWORD);
        exportBody.put("commitMessage", "test export");

        HttpResponse<String> exportResponse = http.send(
                HttpRequest.newBuilder(URI.create(
                                BASE_URL + "/brain/" + TENANT + "/admin/kits/"
                                        + PROJECT_ID + "/export"))
                        .timeout(Duration.ofMinutes(2))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + auth.token())
                        .POST(HttpRequest.BodyPublishers.ofString(
                                json.writeValueAsString(exportBody)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(exportResponse.statusCode())
                .as("export endpoint should return 200 — body: %s", exportResponse.body())
                .isEqualTo(200);

        // Disk state asserts.
        assertThat(Files.isRegularFile(exportDir.resolve("kit.yaml")))
                .as("export should write kit.yaml at the root").isTrue();
        assertThat(Files.isRegularFile(exportDir.resolve("documents/intro.md")))
                .as("intro.md should round-trip into documents/").isTrue();
        assertThat(Files.readString(exportDir.resolve("documents/intro.md")))
                .as("intro.md body should round-trip with the marker")
                .contains("test-kit-intro-v1");
        assertThat(Files.isRegularFile(exportDir.resolve("settings/api_endpoint.yaml")))
                .as("api_endpoint setting file should be exported").isTrue();
        assertThat(Files.isRegularFile(exportDir.resolve("settings/api_token.yaml")))
                .as("api_token PASSWORD-setting file should be exported").isTrue();
        assertThat(Files.isRegularFile(exportDir.resolve("tools/echo_tool.tool.yaml")))
                .as("echo_tool tool file should be exported").isTrue();

        // The exported PASSWORD setting must be vault-encrypted (caller
        // can decrypt with the vault password); it must NOT be the
        // server-encrypted blob from Mongo, and not plaintext.
        String exportedToken = Files.readString(
                exportDir.resolve("settings/api_token.yaml"));
        assertThat(exportedToken)
                .as("export must wrap the token in a vault-encrypted blob, "
                        + "not leak the server-encrypted Mongo value")
                .doesNotContain(storedCiphertext)
                .doesNotContain(SECRET_PLAINTEXT);
        // Sanity: round-trip decrypts cleanly with the vault password.
        String exportedCiphertext = extractYamlValue(exportedToken, "value");
        String decrypted = AesEncryptionService.decryptWith(
                exportedCiphertext, VAULT_PASSWORD);
        assertThat(decrypted)
                .as("vault-decryption of the exported password should match the plaintext")
                .isEqualTo(SECRET_PLAINTEXT);
    }

    /** Tiny helper — pulls the {@code value:} string out of a settings YAML. */
    private static String extractYamlValue(String yaml, String key) {
        for (String line : yaml.split("\n")) {
            String t = line.trim();
            if (t.startsWith(key + ":")) {
                String v = t.substring(key.length() + 1).trim();
                if (v.startsWith("\"") && v.endsWith("\"") && v.length() >= 2) {
                    v = v.substring(1, v.length() - 1);
                }
                return v;
            }
        }
        throw new IllegalStateException("yaml has no '" + key + "' key:\n" + yaml);
    }

    // ──────────────────── helpers ────────────────────

    @SuppressWarnings("unchecked")
    private static java.util.List<Object> asList(Object o) {
        if (o instanceof java.util.List<?> l) {
            return (java.util.List<Object>) l;
        }
        return java.util.List.of();
    }

    private static void copyTree(Path source, Path target) throws IOException {
        if (Files.exists(target)) {
            try (Stream<Path> walk = Files.walk(target)) {
                walk.sorted((a, b) -> b.compareTo(a))
                        .forEach(p -> {
                            try {
                                Files.delete(p);
                            } catch (IOException ignored) {
                                // best-effort cleanup
                            }
                        });
            }
        }
        Files.createDirectories(target);
        try (Stream<Path> walk = Files.walk(source)) {
            walk.forEach(src -> {
                Path rel = source.relativize(src);
                Path dst = target.resolve(rel);
                try {
                    if (Files.isDirectory(src)) {
                        Files.createDirectories(dst);
                    } else {
                        Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    throw new RuntimeException(
                            "failed to copy kit fixture entry " + src, e);
                }
            });
        }
    }
}
