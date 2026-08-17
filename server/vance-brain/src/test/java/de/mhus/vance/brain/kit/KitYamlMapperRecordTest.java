package de.mhus.vance.brain.kit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.api.kit.KitArtefactDto;
import de.mhus.vance.api.kit.KitArtefactsDto;
import de.mhus.vance.api.kit.KitConfigDto;
import de.mhus.vance.api.kit.KitDescriptorDto;
import de.mhus.vance.api.kit.KitInheritDto;
import de.mhus.vance.api.kit.KitInstalledRecordDto;
import de.mhus.vance.api.kit.KitMetadataDto;
import de.mhus.vance.api.kit.KitOriginDto;
import de.mhus.vance.api.kit.KitPolicyAction;
import de.mhus.vance.api.kit.KitPolicyDto;
import de.mhus.vance.api.kit.KitPolicyRuleDto;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * YAML mapping of the install record and the user config document —
 * spec: {@code planning/kit-installed-multi.md} §4.
 */
class KitYamlMapperRecordTest {

    // ── install record ───────────────────────────────────────────────

    @Test
    void installedRecord_roundTrips() {
        KitInstalledRecordDto original = KitInstalledRecordDto.builder()
                .id("kernel-security-a4f32b")
                .kit(KitMetadataDto.builder()
                        .name("kernel-security").description("Kernel research").version("1.2.0")
                        .build())
                .origin(KitOriginDto.builder()
                        .url("https://github.com/mhus/vance-kits.git")
                        .path("kernel-security")
                        .branch("main")
                        .commit("a4f32b1")
                        .installedAt(Instant.parse("2026-08-17T10:00:00Z"))
                        .installedBy("hummel@sipgate.de")
                        .build())
                .descriptor(KitDescriptorDto.builder()
                        .name("kernel-security").description("Kernel research")
                        .inherits(List.of(KitInheritDto.builder()
                                .url("https://github.com/mhus/vance-kits.git")
                                .path("c-development").branch("main").build()))
                        .build())
                .artefacts(KitArtefactsDto.builder()
                        .documents(List.of(KitArtefactDto.builder()
                                .id("skills/cve/SKILL.md").hash("sha256:1f0c")
                                .layer("kernel-security").build()))
                        .settings(List.of(KitArtefactDto.builder()
                                .id("ai.alias.default.analyze").hash("sha256:44de")
                                .layer("c-development").build()))
                        .build())
                .hasEncryptedSecrets(true)
                .build();

        KitInstalledRecordDto parsed = KitYamlMapper.parseInstalledRecord(
                KitYamlMapper.writeInstalledRecord(original));

        assertThat(parsed.getId()).isEqualTo("kernel-security-a4f32b");
        assertThat(parsed.getKit().getVersion()).isEqualTo("1.2.0");
        assertThat(parsed.getOrigin().getCommit()).isEqualTo("a4f32b1");
        assertThat(parsed.getOrigin().getInstalledAt())
                .isEqualTo(Instant.parse("2026-08-17T10:00:00Z"));
        assertThat(parsed.getDescriptor().getInherits()).hasSize(1);
        assertThat(parsed.getDescriptor().getInherits().get(0).getPath()).isEqualTo("c-development");
        assertThat(parsed.getArtefacts().getDocuments()).hasSize(1);
        assertThat(parsed.getArtefacts().getDocuments().get(0).getLayer())
                .isEqualTo("kernel-security");
        assertThat(parsed.getArtefacts().getSettings().get(0).getId())
                .isEqualTo("ai.alias.default.analyze");
        assertThat(parsed.getArtefacts().getSettings().get(0).getLayer())
                .isEqualTo("c-development");
        assertThat(parsed.isHasEncryptedSecrets()).isTrue();
    }

    @Test
    void installedRecord_documentsUsePathKey_settingsUseKeyKey() {
        // The YAML should read like what it describes, not like a generic
        // id list — this is a file people open in the document editor.
        String yaml = KitYamlMapper.writeInstalledRecord(minimalRecord());
        assertThat(yaml).contains("- path: onboarding.md");
        assertThat(yaml).contains("- key: tracing.llm");
    }

    @Test
    void installedRecord_withoutId_isRejected() {
        // A record without an id cannot be addressed for update or
        // uninstall — tolerating it would create an entry nobody can remove.
        String yaml = """
                kit:
                  name: k
                  description: d
                origin:
                  url: file:///x
                """;
        assertThatThrownBy(() -> KitYamlMapper.parseInstalledRecord(yaml))
                .isInstanceOf(KitException.class)
                .hasMessageContaining("id");
    }

    @Test
    void installedRecord_withoutOrigin_isRejected() {
        String yaml = """
                id: k-abc123
                kit:
                  name: k
                  description: d
                """;
        assertThatThrownBy(() -> KitYamlMapper.parseInstalledRecord(yaml))
                .isInstanceOf(KitException.class)
                .hasMessageContaining("origin");
    }

    // ── config document ──────────────────────────────────────────────

    @Test
    void config_absentPolicy_leavesItUnset() {
        // Null means "the user has no opinion", which is what lets the
        // kit author's suggested policy through. Defaulting it here would
        // silence every suggestion a kit ever ships.
        KitConfigDto config = KitYamlMapper.parseConfig("sortIndex: 20\n");
        assertThat(config.getSortIndex()).isEqualTo(20);
        assertThat(config.getPolicy()).isNull();
    }

    @Test
    void config_explicitKeep_isNotTheSameAsAbsent() {
        // Written out, `keep` is a decision and overrides the kit's suggestion.
        KitConfigDto config = KitYamlMapper.parseConfig("policy: keep\n");
        assertThat(config.getPolicy()).isNotNull();
        assertThat(config.getPolicy().getDefaultAction()).isEqualTo(KitPolicyAction.KEEP);
    }

    @Test
    void config_scalarShorthand_expandsToDefaultAction() {
        KitConfigDto config = KitYamlMapper.parseConfig("policy: overwrite\n");
        assertThat(config.getPolicy().getDefaultAction()).isEqualTo(KitPolicyAction.OVERWRITE);
        assertThat(config.getPolicy().getRules()).isEmpty();
        assertThat(config.getSortIndex()).isNull();
    }

    @Test
    void config_fullPolicy_parsesRulesInOrder() {
        KitConfigDto config = KitYamlMapper.parseConfig("""
                policy:
                  default: overwrite
                  rules:
                    - document: "recipes/*.yaml"
                      action: keep
                    - setting: "ai.alias.*"
                      action: ignore
                """);
        assertThat(config.getPolicy().getDefaultAction()).isEqualTo(KitPolicyAction.OVERWRITE);
        assertThat(config.getPolicy().getRules()).hasSize(2);
        assertThat(config.getPolicy().getRules().get(0).getDocument()).isEqualTo("recipes/*.yaml");
        assertThat(config.getPolicy().getRules().get(1).getSetting()).isEqualTo("ai.alias.*");
        assertThat(config.getPolicy().getRules().get(1).getAction()).isEqualTo(KitPolicyAction.IGNORE);
    }

    @Test
    void config_ruleWithBothNamespaces_isRejected() {
        assertThatThrownBy(() -> KitYamlMapper.parseConfig("""
                policy:
                  rules:
                    - document: "a.md"
                      setting: "b"
                      action: keep
                """))
                .isInstanceOf(KitException.class)
                .hasMessageContaining("exactly one");
    }

    @Test
    void config_ruleWithNeitherNamespace_isRejected() {
        assertThatThrownBy(() -> KitYamlMapper.parseConfig("""
                policy:
                  rules:
                    - action: keep
                """))
                .isInstanceOf(KitException.class)
                .hasMessageContaining("exactly one");
    }

    @Test
    void config_unknownAction_namesTheOffendingValue() {
        // This is the one kit file a human edits by hand — the error has
        // to say what was wrong, not just that something was.
        assertThatThrownBy(() -> KitYamlMapper.parseConfig("policy: replace\n"))
                .isInstanceOf(KitException.class)
                .hasMessageContaining("replace")
                .hasMessageContaining("keep");
    }

    @Test
    void config_mergeAction_isAccepted() {
        assertThat(KitYamlMapper.parseConfig("policy: merge\n").getPolicy().getDefaultAction())
                .isEqualTo(KitPolicyAction.MERGE);
    }

    @Test
    void descriptor_suggestedPolicy_roundTrips() {
        // A kit author may ship a recommendation; it uses the very same
        // grammar as the user's own config.
        String yaml = """
                name: kernel-security
                description: Kernel research
                policy:
                  default: keep
                  rules:
                    - setting: "ai.alias.*"
                      action: ignore
                """;
        KitDescriptorDto parsed = KitYamlMapper.parseDescriptor(yaml);
        assertThat(parsed.getPolicy()).isNotNull();
        assertThat(parsed.getPolicy().getDefaultAction()).isEqualTo(KitPolicyAction.KEEP);
        assertThat(parsed.getPolicy().getRules()).hasSize(1);
        assertThat(parsed.getPolicy().getRules().get(0).getSetting()).isEqualTo("ai.alias.*");

        KitDescriptorDto reparsed = KitYamlMapper.parseDescriptor(
                KitYamlMapper.writeDescriptor(parsed));
        assertThat(reparsed.getPolicy().getRules().get(0).getAction())
                .isEqualTo(KitPolicyAction.IGNORE);
    }

    @Test
    void descriptor_withoutPolicy_leavesItUnset() {
        // Absent means "no opinion" — which is different from "keep", and
        // the cascade relies on telling those apart.
        KitDescriptorDto parsed = KitYamlMapper.parseDescriptor("""
                name: plain
                description: no opinion on updates
                """);
        assertThat(parsed.getPolicy()).isNull();
    }

    @Test
    void config_nonNumericSortIndex_isRejected() {
        assertThatThrownBy(() -> KitYamlMapper.parseConfig("sortIndex: last\n"))
                .isInstanceOf(KitException.class)
                .hasMessageContaining("sortIndex");
    }

    @Test
    void config_roundTripsThroughShorthandAndFullForm() {
        KitConfigDto shorthand = KitConfigDto.builder()
                .policy(KitPolicyDto.builder().defaultAction(KitPolicyAction.IGNORE).build())
                .build();
        assertThat(KitYamlMapper.parseConfig(KitYamlMapper.writeConfig(shorthand))
                .getPolicy().getDefaultAction()).isEqualTo(KitPolicyAction.IGNORE);

        KitConfigDto full = KitConfigDto.builder()
                .sortIndex(30)
                .policy(KitPolicyDto.builder()
                        .defaultAction(KitPolicyAction.OVERWRITE)
                        .rules(List.of(KitPolicyRuleDto.builder()
                                .document("recipes/*.yaml").action(KitPolicyAction.KEEP).build()))
                        .build())
                .build();
        KitConfigDto parsed = KitYamlMapper.parseConfig(KitYamlMapper.writeConfig(full));
        assertThat(parsed.getSortIndex()).isEqualTo(30);
        assertThat(parsed.getPolicy().getRules()).hasSize(1);
        assertThat(parsed.getPolicy().getRules().get(0).getAction()).isEqualTo(KitPolicyAction.KEEP);
    }

    private static KitInstalledRecordDto minimalRecord() {
        return KitInstalledRecordDto.builder()
                .id("k-abc123")
                .kit(KitMetadataDto.builder().name("k").description("d").build())
                .origin(KitOriginDto.builder().url("file:///x").build())
                .artefacts(KitArtefactsDto.builder()
                        .documents(List.of(KitArtefactDto.builder()
                                .id("onboarding.md").hash("sha256:aa").layer("k").build()))
                        .settings(List.of(KitArtefactDto.builder()
                                .id("tracing.llm").hash("sha256:bb").layer("k").build()))
                        .build())
                .build();
    }
}
