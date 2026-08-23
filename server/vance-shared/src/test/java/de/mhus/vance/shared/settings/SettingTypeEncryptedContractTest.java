package de.mhus.vance.shared.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.settings.SettingType;
import de.mhus.vance.shared.audit.AuditService;
import de.mhus.vance.shared.crypto.AesEncryptionService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * Pins the {@link SettingType#encrypted()} contract across <em>every</em> enum
 * constant rather than the two we happen to know today.
 *
 * <p>Rationale (see {@code planning/setting-type-hidden.md} §4.1): the
 * PASSWORD/HIDDEN split turned ~19 {@code == SettingType.PASSWORD} comparisons
 * into {@code type.encrypted()} calls. A single missed one in a write path would
 * let an encrypted value be persisted in cleartext — a worse failure than the
 * leak the split closes. Iterating the enum means a future third encrypted type
 * is covered by these tests the moment it is added to {@code encrypted()}.
 */
class SettingTypeEncryptedContractTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = SettingService.SCOPE_PROJECT;
    private static final String REF = "proj";

    private SettingRepository repository;
    private SettingService service;

    @BeforeEach
    void setUp() {
        repository = mock(SettingRepository.class);
        service = new SettingService(repository, mock(MongoTemplate.class),
                new AesEncryptionService("unit-test-master-key"), mock(AuditService.class),
                new AgentSettingKeyPolicy(""));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    // ──────────────── write path: encrypted types never reach set() ────────────────

    @ParameterizedTest
    @EnumSource(SettingType.class)
    void set_rejectsEveryEncryptedType(SettingType type) {
        if (!type.encrypted()) return;

        assertThatThrownBy(() -> service.set(
                TENANT, PROJECT, REF, "some.key", "value", type, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("setEncryptedSecret");
    }

    @ParameterizedTest
    @EnumSource(SettingType.class)
    void set_acceptsEveryPlainType(SettingType type) {
        if (type.encrypted()) return;

        SettingDocument saved = service.set(
                TENANT, PROJECT, REF, "some.key", "value", type, null);

        assertThat(saved.getType()).isEqualTo(type);
        assertThat(saved.getValue()).isEqualTo("value");
    }

    @ParameterizedTest
    @EnumSource(SettingType.class)
    void setEncryptedSecret_rejectsEveryPlainType(SettingType type) {
        if (type.encrypted()) return;

        assertThatThrownBy(() -> service.setEncryptedSecret(
                TENANT, PROJECT, REF, "some.key", "value", type))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires an encrypted type");
    }

    @ParameterizedTest
    @EnumSource(SettingType.class)
    void setEncryptedSecret_storesCiphertextAndRoundTripsForEveryEncryptedType(SettingType type) {
        if (!type.encrypted()) return;

        SettingDocument saved = service.setEncryptedSecret(
                TENANT, PROJECT, REF, "api.key", "s3cr3t", type);

        assertThat(saved.getType()).isEqualTo(type);
        assertThat(saved.getValue()).isNotNull().isNotEqualTo("s3cr3t");

        stubFind(saved);
        assertThat(service.getDecryptedPassword(TENANT, PROJECT, REF, "api.key"))
                .isEqualTo("s3cr3t");
    }

    // ──────────────── read paths: no encrypted type leaks as a string ────────────────

    @ParameterizedTest
    @EnumSource(SettingType.class)
    void getStringValue_refusesEveryEncryptedType(SettingType type) {
        if (!type.encrypted()) return;

        stubFind(service.setEncryptedSecret(
                TENANT, PROJECT, REF, "api.key", "s3cr3t", type));

        assertThat(service.getStringValue(TENANT, PROJECT, REF, "api.key")).isNull();
    }

    @ParameterizedTest
    @EnumSource(SettingType.class)
    void prefixRead_skipsEveryEncryptedType(SettingType type) {
        if (!type.encrypted()) return;

        SettingDocument secret = service.setEncryptedSecret(
                TENANT, PROJECT, REF, "ai.provider.x.apiKey", "s3cr3t", type);
        SettingDocument plain = service.set(
                TENANT, PROJECT, REF, "ai.provider.x.baseUrl", "https://x", SettingType.STRING, null);
        when(repository.findByTenantIdAndReferenceTypeAndReferenceId(TENANT, PROJECT, REF))
                .thenReturn(List.of(secret, plain));
        when(repository.findByTenantIdAndReferenceTypeAndReferenceId(
                anyString(), anyString(), anyString()))
                .thenReturn(List.of(secret, plain));

        assertThat(service.findByPrefixCascade(TENANT, REF, null, "ai.provider."))
                .containsOnlyKeys("ai.provider.x.baseUrl");
    }

    // ──────────────── the reference-resolution axis ────────────────

    @Test
    void password_is_the_only_type_a_secret_reference_may_not_resolve() {
        for (SettingType type : SettingType.values()) {
            assertThat(type.referenceReadable())
                    .as("%s referenceReadable", type)
                    .isEqualTo(type != SettingType.PASSWORD);
        }
    }

    @Test
    void hidden_is_encrypted_at_rest_but_reference_readable() {
        assertThat(SettingType.HIDDEN.encrypted()).isTrue();
        assertThat(SettingType.HIDDEN.referenceReadable()).isTrue();
        assertThat(SettingType.PASSWORD.encrypted()).isTrue();
        assertThat(SettingType.PASSWORD.referenceReadable()).isFalse();
    }

    // ──────────────── W1 asks the predicate, not the constant ────────────────

    @Test
    void setAgentSecret_refusesToOverwriteWhatAnAgentMayNotReadBack() {
        SettingDocument stored = service.setEncryptedSecret(
                TENANT, PROJECT, REF, "smtp.password", "operator-set", SettingType.PASSWORD);
        stubFind(stored);

        assertThatThrownBy(() -> service.setAgentSecret(
                TENANT, PROJECT, REF, "smtp.password", "agent-set", SettingType.HIDDEN))
                .isInstanceOf(SecretAccessDeniedException.class)
                .hasMessageContaining("smtp.password");
    }

    @Test
    void setAgentSecret_overwritesAHiddenSetting() {
        // The other side of the same threshold: a vault secret exists to be
        // written by the tools that resolve it, so HIDDEN has to stay writable.
        SettingDocument stored = service.setEncryptedSecret(
                TENANT, PROJECT, REF, "deploy-token", "old", SettingType.HIDDEN);
        stubFind(stored);

        SettingDocument saved = service.setAgentSecret(
                TENANT, PROJECT, REF, "deploy-token", "new", SettingType.HIDDEN);

        assertThat(saved.getType()).isEqualTo(SettingType.HIDDEN);
    }

    // ──────────────── no constant comparison anywhere in main ────────────────

    /**
     * Matches a comparison of a {@link SettingType} constant, in either
     * direction and either operator, plus the {@code equals} spelling of the
     * same thought.
     */
    private static final Pattern CONSTANT_COMPARISON = Pattern.compile(
            "(?:[=!]=\\s*SettingType\\.(?:PASSWORD|HIDDEN))"
                    + "|(?:SettingType\\.(?:PASSWORD|HIDDEN)\\s*[=!]=)"
                    + "|(?:SettingType\\.(?:PASSWORD|HIDDEN)\\.equals\\s*\\()"
                    + "|(?:\\.equals\\s*\\(\\s*SettingType\\.(?:PASSWORD|HIDDEN)\\s*\\))");

    /**
     * The one rule CLAUDE.md and {@code specification/public/settings-system.md}
     * state literally: no {@code == SettingType.PASSWORD} in the tree outside the
     * enum itself. It is a rule about <em>every future</em> call site, which no
     * behavioural test can express — with two protection levels the predicate and
     * the constant agree, so the failure the rule prevents only becomes
     * observable once a third level exists, i.e. after the damage.
     *
     * <p>Hence the source scan. {@code SettingType.java} is exempt (it defines
     * the thresholds) and so is test code, which legitimately pins what each
     * constant answers.
     */
    @Test
    void noProductionSourceComparesAgainstAProtectionConstant() {
        Path serverRoot = serverRoot();
        List<Path> sourceRoots = mainJavaRoots(serverRoot);
        assertThat(sourceRoots)
                .as("source roots found below %s", serverRoot)
                .isNotEmpty();

        List<String> offenders = new ArrayList<>();
        for (Path root : sourceRoots) {
            try (Stream<Path> files = Files.walk(root)) {
                files.filter(p -> p.getFileName().toString().endsWith(".java"))
                        .filter(p -> !p.getFileName().toString().equals("SettingType.java"))
                        .forEach(p -> {
                            Matcher m = CONSTANT_COMPARISON.matcher(read(p));
                            while (m.find()) {
                                offenders.add(serverRoot.relativize(p) + ": " + m.group());
                            }
                        });
            } catch (IOException e) {
                throw new UncheckedIOException("cannot scan " + root, e);
            }
        }

        assertThat(offenders)
                .as("compare against SettingType.encrypted() / .referenceReadable() instead — "
                        + "a constant comparison silently excludes every level added later")
                .isEmpty();
    }

    /**
     * Every {@code <module>/src/main/java} below the aggregator, including the
     * one extra nesting level the {@code plugins/} aggregator introduces. Test
     * sources are deliberately not scanned.
     */
    private static List<Path> mainJavaRoots(Path serverRoot) {
        try (Stream<Path> paths = Files.walk(serverRoot, 5)) {
            return paths.filter(Files::isDirectory)
                    .filter(p -> p.endsWith(Path.of("src", "main", "java")))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list modules of " + serverRoot, e);
        }
    }

    /** The Maven aggregator directory holding all server modules. */
    private static Path serverRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.isDirectory(dir.resolve("vance-api"))
                    && Files.isDirectory(dir.resolve("vance-shared"))
                    && Files.isDirectory(dir.resolve("vance-brain"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException(
                "server root not found above " + Path.of("").toAbsolutePath());
    }

    private static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + p, e);
        }
    }

    private void stubFind(SettingDocument doc) {
        when(repository.findByTenantIdAndReferenceTypeAndReferenceIdAndKey(
                anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.of(doc));
    }
}
