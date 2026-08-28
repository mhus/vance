package de.mhus.vance.brain.settingform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.prompt.PromptTemplateRenderer;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.LookupResult;
import de.mhus.vance.shared.home.HomeBootstrapService;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Smoke test for the bundled Setting Forms under
 * {@code src/main/resources/vance-defaults/_vance/setting_forms/}. Reads each
 * YAML straight from the classpath and runs it through
 * {@link SettingFormLoader}'s parse + Pebble-compile path. Catches
 * typos and template syntax errors that would otherwise only surface
 * on a tenant's first setting-form listing refresh.
 */
class BundledSettingFormsTest {

    private static final String TENANT = "acme";

    private final DocumentService documentService = mock(DocumentService.class);
    private final PromptTemplateRenderer renderer = new PromptTemplateRenderer();
    private final SettingFormLoader loader = new SettingFormLoader(documentService, renderer);

    @Test
    void llm_setup_parses_cleanly() throws IOException {
        ResolvedSettingForm f = loadBundled("llm-setup");
        assertThat(f.source()).isEqualTo(SettingFormSource.RESOURCE);
        assertThat(f.title()).containsKeys("de", "en");
        assertThat(f.fields())
                .extracting(field -> field.getName())
                .containsExactly(
                        "aliasAnalyze", "aliasFast", "aliasDeep", "aliasWeb", "aliasCode",
                        "aliasImage", "aliasImageHigh",
                        "provider",
                        "embeddingProvider", "embeddingModel", "embeddingKey", "embeddingBaseUrl",
                        "tracing");

        // Chat credentials moved out to one form per provider instance. A
        // single form cannot carry them: `bindsTo.key` is a fixed string, so
        // `ai.provider.openai.apiKey` and `ai.provider.cortecs.apiKey` need
        // two forms, and the shared one used to make the second endpoint
        // unconfigurable. Guard against them creeping back in.
        assertThat(f.fields())
                .filteredOn(field -> field.getBindsTo() != null
                        && field.getBindsTo().getKey().startsWith("ai.provider."))
                .as("chat credentials belong in llm-provider-<instance>, not in llm-setup")
                .isEmpty();

        // Embedding fields bind to the standalone ai.embedding.* namespace
        // (separate from the chat-side ai.provider.*.apiKey credentials).
        for (String f2 : new String[]{
                "embeddingProvider", "embeddingModel", "embeddingKey", "embeddingBaseUrl"}) {
            var fld = f.fields().stream()
                    .filter(field -> field.getName().equals(f2))
                    .findFirst().orElseThrow();
            assertThat(fld.getBindsTo()).isNotNull();
            assertThat(fld.getBindsTo().getKey()).startsWith("ai.embedding.");
        }

        // Chat-tier aliases use the chat-only ai-models choice source.
        for (String aliasField : new String[]{
                "aliasAnalyze", "aliasFast", "aliasDeep", "aliasWeb", "aliasCode"}) {
            var fld = f.fields().stream()
                    .filter(field -> field.getName().equals(aliasField))
                    .findFirst().orElseThrow();
            assertThat(fld.getChoicesFrom()).isEqualTo("ai-models");
            assertThat(fld.getBindsTo()).isNotNull();
            assertThat(fld.getBindsTo().getKey()).startsWith("ai.alias.default.");
        }

        // Image aliases use the kind:image filtered source so the picker
        // doesn't mix chat models in.
        for (String aliasField : new String[]{"aliasImage", "aliasImageHigh"}) {
            var fld = f.fields().stream()
                    .filter(field -> field.getName().equals(aliasField))
                    .findFirst().orElseThrow();
            assertThat(fld.getChoicesFrom()).isEqualTo("ai-image-models");
            assertThat(fld.getBindsTo()).isNotNull();
            assertThat(fld.getBindsTo().getKey()).startsWith("ai.alias.default.image");
        }

        // Tracing should produce two computed settings.
        assertThat(f.computedSettings())
                .extracting(ResolvedComputedSetting::key)
                .contains("tracing.llm.enabled", "tracing.llm.sample_rate");

        // availableIn keeps this form out of per-user home projects but
        // allows the tenant-default project so LLM creds can be set there.
        assertThat(f.availableIn()).containsExactly("!_user_*");
        assertThat(SettingFormLoader.isAvailableIn(f.availableIn(), "research-2026")).isTrue();
        assertThat(SettingFormLoader.isAvailableIn(f.availableIn(), "_tenant")).isTrue();
        assertThat(SettingFormLoader.isAvailableIn(f.availableIn(), "_user_wile.coyote")).isFalse();
    }

    /**
     * Every bundled credential form binds to its <em>own</em> provider
     * instance and to nothing else. This is the assertion that keeps the
     * split meaningful: a copy-paste that leaves an `openai` key in the
     * Cortecs form re-creates exactly the collision the split removed —
     * two endpoints sharing one credential — and it would do so silently,
     * because the form still renders and still saves.
     */
    @Test
    void llm_provider_forms_bindOnlyToTheirOwnInstance() throws IOException {
        for (String instance : new String[]{
                "anthropic", "openai", "openai-experimental", "gemini",
                "ollama", "lmstudio", "cortecs"}) {
            ResolvedSettingForm f = loadBundled("llm-provider-" + instance);
            assertThat(f.fields())
                    .as("form llm-provider-%s has no fields", instance)
                    .isNotEmpty();
            assertThat(f.fields())
                    .allSatisfy(field -> {
                        assertThat(field.getBindsTo())
                                .as("field '%s' in llm-provider-%s must bind to a setting",
                                        field.getName(), instance)
                                .isNotNull();
                        assertThat(field.getBindsTo().getKey())
                                .as("field '%s' in llm-provider-%s binds to a foreign instance",
                                        field.getName(), instance)
                                .startsWith("ai.provider." + instance + ".");
                    });
        }
    }

    /**
     * A required password field cannot be saved without re-typing the
     * secret — blank means "keep the existing value", and FormValidator
     * reports blank+required as an error. A required key field would make
     * every unrelated edit (changing a base URL) fail with 422.
     */
    @Test
    void llm_provider_forms_neverMarkCredentialsRequired() throws IOException {
        for (String instance : new String[]{
                "anthropic", "openai", "openai-experimental", "gemini", "cortecs"}) {
            ResolvedSettingForm f = loadBundled("llm-provider-" + instance);
            assertThat(f.fields())
                    .filteredOn(field -> "password".equals(field.getType()))
                    .as("llm-provider-%s", instance)
                    .isNotEmpty()
                    .allSatisfy(field -> assertThat(field.isRequired()).isFalse());
        }
    }

    @Test
    void quota_preset_parses_cleanly_with_conditional_overlap() throws IOException {
        ResolvedSettingForm f = loadBundled("quota-preset");
        assertThat(f.fields())
                .extracting(field -> field.getName())
                .containsExactly("budget", "customDailyTokens", "customMonthlyTokens", "warnAtPercent");

        // Preset-mode and custom-mode entries share the same target key
        // (quota.daily_tokens / quota.monthly_tokens). The loader accepts
        // this because both sides carry writeIf.
        assertThat(f.computedSettings())
                .extracting(ResolvedComputedSetting::key)
                .containsExactly("quota.daily_tokens", "quota.monthly_tokens");
    }

    @Test
    void integrations_jira_parses_cleanly() throws IOException {
        ResolvedSettingForm f = loadBundled("integrations-jira");
        assertThat(f.fields())
                .extracting(field -> field.getName())
                .containsExactly("instanceUrl", "authMode", "oauthAccessToken",
                        "oauthRefreshToken", "apiToken", "userEmail");

        // Marker computed-setting is unconditional.
        assertThat(f.computedSettings()).hasSize(1);
        assertThat(f.computedSettings().get(0).key())
                .isEqualTo("credentials.jira.configured");

        // All three credentials stay PASSWORD (the default for a `password` field):
        // the Jira connector uses them, and a connector resolves through
        // SecretResolver.resolveForConnector, which reads PASSWORD too. Declaring
        // HIDDEN here would make them readable for agents and scripts for no gain
        // — the type follows the use, not the mechanism that resolves it.
        assertThat(settingTypeOf(f, "oauthAccessToken")).isNull();
        assertThat(settingTypeOf(f, "apiToken")).isNull();
        assertThat(settingTypeOf(f, "oauthRefreshToken")).isNull();
    }

    private static @org.jspecify.annotations.Nullable String settingTypeOf(
            ResolvedSettingForm form, String fieldName) {
        return form.fields().stream()
                .filter(field -> fieldName.equals(field.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no such field: " + fieldName))
                .getBindsTo()
                .getSettingType();
    }

    @Test
    void vault_conditionalFieldsAreNotRequired() throws IOException {
        ResolvedSettingForm f = loadBundled("vault");

        // The provider select defaults to `settings`, which needs no
        // connection at all. FormValidator does not know `showIf` — it sees
        // the whole field list and reports "required" for anything blank —
        // so a required Infisical field would make the form unsavable for
        // the default provider. Regression: the form shipped with
        // required:true on baseUrl/project/environment/clientId/clientSecret
        // and every save returned 422.
        assertThat(f.fields())
                .filteredOn(field -> field.getShowIf() != null)
                .isNotEmpty()
                .allSatisfy(field -> assertThat(field.isRequired())
                        .as("field '%s' is behind showIf and must not be required", field.getName())
                        .isFalse());

        // The one unconditional field may — and must — stay required.
        assertThat(f.fields())
                .filteredOn(field -> "type".equals(field.getName()))
                .singleElement()
                .satisfies(field -> {
                    assertThat(field.isRequired()).isTrue();
                    assertThat(field.getDefaultValue()).isEqualTo("settings");
                });
    }

    private ResolvedSettingForm loadBundled(String name) throws IOException {
        String resourcePath = "vance-defaults/_vance/setting_forms/" + name + ".yaml";
        String yaml = readClasspath(resourcePath);
        String docPath = "_vance/setting_forms/" + name + ".yaml";

        when(documentService.findByPath(any(), any(), any())).thenReturn(Optional.empty());
        when(documentService.lookupCascade(
                eq(TENANT),
                eq(HomeBootstrapService.TENANT_PROJECT_NAME),
                eq(docPath)))
                .thenReturn(Optional.of(new LookupResult(
                        docPath, yaml, LookupResult.Source.RESOURCE, null)));

        return loader.load(TENANT, null, null, name).orElseThrow(
                () -> new AssertionError("bundled setting form '" + name + "' could not be loaded"));
    }

    private static String readClasspath(String path) throws IOException {
        try (InputStream in = BundledSettingFormsTest.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new AssertionError("classpath resource missing: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
