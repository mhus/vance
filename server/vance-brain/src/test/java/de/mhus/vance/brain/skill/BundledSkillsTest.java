package de.mhus.vance.brain.skill;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.api.skills.SkillActivation;
import de.mhus.vance.api.skills.SkillScope;
import de.mhus.vance.shared.document.DocumentService;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Every bundled {@code SKILL.md} on the brain's classpath parses. At runtime
 * {@link SkillLoader} skips a malformed bundled skill with a warning — the
 * skill just silently never exists — so the contract is pinned here.
 */
class BundledSkillsTest {

    private static final PathMatchingResourcePatternResolver RESOLVER = new PathMatchingResourcePatternResolver();

    /** Classpath sibling reader — same lookup the loader's resource tier uses. */
    private static final SkillLoader.SiblingReader CLASSPATH_SIBLINGS = (folder, relativePath) -> {
        Resource resource = RESOLVER.getResource("classpath:" + DocumentService.RESOURCE_PREFIX
                + SkillLoader.SKILL_PATH_PREFIX + folder + "/" + relativePath);
        if (!resource.exists()) return Optional.empty();
        try (InputStream in = resource.getInputStream()) {
            return Optional.of(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    };

    private static Map<String, ResolvedSkill> bundledSkills() throws IOException {
        Resource[] resources = RESOLVER.getResources("classpath*:" + DocumentService.RESOURCE_PREFIX
                + SkillLoader.SKILL_PATH_PREFIX + "*/" + SkillLoader.SKILL_ENTRY_FILE);
        Map<String, ResolvedSkill> out = new LinkedHashMap<>();
        List<String> failures = new ArrayList<>();
        for (Resource resource : resources) {
            String url = resource.getURL().toString();
            String folder = url.substring(0, url.lastIndexOf('/'));
            String stem = folder.substring(folder.lastIndexOf('/') + 1);
            try (InputStream in = resource.getInputStream()) {
                String raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                out.put(stem, SkillLoader.parse(stem, raw, SkillScope.RESOURCE, CLASSPATH_SIBLINGS, stem));
            } catch (RuntimeException e) {
                failures.add(stem + ": " + e.getMessage());
            }
        }
        assertThat(failures).as("bundled skills that fail to parse").isEmpty();
        return out;
    }

    @Test
    void everyBundledSkillParses() throws IOException {
        assertThat(bundledSkills()).isNotEmpty();
    }

    @Test
    void codingSkills_areOfferedByLabel_notSelfAdvertised() throws IOException {
        // benjy-do-coding advertises "@coding" (skills.md §7e): these skills reach
        // coding doers through the tag, and stay out of every other context.
        Map<String, ResolvedSkill> skills = bundledSkills();
        for (String name : List.of(
                "code-review",
                "test-first",
                "systematic-debugging",
                "safe-refactoring",
                "codebase-orientation",
                "commit-message")) {
            ResolvedSkill skill = skills.get(name);
            assertThat(skill).as(name).isNotNull();
            assertThat(skill.enabled()).as(name + " enabled").isTrue();
            assertThat(skill.tags()).as(name + " tags").contains("coding");
            assertThat(skill.category()).as(name + " category").isEqualTo("coding");
            assertThat(skill.frontmatterActivation()).as(name + " activation").isNotEqualTo(SkillActivation.AGENT);
        }
    }

    @Test
    void commitMessage_isAShotMacroWithAnAction() throws IOException {
        ResolvedSkill skill = bundledSkills().get("commit-message");
        assertThat(skill.lifecycle()).isEqualTo(SkillLifecycle.SHOT);
        assertThat(skill.action()).contains("git diff --staged");
        assertThat(skill.consumesArgs()).isTrue();
    }
}
