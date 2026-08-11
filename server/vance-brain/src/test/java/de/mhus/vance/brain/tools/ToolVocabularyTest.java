package de.mhus.vance.brain.tools;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.toolpack.Tool;
import java.lang.reflect.Constructor;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;

/**
 * One concept, one parameter name — within the file/document content families.
 *
 * <p>Those families had accumulated several names for the same thing: a line
 * window was {@code offset}/{@code limit} in {@code doc_read_lines} and
 * {@code startLine}/{@code maxLines} in {@code file_read}; a path scope was
 * {@code folder}, {@code parentPath} or {@code pathPrefix} depending on which
 * listing tool you reached for; an edit was {@code old_string} here and
 * {@code oldText} there. Every variant costs the model a guess, and a wrong
 * guess is silent — an unknown key is dropped and the tool answers about
 * something else. This test pins the winners.
 *
 * <h2>Why the scope is narrow</h2>
 *
 * The forbidden names are only forbidden <em>here</em>. Outside these families
 * the same words mean different things and must keep their spelling:
 * {@code from}/{@code to} are time ranges in the calendar / gantt / journal
 * tools and node ids in {@code canvas_edge_add}; {@code folder} is the app's
 * folder in ~35 application tools ({@code kanban_*}, {@code gtd_*},
 * {@code issue_*}, …); {@code targetPath} is the output file of the seven
 * {@code image_*} tools; {@code target} is an edge endpoint in the graph and
 * relations tools. A global rename would have been wrong, so the prefix filter
 * below is part of the rule, not a shortcut.
 *
 * <h2>Aliases</h2>
 *
 * The old spellings are still <em>read</em> at runtime (see
 * {@code KindToolSupport.paramStringAliased} and friends) so prompts, manuals
 * and in-flight calls keep working. They are deliberately not
 * <em>declared</em>, which is exactly what this test checks: the schema — the
 * only thing the model sees — offers one name per concept.
 */
class ToolVocabularyTest {

    /**
     * The content families this rule covers: generic wrappers plus their two
     * backends, and the document tools. Deliberately not "every tool".
     */
    private static final List<String> COVERED_PREFIXES = List.of(
            "doc_", "file_", "work_file_", "client_file_");

    /** Deprecated spelling → the name that replaced it, and why it exists. */
    private static final Map<String, String> RENAMED = Map.of(
            "offset", "startLine (line window start; `offset` also read as a byte offset elsewhere)",
            // One concept per name, but also one *unit* per name: a line window is
            // fromLine/toLine, a character range is fromChar/toChar. Naming a
            // character offset `fromLine` is worse than the bare `from` it
            // replaced — it reads as correct and answers with the wrong slice.
            "from", "fromLine / fromChar by unit (bare `from` is a time range in calendar tools)",
            "to", "toLine / toChar by unit",
            "folder", "pathPrefix (path scope; `folder` is an app's folder in the application tools)",
            "parentPath", "pathPrefix (path scope)",
            "targetPath", "newPath (write destination; `targetPath` is the image tools' output file)",
            "target", "newPath (write destination; `target` is an edge endpoint in graph/relations)",
            "old_string", "oldText (camelCase like the rest of the tool surface)",
            "new_string", "newText (camelCase)",
            "replace_all", "replaceAll (camelCase)");

    /**
     * {@code project} → {@code projectId} is tracked separately: the word is
     * legitimate in the gtd and kit tools, which are outside these families.
     */
    private static final String PROJECT_ALIAS = "project";

    @Test
    void noCoveredToolDeclaresARenamedAwayParameter() {
        Map<String, List<String>> offenders = new TreeMap<>();
        for (Map.Entry<String, Tool> e : coveredTools().entrySet()) {
            Set<String> declared = declaredParams(e.getValue());
            List<String> bad = new java.util.ArrayList<>();
            for (String deprecated : RENAMED.keySet()) {
                if (declared.contains(deprecated)) {
                    bad.add(deprecated + " → use " + RENAMED.get(deprecated));
                }
            }
            if (declared.contains(PROJECT_ALIAS)) {
                bad.add(PROJECT_ALIAS + " → use projectId");
            }
            if (!bad.isEmpty()) offenders.put(e.getKey(), bad);
        }
        assertThat(offenders)
                .as("file/document tools must declare one name per concept; "
                        + "keep the old spelling readable as an undeclared alias instead")
                .isEmpty();
    }

    @Test
    void theSuiteActuallySeesTheToolsItClaimsToGuard() {
        // A vocabulary test that silently scanned nothing would pass forever.
        Map<String, Tool> covered = coveredTools();
        assertThat(covered).hasSizeGreaterThan(40);
        assertThat(covered).containsKeys(
                "doc_read_lines", "doc_get_selection", "doc_edit", "doc_concat",
                "doc_list_in_folder", "doc_list_folders", "doc_version_restore",
                "file_read", "file_grep", "work_file_read");
    }

    @Test
    void theCanonicalNamesAreTheOnesActuallyInUse() {
        Map<String, Tool> tools = coveredTools();
        assertThat(declaredParams(tools.get("doc_read_lines")))
                .contains("startLine", "maxLines");
        // Character offsets, so *Char — not the *Line spelling used for line
        // windows. See the RENAMED note above.
        assertThat(declaredParams(tools.get("doc_get_selection")))
                .contains("fromChar", "toChar")
                .doesNotContain("fromLine", "toLine");
        assertThat(declaredParams(tools.get("doc_edit")))
                .contains("oldText", "newText", "replaceAll");
        assertThat(declaredParams(tools.get("doc_concat"))).contains("newPath");
        assertThat(declaredParams(tools.get("doc_version_restore"))).contains("newPath");
        assertThat(declaredParams(tools.get("doc_list_in_folder"))).contains("pathPrefix");
        assertThat(declaredParams(tools.get("doc_list_folders"))).contains("pathPrefix");
    }

    // ──────────────── reflection ────────────────

    private static Set<String> declaredParams(Tool tool) {
        Map<String, Object> schema = tool.paramsSchema();
        Object props = schema == null ? null : schema.get("properties");
        Set<String> out = new TreeSet<>();
        if (props instanceof Map<?, ?> m) {
            for (Object k : m.keySet()) {
                if (k instanceof String s) out.add(s);
            }
        }
        return out;
    }

    /**
     * Every {@link Tool} on the brain classpath whose name starts with one of
     * {@link #COVERED_PREFIXES}, instantiated with all-null constructor args.
     * Null args are safe because these tools build their schema as a static
     * constant; one that touches a collaborator in its constructor drops out of
     * the scan and {@link #theSuiteActuallySeesTheToolsItClaimsToGuard} notices.
     */
    private static Map<String, Tool> coveredTools() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(Tool.class));
        Map<String, Tool> out = new LinkedHashMap<>();
        for (BeanDefinition bd : scanner.findCandidateComponents("de.mhus.vance.brain.tools")) {
            String className = bd.getBeanClassName();
            if (className == null) continue;
            try {
                Class<?> c = Class.forName(className);
                if (c.isInterface() || java.lang.reflect.Modifier.isAbstract(c.getModifiers())) {
                    continue;
                }
                Constructor<?> ctor = shortestConstructor(c);
                ctor.setAccessible(true);
                Tool tool = (Tool) ctor.newInstance(new Object[ctor.getParameterCount()]);
                String name = tool.name();
                if (COVERED_PREFIXES.stream().anyMatch(name::startsWith)) {
                    out.put(name, tool);
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Not instantiable without collaborators — out of scope for a
                // schema-only check.
            }
        }
        return out;
    }

    private static Constructor<?> shortestConstructor(Class<?> c) {
        Constructor<?>[] all = c.getDeclaredConstructors();
        Constructor<?> best = all[0];
        for (Constructor<?> ctor : all) {
            if (ctor.getParameterCount() < best.getParameterCount()) best = ctor;
        }
        return best;
    }
}
