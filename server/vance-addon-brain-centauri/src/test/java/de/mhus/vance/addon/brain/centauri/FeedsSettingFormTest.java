package de.mhus.vance.addon.brain.centauri;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.centauri.CentauriSettings;
import de.mhus.vance.brain.prompt.PromptTemplateRenderer;
import de.mhus.vance.brain.settingform.ResolvedSettingForm;
import de.mhus.vance.brain.settingform.SettingFormLoader;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.LookupResult;
import de.mhus.vance.shared.home.HomeBootstrapService;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Guards the bundled {@code _vance/setting_forms/feeds.yaml}.
 *
 * <p>A setting form is YAML with Pebble in its {@code showIf}/{@code writeIf}
 * expressions, and nothing in the normal build notices when one of those stops
 * compiling or a field binds to a key that no longer exists. Running the real
 * loader catches the first; comparing against
 * {@link de.mhus.vance.brain.centauri.CentauriSettings} catches the second —
 * a form that writes {@code centauri.endpoint.x.protokol} would otherwise look
 * like it worked and configure nothing.
 */
class FeedsSettingFormTest {

    private static final String RESOURCE = "vance-defaults/_vance/setting_forms/feeds.yaml";
    private static final String TENANT = "acme";
    private static final String PROJECT = "news";

    private final DocumentService documentService = mock(DocumentService.class);
    private final SettingFormLoader loader =
            new SettingFormLoader(documentService, new PromptTemplateRenderer());

    @Test
    void form_parsesThroughTheRealLoader() {
        ResolvedSettingForm form = load();

        assertThat(form.title()).containsKeys("de", "en");
        // Sources are tenant-wide: a feed in one project should not have to
        // re-configure the endpoint the next project already has.
        assertThat(form.defaultScope()).isEqualTo("tenant");
    }

    @Test
    void form_bindsEveryFieldToACentauriEndpointKey() {
        List<String> keys = boundKeys();

        assertThat(keys).isNotEmpty();
        assertThat(keys).allSatisfy(key ->
                assertThat(key).startsWith(CentauriSettings.PREFIX_ENDPOINT));
    }

    @Test
    void form_usesTheSuffixesTheFactoryActuallyReads() {
        // The factory pulls apart centauri.endpoint.<id>.<suffix>; a typo in a
        // suffix lands in the protocol's extras map and is silently ignored.
        List<String> known = List.of(
                CentauriSettings.SUFFIX_PROTOCOL,
                CentauriSettings.SUFFIX_BASE_URL,
                CentauriSettings.SUFFIX_API_KEY,
                CentauriSettings.SUFFIX_ENABLED,
                CentauriSettings.SUFFIX_SEND_ACTOR,
                ".feedPath");

        assertThat(boundKeys()).allSatisfy(key ->
                assertThat(known).anySatisfy(suffix -> assertThat(key).endsWith(suffix)));
    }

    @Test
    void form_pinsTheProtocolOfEveryOfferedSource() {
        // Without .protocol the factory skips the endpoint entirely, so the
        // protocol writes are what the toggles actually do.
        assertThat(protocolWrites())
                .containsExactlyInAnyOrder("ode", "usgs", "wikipedia", "wikipedia");
    }

    @Test
    void form_offersEachProtocolThatExists() {
        assertThat(protocolWrites()).contains("ode", "usgs", "wikipedia");
    }

    // ── helpers ──────────────────────────────────────────────────────

    private List<String> boundKeys() {
        ResolvedSettingForm form = load();
        List<String> keys = new java.util.ArrayList<>();
        form.fields().forEach(field -> {
            if (field.getBindsTo() != null) {
                keys.add(field.getBindsTo().getKey());
            }
        });
        form.computedSettings().forEach(computed -> keys.add(computed.key()));
        return keys;
    }

    private List<String> protocolWrites() {
        List<String> values = new java.util.ArrayList<>();
        load().computedSettings().forEach(computed -> {
            if (computed.key().endsWith(CentauriSettings.SUFFIX_PROTOCOL)) {
                values.add(computed.valueTemplate());
            }
        });
        return values;
    }

    private ResolvedSettingForm load() {
        String yaml = resource();
        when(documentService.findByPath(eq(TENANT), any(), any())).thenReturn(Optional.empty());
        when(documentService.lookupCascade(eq(TENANT), any(), any()))
                .thenReturn(Optional.of(new LookupResult(
                        "_vance/setting_forms/feeds.yaml", yaml,
                        LookupResult.Source.VANCE, null)));
        Optional<ResolvedSettingForm> hit = loader.load(
                TENANT, HomeBootstrapService.TENANT_PROJECT_NAME, null, "feeds");
        assertThat(hit).as("bundled feeds form must resolve").isPresent();
        return hit.get();
    }

    private static String resource() {
        try (InputStream in = FeedsSettingFormTest.class.getClassLoader()
                .getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("bundled setting form missing: " + RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("could not read " + RESOURCE, e);
        }
    }
}
