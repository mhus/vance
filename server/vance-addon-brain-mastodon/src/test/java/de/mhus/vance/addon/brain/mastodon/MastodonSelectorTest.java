package de.mhus.vance.addon.brain.mastodon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * The selector grammar is the whole reason this protocol needed the brain-side
 * {@code FREEFORM} path wired: every case below used to produce an empty stream
 * and no explanation.
 */
class MastodonSelectorTest {

    @Test
    void complain_acceptsAHashtagAndAPublicScope() {
        assertThat(MastodonSelector.complain("hashtag:opensource")).isEmpty();
        assertThat(MastodonSelector.complain("public:local")).isEmpty();
        assertThat(MastodonSelector.complain("public:remote")).isEmpty();
        assertThat(MastodonSelector.complain("public:all")).isEmpty();
    }

    @Test
    void complain_acceptsNonAsciiTags() {
        // #Grüße and #日本 are valid Mastodon tags; an [A-Za-z0-9_] check would
        // refuse what the server happily indexes.
        assertThat(MastodonSelector.complain("hashtag:Grüße")).isEmpty();
        assertThat(MastodonSelector.complain("hashtag:日本")).isEmpty();
        assertThat(MastodonSelector.complain("hashtag:vance_tope2")).isEmpty();
    }

    @Test
    void complain_toleratesSurroundingWhitespaceAndCase() {
        assertThat(MastodonSelector.complain("  HashTag:Linux  ")).isEmpty();
        assertThat(MastodonSelector.parse("  HashTag:Linux  ").value()).isEqualTo("Linux");
        // The scope is vocabulary, so it is normalised; a tag is data and keeps
        // its case for the URL.
        assertThat(MastodonSelector.parse("PUBLIC:Local").value()).isEqualTo("local");
    }

    @Test
    void complain_theTrailingSpaceCase() {
        // The exact failure the SPI doc names: "somebody types a tag with a
        // trailing space and gets an empty stream and no explanation".
        assertThat(MastodonSelector.complain("hashtag:foo ")).isEmpty();
        assertThat(MastodonSelector.parse("hashtag:foo ").value()).isEqualTo("foo");
    }

    @Test
    void complain_rejectsEmpty() {
        assertThat(MastodonSelector.complain(null)).get().asString().contains("must not be empty");
        assertThat(MastodonSelector.complain("   ")).isPresent();
    }

    @Test
    void complain_rejectsAHashPrefixWithTheFix() {
        // The most likely typo gets the specific answer, not the generic one.
        assertThat(MastodonSelector.complain("#linux")).get().asString()
                .contains("hashtag:linux");
        assertThat(MastodonSelector.complain("hashtag:#linux")).get().asString()
                .contains("omit the '#'");
    }

    @Test
    void complain_rejectsASpaceInsideATag() {
        assertThat(MastodonSelector.complain("hashtag:free software")).get().asString()
                .contains("letters, digits and underscore");
    }

    @Test
    void complain_rejectsADigitOnlyTag() {
        assertThat(MastodonSelector.complain("hashtag:2026")).get().asString()
                .contains("no letter");
    }

    @Test
    void complain_rejectsAnUnknownPublicScope() {
        assertThat(MastodonSelector.complain("public:everything")).get().asString()
                .contains("use all, local or remote");
        assertThat(MastodonSelector.complain("public:")).get().asString().contains("needs a scope");
    }

    @Test
    void complain_rejectsAnUnprefixedSelector() {
        // "news" is a plausible hashtag and a plausible slip for public:local.
        // Guessing would silently show a stranger's timeline.
        assertThat(MastodonSelector.complain("news")).get().asString()
                .contains("unknown selector kind 'news'");
    }

    @Test
    void complain_namesAccountAsUnknownRatherThanAcceptingIt() {
        // Not supported in v1 (needs accounts/lookup); being refused by name is
        // better than being accepted and returning nothing.
        assertThat(MastodonSelector.complain("account:@someone@example.social"))
                .get().asString().contains("unknown selector kind 'account'");
    }

    @Test
    void parse_guardsAgainstUnvalidatedInput() {
        assertThatThrownBy(() -> MastodonSelector.parse("nonsense"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parse_splitsKindFromValue() {
        MastodonSelector tag = MastodonSelector.parse("hashtag:opensource");
        assertThat(tag.kind()).isEqualTo(MastodonSelector.Kind.HASHTAG);
        assertThat(tag.value()).isEqualTo("opensource");

        MastodonSelector firehose = MastodonSelector.parse("public:remote");
        assertThat(firehose.kind()).isEqualTo(MastodonSelector.Kind.PUBLIC);
        assertThat(firehose.value()).isEqualTo("remote");
    }
}
