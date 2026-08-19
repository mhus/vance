package de.mhus.vance.brain.centauri;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.toolpack.feed.FeedActor;
import de.mhus.vance.toolpack.feed.FeedScope;
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

    @Test
    void pseudonym_isStableForTheSameReaderAndSource() {
        String first = FeedActorResolver.pseudonym("salt-a", "acme", "marvin");
        String second = FeedActorResolver.pseudonym("salt-a", "acme", "marvin");

        assertThat(first).isEqualTo(second);
        assertThat(first).hasSize(FeedActorResolver.PSEUDONYM_LENGTH);
    }

    @Test
    void pseudonym_differsPerSource_soTwoSourcesCannotJoinProfiles() {
        String atAlpha = FeedActorResolver.pseudonym("salt-alpha", "acme", "marvin");
        String atBeta = FeedActorResolver.pseudonym("salt-beta", "acme", "marvin");

        assertThat(atAlpha).isNotEqualTo(atBeta);
    }

    @Test
    void pseudonym_differsPerReaderAndPerTenant() {
        String marvin = FeedActorResolver.pseudonym("salt-a", "acme", "marvin");
        String trillian = FeedActorResolver.pseudonym("salt-a", "acme", "trillian");
        String otherTenant = FeedActorResolver.pseudonym("salt-a", "other", "marvin");

        assertThat(marvin).isNotEqualTo(trillian).isNotEqualTo(otherTenant);
    }

    @Test
    void pseudonym_cannotCollideByConcatenation() {
        // Without length prefixes ("ac" + "medata") and ("acme" + "data") would
        // hash the same message.
        assertThat(FeedActorResolver.pseudonym("s", "ac", "medata"))
                .isNotEqualTo(FeedActorResolver.pseudonym("s", "acme", "data"));
    }

    @Test
    void resolve_withoutUser_isAnonymousAndReadsNoSettings() {
        FeedActorResolver resolver = new FeedActorResolver(settings);

        FeedActor actor = resolver.resolve(FeedScope.of("acme", "proj"), "alpha");

        assertThat(actor).isNull();
        verifyNoInteractions(settings);
    }

    @Test
    void resolve_withSendActorOff_isAnonymous() {
        when(settings.getBooleanValueCascade(any(), any(), any(), anyString(), anyBoolean()))
                .thenReturn(false);
        FeedActorResolver resolver = new FeedActorResolver(settings);

        FeedActor actor = resolver.resolve(scopeWithUser(), "alpha");

        assertThat(actor).isNull();
    }

    @Test
    void resolve_withSaltPresent_yieldsThePseudonymOfThatSalt() {
        when(settings.getBooleanValueCascade(any(), any(), any(), anyString(), anyBoolean()))
                .thenReturn(true);
        when(settings.getDecryptedPasswordCascade(
                eq("acme"), eq("proj"), any(),
                eq(CentauriSettings.endpointActorSaltKey("alpha"))))
                .thenReturn("salt-alpha");
        FeedActorResolver resolver = new FeedActorResolver(settings);

        FeedActor actor = resolver.resolve(scopeWithUser(), "alpha");

        assertThat(actor).isNotNull();
        assertThat(actor.pseudonym())
                .isEqualTo(FeedActorResolver.pseudonym("salt-alpha", "acme", "marvin"));
    }

    @Test
    void resolve_whenSaltCannotBePersisted_fallsBackToAnonymous() {
        when(settings.getBooleanValueCascade(any(), any(), any(), anyString(), anyBoolean()))
                .thenReturn(true);
        when(settings.getDecryptedPasswordCascade(any(), any(), any(), anyString()))
                .thenReturn(null);
        when(settings.setEncryptedSecret(any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("no encryption key"));
        FeedActorResolver resolver = new FeedActorResolver(settings);

        // A source must answer without a pseudonym anyway, so a salt problem
        // degrades the view rather than failing the fetch.
        assertThat(resolver.resolve(scopeWithUser(), "alpha")).isNull();
    }

    private static FeedScope scopeWithUser() {
        return new FeedScope("acme", "proj", null, "marvin");
    }
}
