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
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.bson.Document;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * End-to-end test for the Kit subsystem. Runs four ordered phases on the
 * same Spring context + Mongo container so each step builds on the
 * previous state. Uses two static fixture versions side-by-side
 * ({@code qa/kits/test-kit-v1/} and {@code qa/kits/test-kit-v2/}) so the
 * diff between import calls is readable in the repo, not buried in
 * runtime file mutations.
 *
 * <p>Fixtures:
 * <ul>
 *   <li>{@code parent-kit/} — base layer inherited by both child versions.
 *       Contributes {@code documents/parent.md} and
 *       {@code settings/parent_setting.yaml}.</li>
 *   <li>{@code test-kit-v1/} — has {@code documents/intro.md},
 *       {@code settings/api_endpoint.yaml},
 *       {@code settings/api_token.yaml} (PASSWORD), and
 *       {@code tools/echo_tool.tool.yaml}.</li>
 *   <li>{@code test-kit-v2/} — drops {@code intro.md}, adds
 *       {@code documents/welcome.md} and
 *       {@code settings/extra_setting.yaml}.</li>
 * </ul>
 *
 * <p>Phases:
 * <ol>
 *   <li>{@link #step1_install} — install v1 with the parent inherits.
 *       Verifies documents, settings, tool, manifest with per-layer
 *       ownership tracking ({@code inheritArtefacts}).</li>
 *   <li>{@link #step2_updateV2WithoutPrune} — re-import v2 without prune.
 *       {@code welcome.md} and {@code extra_setting} are added; the
 *       orphan {@code intro.md} stays in the project (no prune).</li>
 *   <li>{@link #step3_updateV2WithPrune} — re-install v1 (resets the
 *       manifest back to the v1 shape), then re-import v2 with
 *       {@code prune=true}. The orphan {@code intro.md} is now removed.
 *       (The implementation rewrites the manifest fully on every import,
 *       so prune always compares the new source against the
 *       most-recently-written manifest — re-installing v1 in between is
 *       what makes the prune step demonstrably non-trivial.)</li>
 *   <li>{@link #step4_export} — export the project's current kit (v2 +
 *       parent-merged) to a {@code file://} folder. Only top-layer
 *       artefacts are written; inherited contributions stay with their
 *       owning kit.</li>
 * </ol>
 *
 * <p>Setup writes scratch copies of all three fixtures into
 * {@code target/ai-test/}, rewrites the inherit URL placeholder in each
 * child kit, and rewrites the PASSWORD setting with a freshly
 * vault-encrypted ciphertext (random IV → not stable across runs).
 */
@SpringBootTest(
        classes = VanceBrainApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ActiveProfiles("aitest")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class KitImportTest {

    private static final String TENANT = "acme";
    private static final String USERNAME = "wile.coyote";
    private static final String PASSWORD = "acme-rocket";
    private static final String PROJECT_ID = "giant-slingshot";

    private static final String VAULT_PASSWORD = "test-kit-vault-secret";
    private static final String SECRET_PLAINTEXT = "test-kit-secret-value";

    private static final String BASE_URL = "http://localhost:18080";

    @Autowired
    private MongoTemplate mongo;

    private final ObjectMapper json = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private Path parentScratch;
    private Path v1Scratch;
    private Path v2Scratch;
    /** Vault-encrypted ciphertext written into both v1 and v2 PASSWORDs. */
    private String vaultCiphertext;
    private BrainAuthClient auth;

    @DynamicPropertySource
    static void mongoProperties(DynamicPropertyRegistry registry) {
        AbstractAiTest.wipeAiTestArtifacts();
        MongoFixture.start();
        registry.add("spring.mongodb.uri", MongoFixture::uri);
        registry.add("spring.mongodb.database", () -> MongoFixture.DATABASE);
    }

    @BeforeAll
    void setUp() throws Exception {
        Path fixtureRoot = Path.of("..", "kits").toAbsolutePath().normalize();
        Path parentFixture = fixtureRoot.resolve("parent-kit");
        Path v1Fixture = fixtureRoot.resolve("test-kit-v1");
        Path v2Fixture = fixtureRoot.resolve("test-kit-v2");
        for (Path p : List.of(parentFixture, v1Fixture, v2Fixture)) {
            assertThat(Files.isDirectory(p))
                    .as("fixture must exist at %s", p).isTrue();
        }

        Path scratchRoot = Path.of("target", "ai-test").toAbsolutePath().normalize();
        parentScratch = scratchRoot.resolve("parent-kit-source");
        v1Scratch = scratchRoot.resolve("test-kit-v1-source");
        v2Scratch = scratchRoot.resolve("test-kit-v2-source");
        copyTree(parentFixture, parentScratch);
        copyTree(v1Fixture, v1Scratch);
        copyTree(v2Fixture, v2Scratch);

        // Wire each child kit's inherits URL to the scratch parent path,
        // and fill its PASSWORD setting with a fresh vault ciphertext.
        vaultCiphertext = AesEncryptionService.encryptWith(SECRET_PLAINTEXT, VAULT_PASSWORD);
        for (Path child : List.of(v1Scratch, v2Scratch)) {
            Path kitYaml = child.resolve("kit.yaml");
            Files.writeString(
                    kitYaml,
                    Files.readString(kitYaml).replace(
                            "INHERIT_URL_PLACEHOLDER",
                            parentScratch.toUri().toString()),
                    StandardCharsets.UTF_8);
            Files.writeString(
                    child.resolve("settings").resolve("api_token.yaml"),
                    """
                    type: PASSWORD
                    value: "%s"
                    description: "API token used by the test-kit; vault-encrypted on import."
                    """.formatted(vaultCiphertext),
                    StandardCharsets.UTF_8);
        }

        auth = new BrainAuthClient(BASE_URL, TENANT, USERNAME, PASSWORD);
        auth.mint();
    }

    // ──────────────────────────────────────────────────────────────────
    //  Step 1 — install v1 (which inherits parent-kit)
    // ──────────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    void step1_install() throws Exception {
        Map<String, Object> result = postKit("install", baseRequest(v1Scratch, "INSTALL", false));

        assertThat(result.get("kitName")).isEqualTo("test-kit");
        assertThat(asList(result.get("documentsAdded")))
                .as("v1 install adds intro.md (child) and parent.md (inherited)")
                .anyMatch(p -> ((String) p).endsWith("intro.md"))
                .anyMatch(p -> ((String) p).endsWith("parent.md"));
        assertThat(asList(result.get("settingsAdded")))
                .contains("api_endpoint", "api_token", "parent_setting");
        assertThat(asList(result.get("toolsAdded"))).contains("echo_tool");
        assertThat(asList(result.get("skippedPasswords"))).isEmpty();
        assertThat(asList(result.get("inheritedKits")))
                .anyMatch(s -> ((String) s).contains("parent-kit"));

        // Mongo asserts.
        assertThat(documentAt(PROJECT_ID, "intro.md")).isNotNull();
        assertThat(documentAt(PROJECT_ID, "parent.md")).isNotNull();
        assertThat(documentAt(PROJECT_ID, "_vance/kit-manifest.yaml")).isNotNull();

        Document apiToken = settingAt(PROJECT_ID, "api_token");
        assertThat(apiToken).isNotNull();
        assertThat(apiToken.getString("type")).isEqualTo("PASSWORD");
        assertThat(apiToken.getString("value"))
                .isNotEqualTo(vaultCiphertext)
                .isNotEqualTo(SECRET_PLAINTEXT);

        // Manifest YAML — per-layer ownership tracking.
        Map<String, Object> manifest = readManifestYaml();
        @SuppressWarnings("unchecked")
        Map<String, Object> kitMeta = (Map<String, Object>) manifest.get("kit");
        assertThat(kitMeta.get("name")).isEqualTo("test-kit");
        assertThat(asStringList(manifest.get("documents")))
                .as("top-layer documents = v1's own only").containsExactlyInAnyOrder("intro.md");
        assertThat(asStringList(manifest.get("settings")))
                .as("top-layer settings = v1's own only")
                .containsExactlyInAnyOrder("api_endpoint", "api_token");
        assertThat(asStringList(manifest.get("tools")))
                .containsExactlyInAnyOrder("echo_tool");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> inheritArtefacts =
                (List<Map<String, Object>>) manifest.get("inheritArtefacts");
        assertThat(inheritArtefacts).hasSize(1);
        assertThat(inheritArtefacts.get(0).get("name")).isEqualTo("parent-kit");
        assertThat(asStringList(inheritArtefacts.get(0).get("documents")))
                .containsExactlyInAnyOrder("parent.md");
        assertThat(asStringList(inheritArtefacts.get(0).get("settings")))
                .containsExactlyInAnyOrder("parent_setting");
    }

    // ──────────────────────────────────────────────────────────────────
    //  Step 2 — UPDATE to v2 WITHOUT prune
    //   v1 docs:     intro.md
    //   v2 docs:     welcome.md          (intro.md REMOVED at source)
    //   v1 settings: api_endpoint, api_token
    //   v2 settings: api_endpoint, api_token, extra_setting   (extra is NEW)
    //  Without prune, intro.md stays in Mongo (orphan from v1's manifest)
    //  while welcome.md + extra_setting are added.
    // ──────────────────────────────────────────────────────────────────

    @Test
    @Order(2)
    void step2_updateV2WithoutPrune() throws Exception {
        Map<String, Object> result = postKit("update", baseRequest(v2Scratch, "UPDATE", false));

        assertThat(asList(result.get("documentsAdded")))
                .as("welcome.md is new in v2 → documentsAdded")
                .anyMatch(p -> ((String) p).endsWith("welcome.md"));
        assertThat(asList(result.get("settingsAdded")))
                .as("extra_setting is new in v2 → settingsAdded")
                .contains("extra_setting");
        assertThat(asList(result.get("documentsRemoved")))
                .as("no prune → intro.md not removed yet").isEmpty();

        // Mongo: intro.md still present (no prune), welcome.md + extra_setting added.
        assertThat(documentAt(PROJECT_ID, "intro.md"))
                .as("intro.md should remain — orphan tolerated without prune")
                .isNotNull();
        assertThat(documentAt(PROJECT_ID, "welcome.md")).isNotNull();
        assertThat(settingAt(PROJECT_ID, "extra_setting")).isNotNull();
        // Parent still intact.
        assertThat(documentAt(PROJECT_ID, "parent.md")).isNotNull();
        assertThat(settingAt(PROJECT_ID, "parent_setting")).isNotNull();
    }

    // ──────────────────────────────────────────────────────────────────
    //  Step 3 — UPDATE back to v1, then UPDATE to v2 WITH prune
    //   The no-prune step above rewrote the manifest as v2-only, so a
    //   prune-update of v2 over that manifest would be a no-op. We
    //   UPDATE-back to v1 (also no prune) first to push the manifest
    //   to {documents: [intro.md], settings: [api_endpoint, api_token]};
    //   the prune-update of v2 then has a real diff to act on and
    //   removes intro.md (in old manifest, not in new source).
    // ──────────────────────────────────────────────────────────────────

    @Test
    @Order(3)
    void step3_updateV2WithPrune() throws Exception {
        // Reset the manifest to the v1 shape so the next prune-update
        // sees intro.md as a "tracked but missing" orphan.
        postKit("update", baseRequest(v1Scratch, "UPDATE", false));
        assertThat(documentAt(PROJECT_ID, "intro.md"))
                .as("intro.md restored after UPDATE-back to v1").isNotNull();

        Map<String, Object> result = postKit("update", baseRequest(v2Scratch, "UPDATE", true));

        assertThat(asList(result.get("documentsRemoved")))
                .as("prune=true → intro.md (orphan from v1's manifest) removed")
                .anyMatch(p -> ((String) p).endsWith("intro.md"));

        assertThat(documentAt(PROJECT_ID, "intro.md"))
                .as("intro.md should be gone after prune")
                .isNull();
        assertThat(documentAt(PROJECT_ID, "welcome.md")).isNotNull();
        assertThat(documentAt(PROJECT_ID, "parent.md")).isNotNull();

        // Manifest: top-layer documents now lists welcome.md only;
        // intro.md is no longer tracked anywhere.
        Map<String, Object> manifest = readManifestYaml();
        assertThat(asStringList(manifest.get("documents")))
                .as("post-prune top-layer documents = v2's own only")
                .containsExactlyInAnyOrder("welcome.md");
        assertThat(asStringList(manifest.get("settings")))
                .containsExactlyInAnyOrder("api_endpoint", "api_token", "extra_setting");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> inheritArtefacts =
                (List<Map<String, Object>>) manifest.get("inheritArtefacts");
        assertThat(inheritArtefacts).hasSize(1);
        assertThat(asStringList(inheritArtefacts.get(0).get("documents")))
                .containsExactlyInAnyOrder("parent.md");
    }

    // ──────────────────────────────────────────────────────────────────
    //  Step 4 — export round-trip via folder-mode
    //   Only top-layer artefacts are written. Parent-kit's contributions
    //   stay with parent-kit; a re-import would re-merge them via the
    //   inherits cascade.
    // ──────────────────────────────────────────────────────────────────

    @Test
    @Order(4)
    void step4_export() throws Exception {
        Path exportDir = Path.of("target", "ai-test", "test-kit-export")
                .toAbsolutePath().normalize();
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

        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create(adminBaseUrl() + "/export"))
                        .timeout(Duration.ofMinutes(2))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + auth.token())
                        .POST(HttpRequest.BodyPublishers.ofString(
                                json.writeValueAsString(exportBody)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode())
                .as("export endpoint should return 200 — body: %s", response.body())
                .isEqualTo(200);

        // Top-layer-only export of the current state (v2 + post-prune):
        //   kit.yaml + welcome.md + 2-or-3 settings + echo_tool
        //   intro.md was pruned, parent.md belongs to parent-kit.
        assertThat(Files.isRegularFile(exportDir.resolve("kit.yaml"))).isTrue();
        assertThat(Files.isRegularFile(exportDir.resolve("documents/welcome.md")))
                .as("welcome.md (current top-layer) round-trips").isTrue();
        assertThat(Files.exists(exportDir.resolve("documents/intro.md")))
                .as("intro.md was pruned in step3 — must NOT be in export").isFalse();
        assertThat(Files.exists(exportDir.resolve("documents/parent.md")))
                .as("parent.md belongs to parent-kit — must NOT be in this export").isFalse();
        assertThat(Files.isRegularFile(exportDir.resolve("settings/api_endpoint.yaml"))).isTrue();
        assertThat(Files.isRegularFile(exportDir.resolve("settings/api_token.yaml"))).isTrue();
        assertThat(Files.isRegularFile(exportDir.resolve("settings/extra_setting.yaml")))
                .as("extra_setting (new in v2) round-trips").isTrue();
        assertThat(Files.exists(exportDir.resolve("settings/parent_setting.yaml")))
                .as("parent_setting belongs to parent-kit — must NOT be in this export").isFalse();
        assertThat(Files.isRegularFile(exportDir.resolve("tools/echo_tool.tool.yaml"))).isTrue();

        String exportedKitYaml = Files.readString(exportDir.resolve("kit.yaml"));
        assertThat(exportedKitYaml)
                .as("exported kit.yaml should still carry the inherits list")
                .contains("inherits:")
                .contains("parent-kit-source");

        String exportedToken = Files.readString(
                exportDir.resolve("settings/api_token.yaml"));
        String exportedCiphertext = extractYamlValue(exportedToken, "value");
        assertThat(AesEncryptionService.decryptWith(exportedCiphertext, VAULT_PASSWORD))
                .as("vault-decrypt of exported PASSWORD must round-trip to plaintext")
                .isEqualTo(SECRET_PLAINTEXT);
    }

    // ──────────────────── helpers ────────────────────

    private Map<String, Object> baseRequest(Path kitDir, String mode, boolean prune) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("projectId", PROJECT_ID);
        body.put("source", Map.of("url", kitDir.toUri().toString()));
        body.put("mode", mode);
        body.put("vaultPassword", VAULT_PASSWORD);
        body.put("prune", prune);
        body.put("keepPasswords", false);
        return body;
    }

    private Map<String, Object> postKit(String operation, Map<String, Object> body) throws Exception {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create(adminBaseUrl() + "/" + operation))
                        .timeout(Duration.ofMinutes(2))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + auth.token())
                        .POST(HttpRequest.BodyPublishers.ofString(
                                json.writeValueAsString(body)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode())
                .as("/%s should return 200 — body: %s", operation, response.body())
                .isEqualTo(200);
        Map<String, Object> result = json.readValue(response.body(), Map.class);
        System.out.println("[KitImportTest." + operation + "] " + result);
        return result;
    }

    private String adminBaseUrl() {
        return BASE_URL + "/brain/" + TENANT + "/admin/kits/" + PROJECT_ID;
    }

    private Document documentAt(String projectId, String path) {
        return mongo.getCollection("documents").find(
                        Filters.and(
                                Filters.eq("tenantId", TENANT),
                                Filters.eq("projectId", projectId),
                                Filters.eq("path", path)))
                .first();
    }

    private Document settingAt(String projectId, String key) {
        return mongo.getCollection("settings").find(
                        Filters.and(
                                Filters.eq("tenantId", TENANT),
                                Filters.eq("referenceType", "project"),
                                Filters.eq("referenceId", projectId),
                                Filters.eq("key", key)))
                .first();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object o) {
        return (o instanceof List<?> l) ? (List<Object>) l : List.of();
    }

    private static List<String> asStringList(Object o) {
        if (!(o instanceof List<?> l)) return List.of();
        return l.stream().map(String::valueOf).toList();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readManifestYaml() {
        Document manifestDoc = documentAt(PROJECT_ID, "_vance/kit-manifest.yaml");
        assertThat(manifestDoc)
                .as("kit-manifest.yaml document should exist in the project")
                .isNotNull();
        String yaml = manifestDoc.getString("inlineText");
        assertThat(yaml).as("manifest inlineText should be non-empty").isNotBlank();
        return (Map<String, Object>) new org.yaml.snakeyaml.Yaml().load(yaml);
    }

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

    private static void copyTree(Path source, Path target) throws IOException {
        if (Files.exists(target)) {
            try (Stream<Path> walk = Files.walk(target)) {
                walk.sorted((a, b) -> b.compareTo(a))
                        .forEach(p -> {
                            try { Files.delete(p); } catch (IOException ignored) {}
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
