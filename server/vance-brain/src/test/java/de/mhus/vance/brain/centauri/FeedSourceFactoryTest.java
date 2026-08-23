package de.mhus.vance.brain.centauri;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.sourceconfig.SourceConfig;
import de.mhus.vance.brain.sourceconfig.SourceConfigLoader;
import de.mhus.vance.brain.sourceconfig.SourceConfigPaths;
import de.mhus.vance.toolpack.core.SecretResolver;
import de.mhus.vance.toolpack.feed.FeedInstanceConfig;
import de.mhus.vance.toolpack.feed.FeedProtocol;
import de.mhus.vance.toolpack.feed.FeedScope;
import de.mhus.vance.toolpack.feed.FeedSourceInstance;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Source configurations → feed instances.
 *
 * <p>Two properties are load-bearing and easy to break: a disabled endpoint is
 * still <em>known</em> (the gate reads the same configuration, and the config
 * UI has to show what exists), and the credential is resolved per call rather
 * than captured, so a rotated secret does not wait for the cache to expire.
 */
class FeedSourceFactoryTest {

    private static final FeedScope SCOPE = FeedScope.of("acme", "research");

    private SourceConfigLoader configLoader;
    private SecretResolver secretResolver;
    private FeedProtocol protocol;
    private FeedSourceFactory factory;

    @BeforeEach
    void setUp() {
        configLoader = mock(SourceConfigLoader.class);
        secretResolver = SecretResolver.PASSTHROUGH;
        factory = newFactory();
    }

    private FeedSourceFactory newFactory() {
        protocol = mock(FeedProtocol.class);
        when(protocol.id()).thenReturn("ode");
        when(protocol.instantiate(any(FeedInstanceConfig.class))).thenAnswer(inv -> {
            FeedInstanceConfig cfg = inv.getArgument(0);
            FeedSourceInstance instance = mock(FeedSourceInstance.class);
            when(instance.id()).thenReturn(cfg.instanceId());
            when(instance.baseUrl()).thenReturn(cfg.baseUrl());
            return instance;
        });
        return new FeedSourceFactory(configLoader, secretResolver, List.of(protocol));
    }

    private void given(SourceConfig... configs) {
        when(configLoader.load(eq("acme"), eq("research"), anyString()))
                .thenReturn(List.of(configs));
    }

    private static SourceConfig config(
            String name, String protocol, String apiKey, boolean enabled,
            Map<String, Object> extras) {
        return new SourceConfig(
                name, SourceConfigPaths.pathFor(SourceConfigPaths.FEEDS, name),
                protocol, "https://" + name + ".test", apiKey, enabled, extras);
    }

    @Test
    void assemble_buildsOneInstancePerDocument() {
        given(config("alpha", "ode", null, true, Map.of()),
                config("beta", "ode", null, true, Map.of()));

        assertThat(factory.assemble(SCOPE))
                .extracting(FeedSourceInstance::id)
                .containsExactly("alpha", "beta");
    }

    @Test
    void assemble_unknownProtocolIsSkipped_theOthersSurvive() {
        given(config("alpha", "does-not-exist", null, true, Map.of()),
                config("beta", "ode", null, true, Map.of()));

        assertThat(factory.assemble(SCOPE))
                .extracting(FeedSourceInstance::id)
                .containsExactly("beta");
    }

    @Test
    void assemble_documentWithoutProtocolIsSkipped() {
        given(config("alpha", null, null, true, Map.of()));

        assertThat(factory.assemble(SCOPE)).isEmpty();
    }

    @Test
    void disabledEndpoint_isStillInstantiatedAndStillKnown() {
        // The gate decides whether to dispatch; the factory's job is to know
        // what exists. Dropping it here would make a disabled source invisible
        // to the configuration UI as well.
        given(config("alpha", "ode", null, false, Map.of()));

        assertThat(factory.assemble(SCOPE)).extracting(FeedSourceInstance::id)
                .containsExactly("alpha");
        assertThat(factory.config(SCOPE, "alpha")).isNotNull()
                .satisfies(c -> assertThat(c.enabled()).isFalse());
    }

    @Test
    void config_forAnUnknownEndpoint_isNull() {
        given(config("alpha", "ode", null, true, Map.of()));

        assertThat(factory.config(SCOPE, "nope")).isNull();
    }

    @Test
    void credential_literalIsHandedBackWithoutThePrefix() {
        given(config("alpha", "ode", "{noop}sk-abc123", true, Map.of()));

        assertThat(capturedConfig("alpha").credential()).isEqualTo("sk-abc123");
    }

    @Test
    void credential_isResolvedPerCall_soRotationDoesNotWaitForTheCache() {
        List<String> resolved = new ArrayList<>();
        secretResolver = (input, ctx) -> {
            resolved.add(input);
            return "resolved-" + resolved.size();
        };
        factory = newFactory();
        given(config("alpha", "ode", "{{secret:vault:alpha}}", true, Map.of()));

        FeedInstanceConfig cfg = capturedConfig("alpha");

        assertThat(cfg.credential()).isEqualTo("resolved-1");
        assertThat(cfg.credential()).isEqualTo("resolved-2");
        assertThat(resolved).containsExactly("{{secret:vault:alpha}}", "{{secret:vault:alpha}}");
    }

    @Test
    void credentialSettingKey_namesTheDocumentAndField() {
        given(config("alpha", "ode", null, true, Map.of()));

        assertThat(capturedConfig("alpha").credentialSettingKey())
                .isEqualTo("_vance/config/feeds/alpha.yaml#apiKey");
    }

    @Test
    void sendActor_isNotHandedToTheProtocol() {
        // Whether the reader pseudonym travels is Centauri's decision, and the
        // point of deriving it centrally is that no protocol has a say.
        given(config("alpha", "ode", null, true,
                Map.of("sendActor", false, "feedPath", "/ode/feed")));

        FeedInstanceConfig cfg = capturedConfig("alpha");

        assertThat(cfg.extras()).containsOnlyKeys("feedPath");
        assertThat(factory.config(SCOPE, "alpha").extraBoolean("sendActor", true)).isFalse();
    }

    /** The {@link FeedInstanceConfig} the protocol was handed for {@code id}. */
    private FeedInstanceConfig capturedConfig(String id) {
        factory.assemble(SCOPE);
        ArgumentCaptor<FeedInstanceConfig> captor =
                ArgumentCaptor.forClass(FeedInstanceConfig.class);
        verify(protocol, atLeastOnce()).instantiate(captor.capture());
        return captor.getAllValues().stream()
                .filter(c -> c.instanceId().equals(id))
                .findFirst()
                .orElseThrow();
    }
}
