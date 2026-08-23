package de.mhus.vance.brain.centauri;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.sourceconfig.SourceConfig;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.toolpack.core.SecretResolver;
import de.mhus.vance.toolpack.feed.FeedActor;
import de.mhus.vance.toolpack.feed.FeedScope;
import de.mhus.vance.toolpack.feed.FeedSourceInstance;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FeedActorResolverTest {

    @Mock
    private SettingService settings;

    @Mock
    private FeedSourceFactory sourceFactory;

    @Test
    void pseudonym_isStableForTheSameReaderAndSource() {
        String first = FeedActorResolver.pseudonym(
                "salt-a", "acme", "marvin", "https://alpha.test/");
        String second = FeedActorResolver.pseudonym(
                "salt-a", "acme", "marvin", "https://alpha.test/");

        assertThat(first).isEqualTo(second);
        assertThat(first).hasSize(FeedActorResolver.PSEUDONYM_LENGTH);
    }

    @Test
    void pseudonym_differsPerSource_soTwoSourcesCannotJoinProfiles() {
        String atAlpha = FeedActorResolver.pseudonym(
                "salt-alpha", "acme", "marvin", "https://alpha.test/");
        String atBeta = FeedActorResolver.pseudonym(
                "salt-beta", "acme", "marvin", "https://beta.test/");

        assertThat(atAlpha).isNotEqualTo(atBeta);
    }

    @Test
    void pseudonym_differsPerEndpointEvenWithTheSameSalt() {
        // Two projects may both name an endpoint "news" and mean two entirely
        // different organisations, while the salt lives tenant-wide. With the
        // endpoint out of the derivation both would have seen the same reader
        // under the same pseudonym and could have joined their profiles — the
        // one property §6 calls the actual decision.
        String atA = FeedActorResolver.pseudonym(
                "shared-salt", "acme", "marvin", "https://a.example/");
        String atB = FeedActorResolver.pseudonym(
                "shared-salt", "acme", "marvin", "https://b.example/");

        assertThat(atA).isNotEqualTo(atB);
    }

    @Test
    void pseudonym_differsPerReaderAndPerTenant() {
        String marvin = FeedActorResolver.pseudonym("salt-a", "acme", "marvin", "https://a/");
        String trillian = FeedActorResolver.pseudonym("salt-a", "acme", "trillian", "https://a/");
        String otherTenant = FeedActorResolver.pseudonym("salt-a", "other", "marvin", "https://a/");

        assertThat(marvin).isNotEqualTo(trillian).isNotEqualTo(otherTenant);
    }

    @Test
    void pseudonym_cannotCollideByConcatenation() {
        // Without length prefixes ("ac" + "medata") and ("acme" + "data") would
        // hash the same message.
        assertThat(FeedActorResolver.pseudonym("s", "ac", "medata", "https://a/"))
                .isNotEqualTo(FeedActorResolver.pseudonym("s", "acme", "data", "https://a/"));
    }

    @Test
    void resolve_withoutUser_isAnonymousAndReadsNoSettings() {
        FeedActorResolver resolver = resolver();

        FeedActor actor = resolver.resolve(FeedScope.of("acme", "proj"), source("alpha"));

        assertThat(actor).isNull();
        verifyNoInteractions(settings, sourceFactory);
    }

    @Test
    void resolve_withSendActorOff_isAnonymous() {
        when(sourceFactory.config(any(), anyString()))
                .thenReturn(config(Map.of("sendActor", false)));

        FeedActor actor = resolver().resolve(scopeWithUser(), source("alpha"));

        assertThat(actor).isNull();
    }

    @Test
    void resolve_withSaltPresent_yieldsThePseudonymOfThatSaltAndEndpoint() {
        givenSendActorOn();
        when(settings.getDecryptedPasswordCascade(
                eq("acme"), eq("proj"), any(),
                eq(CentauriSettings.endpointActorSaltKey("alpha"))))
                .thenReturn("salt-alpha");

        FeedActor actor = resolver().resolve(scopeWithUser(), source("alpha"));

        assertThat(actor).isNotNull();
        assertThat(actor.pseudonym()).isEqualTo(FeedActorResolver.pseudonym(
                "salt-alpha", "acme", "marvin", "https://alpha.test/"));
    }

    @Test
    void resolve_saltHoldingASecretReference_resolvesItRatherThanSigningWithTheText() {
        // Spec §10: a feed protocol is a connector, so a stored `{{secret:…}}`
        // is meant to be resolved. Signing with the literal reference produces
        // a stable, plausible-looking pseudonym that is simply the wrong one.
        givenSendActorOn();
        when(settings.getDecryptedPasswordCascade(any(), any(), any(), anyString()))
                .thenReturn("{{secret:vault:feeds.alpha.salt}}");
        SecretResolver vault = (input, ctx) ->
                "{{secret:vault:feeds.alpha.salt}}".equals(input) ? "salt-from-vault" : input;

        FeedActor actor = new FeedActorResolver(settings, vault, sourceFactory)
                .resolve(scopeWithUser(), source("alpha"));

        assertThat(actor).isNotNull();
        assertThat(actor.pseudonym()).isEqualTo(FeedActorResolver.pseudonym(
                "salt-from-vault", "acme", "marvin", "https://alpha.test/"));
    }

    @Test
    void resolve_whenSaltCannotBePersisted_fallsBackToAnonymous() {
        givenSendActorOn();
        when(settings.getDecryptedPasswordCascade(any(), any(), any(), anyString()))
                .thenReturn(null);
        when(settings.setEncryptedSecret(any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("no encryption key"));

        // A source must answer without a pseudonym anyway, so a salt problem
        // degrades the view rather than failing the fetch.
        assertThat(resolver().resolve(scopeWithUser(), source("alpha"))).isNull();
    }

    @Test
    void resolve_concurrentStreamsOfOneSource_generateOneSaltAndOnePseudonym() throws Exception {
        // Every stream of a page resolves on its own virtual thread. Without a
        // loader that settles the generation, each of them minted its own
        // 32-byte salt and overwrote the others — the four streams of one
        // source then travelled under four different pseudonyms in a single
        // request, and the source saw four readers.
        givenSendActorOn();
        when(settings.getDecryptedPasswordCascade(any(), any(), any(), anyString()))
                .thenReturn(null);
        FeedActorResolver resolver = resolver();
        FeedSourceInstance alpha = source("alpha");

        List<Callable<String>> calls = IntStream.range(0, 8)
                .mapToObj(i -> (Callable<String>) () -> {
                    FeedActor actor = resolver.resolve(scopeWithUser(), alpha);
                    return actor == null ? "anonymous" : actor.pseudonym();
                })
                .collect(Collectors.toList());

        List<String> pseudonyms;
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<String>> futures = pool.invokeAll(calls);
            pseudonyms = new java.util.ArrayList<>();
            for (Future<String> f : futures) {
                pseudonyms.add(f.get());
            }
        }

        assertThat(pseudonyms).doesNotContain("anonymous").hasSize(8);
        assertThat(new java.util.HashSet<>(pseudonyms)).hasSize(1);
        verify(settings, times(1))
                .setEncryptedSecret(any(), any(), any(), any(), any(), any());
    }

    // ── helpers ──────────────────────────────────────────────────────

    private FeedActorResolver resolver() {
        return new FeedActorResolver(settings, SecretResolver.PASSTHROUGH, sourceFactory);
    }

    /** sendActor is on by default, so the plain document is the "on" case. */
    private void givenSendActorOn() {
        when(sourceFactory.config(any(), anyString())).thenReturn(config(Map.of()));
    }

    private static SourceConfig config(Map<String, Object> extras) {
        return new SourceConfig(
                "alpha", "_vance/config/feeds/alpha.yaml", "ode",
                "https://alpha.test/", null, true, extras);
    }

    private static FeedSourceInstance source(String id) {
        return new FakeFeedSource(id);
    }

    private static FeedScope scopeWithUser() {
        return new FeedScope("acme", "proj", null, "marvin");
    }
}
