package de.mhus.vance.addon.brain.mastodon;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Guards the bundled {@code _vance/setting_forms/feeds-mastodon.yaml}.
 *
 * <p>Nothing in a normal build notices when a {@code showIf} stops compiling or
 * a field binds to a key the factory does not read — the form would look like
 * it worked and configure nothing. Running the real loader catches the first,
 * comparing against {@link CentauriSettings} the second.
 */
class MastodonSettingFormTest {

    private static final String RESOURCE =
            "vance-defaults/_vance/setting_forms/feeds-mastodon.yaml";
    private static final String TENANT = "acme";

    private final DocumentService documentService = mock(DocumentService.class);
    private final SettingFormLoader loader =
            new SettingFormLoader(documentService, new PromptTemplateRenderer());

    @Test
    void form_parsesThroughTheRealLoader() {
        ResolvedSettingForm form = load();

        assertThat(form.title()).containsKeys("de", "en");
        // Sources are tenant-wide: a feed in one project should not have to
        // re-configure the server the next project already has.
        assertThat(form.defaultScope()).isEqualTo("tenant");
    }

    @Test
    void form_bindsEveryFieldToACentauriEndpointKey() {
        assertThat(boundKeys()).isNotEmpty().allSatisfy(key ->
                assertThat(key).startsWith(CentauriSettings.PREFIX_ENDPOINT));
    }

    @Test
    void form_usesTheSuffixesTheFactoryActuallyReads() {
        // A typo in a suffix lands in the protocol's extras map and is silently
        // ignored — the failure mode this test exists for.
        List<String> known = List.of(
                CentauriSettings.SUFFIX_PROTOCOL,
                CentauriSettings.SUFFIX_BASE_URL,
                CentauriSettings.SUFFIX_API_KEY,
                CentauriSettings.SUFFIX_ENABLED);

        assertThat(boundKeys()).allSatisfy(key ->
                assertThat(known).anySatisfy(suffix -> assertThat(key).endsWith(suffix)));
    }

    @Test
    void form_pinsTheProtocolOfEverySlot() {
        // Without .protocol the factory skips the endpoint entirely, so the
        // protocol writes are what the toggles actually do.
        assertThat(protocolWrites())
                .hasSize(3)
                .allSatisfy(value -> assertThat(value).isEqualTo(MastodonFeedProtocol.ID));
    }

    @Test
    void form_offersThreeDistinctEndpointIds() {
        // Three fixed slots because the endpoint id is part of the KEY and a
        // form renders only values as templates. Two slots sharing an id would
        // silently overwrite each other.
        assertThat(endpointIds()).containsExactlyInAnyOrder(
                "mastodon-1", "mastodon-2", "mastodon-3");
    }

    @Test
    void form_defaultBaseUrlsAreSelectorsApart() {
        // mastodon.social refuses public: and serves hashtag:, so the two
        // pre-filled servers must not both be of that kind — otherwise a first
        // run of public:all shows nothing anywhere.
        assertThat(defaultsOf(CentauriSettings.SUFFIX_BASE_URL))
                .contains("https://mstdn.social");
    }

    // ── helpers ──────────────────────────────────────────────────────

    private List<String> boundKeys() {
        ResolvedSettingForm form = load();
        List<String> keys = new ArrayList<>();
        form.fields().forEach(field -> {
            if (field.getBindsTo() != null) {
                keys.add(field.getBindsTo().getKey());
            }
        });
        form.computedSettings().forEach(computed -> keys.add(computed.key()));
        return keys;
    }

    private List<String> protocolWrites() {
        List<String> values = new ArrayList<>();
        load().computedSettings().forEach(computed -> {
            if (computed.key().endsWith(CentauriSettings.SUFFIX_PROTOCOL)) {
                values.add(computed.valueTemplate());
            }
        });
        return values;
    }

    private List<String> endpointIds() {
        List<String> ids = new ArrayList<>();
        for (String key : boundKeys()) {
            String rest = key.substring(CentauriSettings.PREFIX_ENDPOINT.length());
            String id = rest.substring(0, rest.indexOf('.'));
            if (!ids.contains(id)) {
                ids.add(id);
            }
        }
        return ids;
    }

    private List<String> defaultsOf(String suffix) {
        List<String> values = new ArrayList<>();
        load().fields().forEach(field -> {
            if (field.getBindsTo() != null
                    && field.getBindsTo().getKey().endsWith(suffix)
                    && field.getDefaultValue() != null) {
                values.add(field.getDefaultValue());
            }
        });
        return values;
    }

    private ResolvedSettingForm load() {
        when(documentService.findByPath(eq(TENANT), any(), any())).thenReturn(Optional.empty());
        when(documentService.lookupCascade(eq(TENANT), any(), any()))
                .thenReturn(Optional.of(new LookupResult(
                        "_vance/setting_forms/feeds-mastodon.yaml", resource(),
                        LookupResult.Source.VANCE, null)));
        Optional<ResolvedSettingForm> hit = loader.load(
                TENANT, HomeBootstrapService.TENANT_PROJECT_NAME, null, "feeds-mastodon");
        assertThat(hit).as("bundled mastodon form must resolve").isPresent();
        return hit.get();
    }

    private static String resource() {
        try (InputStream in = MastodonSettingFormTest.class.getClassLoader()
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
