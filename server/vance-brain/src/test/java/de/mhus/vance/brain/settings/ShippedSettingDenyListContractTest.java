package de.mhus.vance.brain.settings;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.shared.settings.AgentSettingKeyPolicy;
import de.mhus.vance.shared.settings.KitSettingKeyPolicy;
import de.mhus.vance.shared.settings.SecretReferenceKeyPolicy;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

/**
 * The three setting-key deny-lists, checked against the {@code application.yml}
 * this brain actually ships — not against the {@code @Value} defaults in the Java
 * source.
 *
 * <h2>Why the shipped file and not the default</h2>
 * The lists exist twice: as a fallback in the {@code @Value} annotation and as an
 * explicit value in {@code application.yml}. The file <b>wins</b>, so a family
 * present in the Java default but absent from the file is not protected — and
 * that is precisely how {@code kit.*} came to be missing from
 * {@code agentWriteDenyKeys} while the source suggested otherwise. Every unit test
 * up to now constructed the policies with a hand-written pattern string, so all of
 * them passed on a deployment that protected less than they asserted. This one
 * reads the artefact.
 *
 * <p>Assertions are on <b>coverage</b>, not on the exact string: an operator (and
 * the next feature) may add families, and pinning the literal would turn every
 * addition into a test edit. What must never happen is a family silently
 * <em>dropping out</em>.
 */
class ShippedSettingDenyListContractTest {

    private static Properties shipped;

    /**
     * Read off the classpath rather than from {@code src/main/resources}: the
     * source file carries unfiltered Maven tokens ({@code @project.version@}) and
     * is not valid YAML. {@code target/classes/application.yml} is both parseable
     * and the copy that ends up in the jar.
     */
    @BeforeAll
    static void loadShippedConfiguration() {
        ClassPathResource resource = new ClassPathResource("application.yml");
        assertThat(resource.exists())
                .as("application.yml must be on the test classpath")
                .isTrue();
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(resource);
        yaml.afterPropertiesSet();
        Properties props = yaml.getObject();
        assertThat(props).as("application.yml parsed to no properties").isNotNull();
        shipped = props;
    }

    /**
     * The value as configured. An absent key is a failure, not a fallback: it
     * would mean the {@code @Value} default applies, and relying on the default
     * is exactly what this test exists to stop.
     */
    private static String shippedValue(String key) {
        Object value = shipped.get(key);
        assertThat(value)
                .as("%s must be set explicitly in application.yml — relying on the @Value "
                        + "default means the shipped configuration is invisible to review", key)
                .isNotNull();
        return String.valueOf(value);
    }

    // ──────────────── W3: what an agent may never write ────────────────

    @Test
    void agentWriteDenyList_coversEveryFamilyThatDecidesWhereThisInstallationGetsThingsFrom() {
        AgentSettingKeyPolicy policy =
                new AgentSettingKeyPolicy(shippedValue("vance.settings.agentWriteDenyKeys"));

        // Each of these lets an agent redirect an infrastructure decision:
        // which model answers, which vault holds the secrets, which store
        // identity a purchase is checked against, where a project's tool
        // definitions are fetched from, which file tree a mount exposes.
        for (String key : List.of(
                "ai.provider.openai.apiKey",
                "ai.provider.openai.baseUrl",
                "vault.type",
                "vault.clientSecret",
                "store.token.vancetope-library",
                "store.account.vancetope-library",
                "kit.token.acme",
                "jaglan.mount.docs.rootDir")) {
            assertThat(policy.isDenied(key)).as("agent write of '%s'", key).isTrue();
        }
    }

    @Test
    void agentWriteDenyList_leavesOrdinarySettingsAlone() {
        AgentSettingKeyPolicy policy =
                new AgentSettingKeyPolicy(shippedValue("vance.settings.agentWriteDenyKeys"));

        // A deny-list that grew until it covered everything would stop kits and
        // tool templates from doing their job — the counter-assertion matters as
        // much as the coverage one.
        for (String key : List.of("crm.baseUrl", "chat.language", "smtp.password")) {
            assertThat(policy.isDenied(key)).as("agent write of '%s'", key).isFalse();
        }
    }

    // ──────────────── R3: what no reference may resolve ────────────────

    @Test
    void referenceDenyList_coversTheKeysCompiledCodeReadsAtAFixedName() {
        SecretReferenceKeyPolicy policy = new SecretReferenceKeyPolicy(
                shippedValue("vance.settings.secretReferenceDenyKeys"));

        // Connectors resolve PASSWORD by design, so the type no longer keeps a
        // reference away from these — a tool document names its target URL next
        // to its headers.
        for (String key : List.of(
                "ai.provider.openai.apiKey",
                "vault.clientSecret",
                "store.token.vancetope-library")) {
            assertThat(policy.isDenied(key)).as("reference to '%s'", key).isTrue();
        }
    }

    @Test
    void referenceDenyList_stillLetsProvisioningResolveItsOwnToken() {
        SecretReferenceKeyPolicy policy = new SecretReferenceKeyPolicy(
                shippedValue("vance.settings.secretReferenceDenyKeys"));

        // The documented asymmetry between the two lists, pinned: a provisioning
        // document resolves {{secret:project:kit.token.<id>}}, so folding the
        // write list into the reference list would break provisioning in order
        // to close a store leak. If this ever goes red, the lists were merged.
        assertThat(policy.isDenied("kit.token.acme")).isFalse();
        assertThat(new AgentSettingKeyPolicy(
                shippedValue("vance.settings.agentWriteDenyKeys"))
                .isDenied("kit.token.acme")).isTrue();
    }

    @Test
    void referenceDenyList_leavesOrdinaryConnectorCredentialsResolvable() {
        SecretReferenceKeyPolicy policy = new SecretReferenceKeyPolicy(
                shippedValue("vance.settings.secretReferenceDenyKeys"));

        for (String key : List.of("smtp.password", "crm.apiToken", "deploy-token")) {
            assertThat(policy.isDenied(key)).as("reference to '%s'", key).isFalse();
        }
    }

    // ──────────────── what a kit may not write ────────────────

    @Test
    void kitDenyList_coversTheSameFamiliesAsTheAgentWriteList() {
        KitSettingKeyPolicy policy =
                new KitSettingKeyPolicy(shippedValue("vance.kits.settingDenyKeys"));

        // A kit installs unattended, so anything an agent may not write it may
        // not write either — including store.account.*, which is the value its
        // own licence gate compares the signed licensedTo against.
        for (String key : List.of(
                "ai.provider.openai.apiKey",
                "vault.type",
                "store.account.vancetope-library",
                "kit.token.acme",
                "jaglan.mount.docs.rootDir")) {
            assertThat(policy.isDenied(key)).as("kit write of '%s'", key).isTrue();
        }
        assertThat(policy.isDenied("crm.baseUrl")).isFalse();
    }
}
