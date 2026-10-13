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

    /** Domain labels documented in the skill-authoring manual. */
    private static final java.util.Set<String> DOMAIN_LABELS =
            java.util.Set.of("coding", "design", "decision", "writing", "research");

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
    void codingSkills_areTaggedCoding_andAgentDriven() throws IOException {
        // Coding recipes select "@coding" as relevant, chat recipes exclude it
        // (skills.md §7e): the tag decides where the skills make sense, their
        // own level how they are offered there — the model picks via
        // skill_activate. commit-message is a macro the user fires, so manual.
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
            assertThat(skill.frontmatterActivation())
                    .as(name + " activation")
                    .isEqualTo("commit-message".equals(name) ? SkillActivation.MANUAL : SkillActivation.AGENT);
        }
    }

    @Test
    void commitMessage_isAShotMacroWithAnAction() throws IOException {
        ResolvedSkill skill = bundledSkills().get("commit-message");
        assertThat(skill.lifecycle()).isEqualTo(SkillLifecycle.SHOT);
        assertThat(skill.action()).contains("git diff --staged");
        assertThat(skill.consumesArgs()).isTrue();
    }

    /** {@code relevantSkills} of every bundled recipe on the brain's classpath, by recipe name. */
    private static Map<String, List<String>> bundledRelevantSkills() throws IOException {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Resource resource :
                RESOLVER.getResources("classpath*:" + DocumentService.RESOURCE_PREFIX + "_vance/recipes/*.yaml")) {
            try (InputStream in = resource.getInputStream()) {
                Object parsed = new org.yaml.snakeyaml.Yaml().load(in);
                if (parsed instanceof Map<?, ?> m && m.get("relevantSkills") instanceof List<?> list) {
                    String file = resource.getFilename();
                    out.put(
                            file.substring(0, file.length() - ".yaml".length()),
                            list.stream().map(String::valueOf).toList());
                }
            }
        }
        return out;
    }

    @Test
    void everyRecipeSkillLabel_matchesABundledSkillTag() throws IOException {
        // A typo'd label ("@codin") silently selects nothing — an inclusion
        // would hide everything, an exclusion would hide nothing. Allowed:
        // tags of the brain's bundled skills plus the documented domain
        // labels, whose skills may ship in addons (design → designer).
        java.util.Set<String> tags = new java.util.HashSet<>(DOMAIN_LABELS);
        for (ResolvedSkill skill : bundledSkills().values()) {
            skill.tags().forEach(t -> tags.add(t.strip().toLowerCase(java.util.Locale.ROOT)));
        }
        Map<String, List<String>> recipes = bundledRelevantSkills();
        assertThat(recipes).isNotEmpty();
        recipes.forEach((recipe, selectors) -> {
            for (String entry : selectors) {
                if (SkillLabelSelector.isLabelSelector(entry)) {
                    assertThat(tags).as(recipe + " selector " + entry).contains(SkillLabelSelector.labelOf(entry));
                }
            }
        });
    }

    @Test
    void chatRecipesHideTheCodingGroup_codingRecipesSelectIt() throws IOException {
        Map<String, List<String>> recipes = bundledRelevantSkills();
        ResolvedSkill codeReview = bundledSkills().get("code-review");
        ResolvedSkill decisionFrame = bundledSkills().get("decision-frame");
        for (String chat : List.of("arthur", "eddie", "discuss")) {
            assertThat(SkillLabelSelector.relevant(recipes.get(chat), codeReview))
                    .as(chat)
                    .isFalse();
            assertThat(SkillLabelSelector.relevant(recipes.get(chat), decisionFrame))
                    .as(chat)
                    .isTrue();
        }
        ResolvedSkill designSkill = new ResolvedSkill(
                "house-style",
                "house-style",
                "desc",
                "1.0.0",
                List.of(),
                "body",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of("design"),
                true,
                SkillScope.RESOURCE,
                List.of(),
                List.of(),
                SkillLifecycle.STICKY,
                false,
                List.of(),
                null,
                SkillRun.INLINE);
        for (String coding : List.of("coding", "code-read", "python", "benjy-do-coding")) {
            assertThat(SkillLabelSelector.relevant(recipes.get(coding), codeReview))
                    .as(coding)
                    .isTrue();
            // Designer designs are web files a coding worker builds.
            assertThat(SkillLabelSelector.relevant(recipes.get(coding), designSkill))
                    .as(coding + " design")
                    .isTrue();
            assertThat(SkillLabelSelector.relevant(recipes.get(coding), decisionFrame))
                    .as(coding + " decision")
                    .isFalse();
        }
    }
}
