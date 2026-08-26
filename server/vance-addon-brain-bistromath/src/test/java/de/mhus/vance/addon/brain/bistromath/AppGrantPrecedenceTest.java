package de.mhus.vance.addon.brain.bistromath;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * How a granted release and the hand-written file relate.
 *
 * <p>The rule under test is one sentence: the hand-written file wins wherever it
 * **names** the app, and a grant only fills in where it says nothing. Everything
 * else follows from it — most importantly that revoking is naming the app rather
 * than hunting through machine-written bookkeeping.
 */
class AppGrantPrecedenceTest {

    private static ApplicationsConfig config(String yaml) {
        return ApplicationsConfig.parse(new Yaml().load(yaml));
    }

    private static AppGrantRecord granted() {
        return new AppGrantRecord(AppGrantRecord.Status.GRANTED, AppMode.RESTRICTED,
                List.of("documents"), false, true,
                "asker", "2026-01-01T00:00:00Z", "item", "admin", "2026-01-02T00:00:00Z");
    }

    @Test
    void aGrantFillsInWhereTheFileSaysNothingAboutTheApp() {
        ApplicationsConfig c = config("default: forbidden\n");

        assertThat(c.explicitAppRule("p", "apps/x")).isNull();
        assertThat(granted().grantedPolicy()).isNotNull();
    }

    @Test
    void theHandWrittenFileWinsWhenItNamesTheApp() {
        // Revocation: naming the app is how an admin takes a grant back, and it
        // has to beat the grant or there would be no way to undo an approval
        // short of editing the machine's own file.
        ApplicationsConfig c = config("""
                default: forbidden
                apps:
                  p/apps/x: forbidden
                """);

        assertThat(c.explicitAppRule("p", "apps/x")).isNotNull();
        assertThat(c.explicitAppRule("p", "apps/x").mode()).isEqualTo(AppMode.FORBIDDEN);
    }

    @Test
    void aProjectRuleDoesNotCountAsNamingTheApp() {
        // Otherwise a project-wide `forbidden` would make every grant in that
        // project inert, and asking would be pointless everywhere — which is the
        // opposite of what a per-app release is for.
        ApplicationsConfig c = config("""
                default: allowed
                projects:
                  p: forbidden
                """);

        assertThat(c.explicitAppRule("p", "apps/x")).isNull();
    }

    @Test
    void onlyAnApprovedRecordGrantsAnything() {
        for (AppGrantRecord.Status status : AppGrantRecord.Status.values()) {
            AppGrantRecord r = new AppGrantRecord(status, AppMode.RESTRICTED, List.of(),
                    false, true, null, null, null, null, null);
            assertThat(r.grantedPolicy() != null)
                    .as("status %s grants", status)
                    .isEqualTo(status == AppGrantRecord.Status.GRANTED);
        }
    }

    @Test
    void anOpenRequestIsOnlyTheRequestedOne() {
        // `open` drives the dedup: a second click attaches to the waiting
        // request instead of raising another thread.
        assertThat(new AppGrantRecord(AppGrantRecord.Status.REQUESTED, AppMode.RESTRICTED,
                null, false, true, null, null, null, null, null).open()).isTrue();
        assertThat(granted().open()).isFalse();
    }

    @Test
    void aGrantCarriesTheFrozenProposal() {
        // Not re-read from the app at approval time: an app that widened its
        // declaration between asking and being answered must not be approved
        // for the wider thing.
        AppPolicy p = granted().grantedPolicy();

        assertThat(p.mode()).isEqualTo(AppMode.RESTRICTED);
        assertThat(p.restFamilies()).containsExactly("documents");
        assertThat(p.surface()).isFalse();
        assertThat(p.documentsWritable()).isTrue();
    }

    @Test
    void requestsRecipientIsReadFromTheFile() {
        assertThat(config("default: forbidden\nrequests:\n  to: admin\n").requestsTo())
                .isEqualTo("admin");
        assertThat(config("default: forbidden\n").requestsTo()).isNull();
    }
}
