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
                        "anthropicKey", "openaiKey", "geminiKey",
                        "openaiBaseUrl", "ollamaBaseUrl",
                        "tracing");

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

        // availableIn must keep this form out of system projects.
        assertThat(f.availableIn()).containsExactly("!_*");
        assertThat(SettingFormLoader.isAvailableIn(f.availableIn(), "research-2026")).isTrue();
        assertThat(SettingFormLoader.isAvailableIn(f.availableIn(), "_tenant")).isFalse();
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
