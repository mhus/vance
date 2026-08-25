package de.mhus.vance.addon.brain.bistromath;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.LookupResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The load list is assembled from three places that all spell it differently
 * and is written down in none of them. These tests are the written-down part.
 */
class RequireResolverTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "p1";
    private static final String FOLDER = "apps/mine";

    private final DocumentService documents = mock(DocumentService.class);
    private final RequireResolver resolver = new RequireResolver(documents);

    /** path → content, for both the folder listing and the cascade. */
    private final Map<String, String> world = new LinkedHashMap<>();
    @BeforeEach
    void wire() {
        when(documents.findByPath(eq(TENANT), eq(PROJECT), any())).thenAnswer(inv -> {
            String path = inv.getArgument(2);
            if (!world.containsKey(path)) return Optional.empty();
            return Optional.of(DocumentDocument.builder().path(path).build());
        });
        when(documents.readContent(any(DocumentDocument.class)))
                .thenAnswer(inv -> world.getOrDefault(
                        ((DocumentDocument) inv.getArgument(0)).getPath(), ""));
        when(documents.listByPrefixCascade(eq(TENANT), eq(PROJECT), any())).thenAnswer(inv -> {
            String prefix = ((String) inv.getArgument(2)).replaceAll("/+$", "") + "/";
            Map<String, LookupResult> out = new LinkedHashMap<>();
            for (String path : world.keySet()) {
                if (path.startsWith(prefix) && !path.substring(prefix.length()).contains("/")) {
                    out.put(path, new LookupResult(path, world.get(path),
                            LookupResult.Source.PROJECT, null));
                }
            }
            return out;
        });
        when(documents.lookupCascade(eq(TENANT), eq(PROJECT), any())).thenAnswer(inv -> {
            String path = inv.getArgument(2);
            if (!world.containsKey(path)) return Optional.empty();
            return Optional.of(new LookupResult(path, world.get(path),
                    LookupResult.Source.PROJECT, null));
        });
    }

    private void doc(String path, String content) {
        world.put(path, content);
    }

    /** A file that declares itself part of the app, via its header marker. */
    private void script(String path, String content) {
        world.put(path, "// @" + BistromathConfig.SCRIPT_MARKER + "\n" + content);
    }

    private void library(String name, String content) {
        world.put(RequireResolver.LIBRARY_PREFIX + name + ".js", content);
    }

    private RequireReport resolve(BistromathConfig config, ViewRef... views) {
        return resolver.resolve(TENANT, PROJECT, FOLDER, config, List.of(views),
                FOLDER + "/main.js");
    }

    private static BistromathConfig config(String... required) {
        return new BistromathConfig(null, null, List.of(required), null);
    }

    // ── the three sources ────────────────────────────────────────────

    @Test
    void manifest_viewAndHeader_allContribute() {
        library("a@1", "");
        library("b@1", "");
        library("c@1", "");
        doc(FOLDER + "/main.js", "// @require c@1\nfunction hallo() {}\n");
        doc(FOLDER + "/main.yaml", "required: [b@1]\ntype: page\n");

        RequireReport r = resolve(config("a@1"), new ViewRef("main", FOLDER + "/main.yaml", null));

        assertThat(r.missing()).isEmpty();
        assertThat(names(r)).containsExactlyInAnyOrder("a", "b", "c");
    }

    /**
     * A library's own header is what makes the graph transitive, and the order
     * has to follow: nothing may be evaluated before what it needs.
     */
    @Test
    void libraryRequire_isTransitiveAndLoadsAfterItsDependency() {
        library("db@1", "// @require core@1\n");
        library("core@1", "");
        doc(FOLDER + "/main.js", "// @require db@1\n");

        RequireReport r = resolve(config());

        assertThat(paths(r)).containsExactly(
                "_vance/app-libs/core@1.js",
                "_vance/app-libs/db@1.js",
                FOLDER + "/main.js");
    }

    @Test
    void sameLibraryTwice_loadsOnce() {
        library("core@1", "");
        doc(FOLDER + "/main.js", "// @require core@1\n");
        doc(FOLDER + "/main.yaml", "required: [core@1]\ntype: page\n");

        RequireReport r = resolve(config("core@1"),
                new ViewRef("main", FOLDER + "/main.yaml", null));

        assertThat(paths(r)).containsExactly("_vance/app-libs/core@1.js", FOLDER + "/main.js");
        assertThat(r.warnings()).isEmpty();
    }

    // ── conflicts and gaps ───────────────────────────────────────────

    /**
     * Two versions cannot both load into one global scope, so the choice is
     * between refusing to run and picking one. The highest is the useful guess
     * and the warning is the whole mitigation — it has to name both versions
     * *and* who asked, or the author cannot act on it.
     */
    @Test
    void twoVersions_takeTheHighestAndSayWhoAskedForWhat() {
        library("db@2", "");
        doc(FOLDER + "/main.js", "// @require db@1\n");

        RequireReport r = resolve(config("db@2"));

        assertThat(paths(r)).containsExactly("_vance/app-libs/db@2.js", FOLDER + "/main.js");
        assertThat(r.warnings()).hasSize(1);
        assertThat(r.warnings().get(0))
                .contains("db@1").contains("db@2")
                .contains(FOLDER + "/main.js")
                .contains(FOLDER + "/_app.yaml");
    }

    @Test
    void missingLibrary_isReportedWithThePathItLookedAt() {
        doc(FOLDER + "/main.js", "// @require ghost@3\n");

        RequireReport r = resolve(config());

        assertThat(r.missing()).hasSize(1);
        assertThat(r.missing().get(0))
                .contains("ghost@3")
                .contains("_vance/app-libs/ghost@3.js")
                .contains(FOLDER + "/main.js");
        // The app still loads: a missing library is a broken program, not a
        // broken app, and refusing to start would hide which of the two it is.
        assertThat(paths(r)).containsExactly(FOLDER + "/main.js");
    }

    @Test
    void misspelledRequire_isReportedAndDoesNotStopTheRest() {
        library("core@1", "");
        doc(FOLDER + "/main.js", "// @require core\n// @require core@1\n");

        RequireReport r = resolve(config());

        assertThat(r.missing()).hasSize(1);
        assertThat(r.missing().get(0)).contains("the version is required");
        assertThat(paths(r)).contains("_vance/app-libs/core@1.js");
    }

    @Test
    void cycle_isBrokenAndReported() {
        library("a@1", "// @require b@1\n");
        library("b@1", "// @require a@1\n");
        doc(FOLDER + "/main.js", "// @require a@1\n");

        RequireReport r = resolve(config());

        assertThat(r.warnings()).anySatisfy(w -> assertThat(w).contains("Cycle"));
        assertThat(names(r)).contains("a", "b");
    }

    // ── app-local scripts ────────────────────────────────────────────

    /**
     * Found, not required: a file in the app folder belongs to this app. It
     * loads after every library — a helper may use one, and no library has
     * heard of a helper.
     */
    @Test
    void appScripts_areFoundByTheirMarkerAndLoadAfterLibraries() {
        library("core@1", "");
        script(FOLDER + "/helpers.js", "// @require core@1\n");
        doc(FOLDER + "/notes.js", "// just lying around\n");
        doc(FOLDER + "/main.js", "");

        RequireReport r = resolve(config());

        assertThat(paths(r)).containsExactly(
                "_vance/app-libs/core@1.js",
                FOLDER + "/helpers.js",
                FOLDER + "/main.js");
    }

    /** The program is not also an app-script, however its header is marked. */
    @Test
    void program_appearsOnceAndLast() {
        script(FOLDER + "/main.js", "");

        RequireReport r = resolve(config());

        assertThat(paths(r)).containsExactly(FOLDER + "/main.js");
        assertThat(r.scripts().get(0).kind()).isEqualTo(LoadedScript.PROGRAM);
    }

    // ── header parsing ───────────────────────────────────────────────

    /**
     * Header-only, so a `@require` mentioned in a doc comment further down is
     * not mistaken for one that is meant. "The top of the file" is a rule a
     * reader can check.
     */
    @Test
    void headerRequires_stopAtTheFirstLineOfCode() {
        List<String> found = RequireResolver.headerRequires("""
                // @require a@1
                /* @require b@1 */
                 * @require c@1

                const x = 1;
                // @require d@1
                """);

        assertThat(found).containsExactly("a@1", "b@1", "c@1");
    }

    private static List<String> paths(RequireReport r) {
        return r.scripts().stream().map(LoadedScript::path).toList();
    }

    private static List<String> names(RequireReport r) {
        return r.scripts().stream().map(LoadedScript::name).filter(java.util.Objects::nonNull)
                .toList();
    }
}
