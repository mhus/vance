package de.mhus.vance.addon.brain.bistromath;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.toolpack.ToolException;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * {@code _vance/config/applications.yaml} — parsing and resolution.
 *
 * <p>This is the file that decides whether custom code runs, so what is tested
 * here is mostly the direction of every mistake: a broken or ambiguous document
 * must land on the restrictive side, and a rule that reads like a restriction
 * must never be silently inert.
 */
class ApplicationsConfigTest {

    private static ApplicationsConfig parse(String yaml) {
        return ApplicationsConfig.parse(new Yaml().load(yaml));
    }

    // ── the default ───────────────────────────────────────────────────

    @Test
    void missingDocument_forbidsEverything() {
        // A powerful feature is opt-in; the absence of a file is not a quiet yes.
        assertThat(ApplicationsConfig.missing().resolve("p", "apps/x").mode())
                .isEqualTo(AppMode.FORBIDDEN);
    }

    @Test
    void documentWithoutADefault_forbids() {
        ApplicationsConfig c = parse("projects:\n  other: allowed\n");

        assertThat(c.resolve("p", "apps/x").mode()).isEqualTo(AppMode.FORBIDDEN);
    }

    // ── opening inwards ───────────────────────────────────────────────

    @Test
    void aProjectMayOpenWhatTheGlobalDefaultForbids() {
        // The load-bearing case: "no apps, except where the developers sit."
        // Safe because only a tenant admin writes this file — there is no
        // project-level document that could do the same.
        ApplicationsConfig c = parse("""
                default: forbidden
                projects:
                  playground: allowed
                """);

        assertThat(c.resolve("playground", "apps/x").mode()).isEqualTo(AppMode.ALLOWED);
        assertThat(c.resolve("other", "apps/x").mode()).isEqualTo(AppMode.FORBIDDEN);
    }

    @Test
    void oneAppMayOpenWhatItsProjectForbids() {
        ApplicationsConfig c = parse("""
                default: forbidden
                apps:
                  p/apps/blessed: allowed
                """);

        assertThat(c.resolve("p", "apps/blessed").mode()).isEqualTo(AppMode.ALLOWED);
        assertThat(c.resolve("p", "apps/other").mode()).isEqualTo(AppMode.FORBIDDEN);
    }

    @Test
    void anAppMayAlsoCloseWhatItsProjectOpened() {
        ApplicationsConfig c = parse("""
                default: allowed
                apps:
                  p/apps/experimental/: forbidden
                """);

        assertThat(c.resolve("p", "apps/experimental/thing").mode())
                .isEqualTo(AppMode.FORBIDDEN);
        assertThat(c.resolve("p", "apps/normal").mode()).isEqualTo(AppMode.ALLOWED);
    }

    // ── prefix matching ───────────────────────────────────────────────

    @Test
    void longestPrefixWins() {
        ApplicationsConfig c = parse("""
                default: forbidden
                apps:
                  p/apps: restricted
                  p/apps/invoices: allowed
                """);

        assertThat(c.resolve("p", "apps/invoices").mode()).isEqualTo(AppMode.ALLOWED);
        assertThat(c.resolve("p", "apps/other").mode()).isEqualTo(AppMode.RESTRICTED);
    }

    @Test
    void trailingSlashesDoNotDecideAMatch() {
        // Normalised on the way in, so the file and the lookup key cannot
        // disagree about whether they mean the same folder.
        ApplicationsConfig c = parse("default: forbidden\napps:\n  \"p/apps/x/\": allowed\n");

        assertThat(c.resolve("p", "/apps/x").mode()).isEqualTo(AppMode.ALLOWED);
        assertThat(c.resolve("p", "apps/x/").mode()).isEqualTo(AppMode.ALLOWED);
    }

    @Test
    void aPrefixDoesNotLeakIntoAnotherProject() {
        ApplicationsConfig c = parse("default: forbidden\napps:\n  p/apps: allowed\n");

        assertThat(c.resolve("other", "apps/x").mode()).isEqualTo(AppMode.FORBIDDEN);
    }

    // ── restricted ────────────────────────────────────────────────────

    @Test
    void restrictedWithoutAListMeansNoRest() {
        // Not "everything": inventing a set would be guessing at what the admin
        // meant, and guessing wide is the expensive direction.
        ApplicationsConfig c = parse("default: restricted\n");

        assertThat(c.resolve("p", "apps/x").restFamilies()).isEmpty();
    }

    @Test
    void restrictedWithAListCarriesIt() {
        ApplicationsConfig c = parse("""
                default:
                  mode: restricted
                  rest: [documents, Inbox]
                """);

        // Lower-cased on the way in — the client compares the first path
        // segment, and that comparison should not depend on how it was typed.
        assertThat(c.resolve("p", "apps/x").restFamilies())
                .containsExactly("documents", "inbox");
    }

    @Test
    void allowedCarriesNoList() {
        assertThat(parse("default: allowed\n").resolve("p", "apps/x").restFamilies()).isNull();
    }

    // ── refusals ──────────────────────────────────────────────────────

    @Test
    void aRouteListUnderAllowedIsRefused() {
        // The worst way for a policy file to be wrong is a restriction that
        // looks applied and is not.
        assertThatThrownBy(() -> parse("default:\n  mode: allowed\n  rest: [documents]\n"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("only applies to `restricted`");
    }

    @Test
    void anUnknownModeIsRefusedRatherThanIgnored() {
        assertThatThrownBy(() -> parse("default: mostly\n"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("expected forbidden, restricted or allowed");
    }

    @Test
    void aMappingWithoutAModeIsRefused() {
        assertThatThrownBy(() -> parse("default:\n  rest: [documents]\n"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("needs a `mode`");
    }

    @Test
    void aNonMappingDocumentIsRefused() {
        assertThatThrownBy(() -> parse("just a scalar"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("mapping at the top level");
    }

    @Test
    void aNonListRestIsRefused() {
        assertThatThrownBy(() -> parse("default:\n  mode: restricted\n  rest: documents\n"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("is not a list");
    }

    // ── the two capability levers ─────────────────────────────────────

    @Test
    void restricted_withholdsASurfaceByDefault() {
        // The one lever with a phishing shape: an app that may paint arbitrary
        // pixels can paint something that looks like Vance asking for a
        // password. The restrictive reading is right, and an admin who wants it
        // says so.
        assertThat(parse("default: restricted\n").resolve("p", "apps/x").surface()).isFalse();
    }

    @Test
    void restricted_keepsDocumentWritesByDefault() {
        // The opposite default, on purpose: taking an app's own data away would
        // make a bare `restricted` mean "broken" for every register-shaped app,
        // and the admin would have no idea why.
        assertThat(parse("default: restricted\n").resolve("p", "apps/x").documentsWritable())
                .isTrue();
    }

    @Test
    void allowed_hasBoth() {
        AppPolicy p = parse("default: allowed\n").resolve("p", "apps/x");

        assertThat(p.surface()).isTrue();
        assertThat(p.documentsWritable()).isTrue();
    }

    @Test
    void bothLeversCanBeSetExplicitly() {
        AppPolicy p = parse("""
                default:
                  mode: restricted
                  surface: true
                  documents: read
                """).resolve("p", "apps/x");

        assertThat(p.surface()).isTrue();
        assertThat(p.documentsWritable()).isFalse();
    }

    @Test
    void documentsTakesAWordNotABoolean() {
        // `documents: false` would read as "no documents at all", which is not
        // what it means — reading stays open either way.
        assertThatThrownBy(() -> parse("default:\n  mode: restricted\n  documents: false\n"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("expected `read` or `write`");
    }

    @Test
    void surfaceTakesABoolean() {
        assertThatThrownBy(() -> parse("default:\n  mode: restricted\n  surface: maybe\n"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("is true or false");
    }

    @Test
    void forbidden_reportsBothLeversClosed() {
        // Moot, since nothing runs — but a caller that reads a flag without
        // checking the mode first should land on the closed side.
        AppPolicy p = ApplicationsConfig.missing().resolve("x", "y");

        assertThat(p.surface()).isFalse();
        assertThat(p.documentsWritable()).isFalse();
    }

    @Test
    void forbidden_writtenOut_reportsTheSameClosedLevers() {
        // The same value spelled two ways has to *be* the same value. Derived
        // from `!restricted`, a written-out `forbidden` came out with
        // surface=true and documentsWritable=true — the open answer for the
        // most closed mode, which is exactly the misreading the flags are
        // supposed to protect a mode-blind caller from.
        for (String yaml : new String[] {
                "default: forbidden\n",
                "default:\n  mode: forbidden\n"}) {
            AppPolicy p = parse(yaml).resolve("p", "apps/x");

            assertThat(p.mode()).isEqualTo(AppMode.FORBIDDEN);
            assertThat(p.surface()).as(yaml).isFalse();
            assertThat(p.documentsWritable()).as(yaml).isFalse();
        }
    }

    @Test
    void appPrefixStopsAtASegmentBoundary() {
        // A character prefix would have let a project member open a surface by
        // naming a folder the admin never wrote down: `apps/invoices` also
        // covering `apps/invoices-scratch`. The subtree still matches, which is
        // the point of a prefix.
        ApplicationsConfig c = parse("""
                default: forbidden
                apps:
                  p/apps/invoices: allowed
                """);

        assertThat(c.resolve("p", "apps/invoices").mode()).isEqualTo(AppMode.ALLOWED);
        assertThat(c.resolve("p", "apps/invoices/reports").mode()).isEqualTo(AppMode.ALLOWED);
        assertThat(c.resolve("p", "apps/invoices-scratch").mode()).isEqualTo(AppMode.FORBIDDEN);
    }
}
