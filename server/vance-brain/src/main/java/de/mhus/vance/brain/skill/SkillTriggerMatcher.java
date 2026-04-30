package de.mhus.vance.brain.skill;

import de.mhus.vance.api.skills.SkillTriggerType;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.skill.ActiveSkillRefEmbedded;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Pre-turn skill auto-trigger. Called by conversation engines (Ford,
 * Arthur, future ones) right after the user input has been added to
 * the chat log and before the system prompt is built.
 *
 * <p>For each visible skill that has triggers and is not already
 * active, the matcher checks whether the user input fires the trigger
 * (PATTERN regex / KEYWORDS bag-of-words ≥ 50%). Matches are
 * activated as <em>one-shot</em> via
 * {@link SkillSteerProcessor#activate} so they only affect this turn.
 *
 * <p>The whitelist on {@code process.allowedSkillsOverride} is
 * respected — skills outside the recipe's whitelist are filtered out
 * before matching, keeping the trigger detection cheap when a recipe
 * has narrowed the surface.
 *
 * <p>Trigger semantics:
 * <ul>
 *   <li>{@link SkillTriggerType#PATTERN}: Java regex {@code find()} on
 *       the lowercased input. Compiled patterns are cached.</li>
 *   <li>{@link SkillTriggerType#KEYWORDS}: tokenized lowercase
 *       bag-of-words match. The trigger fires when ≥ 50% of the
 *       configured keywords appear as whole tokens in the input.</li>
 * </ul>
 *
 * <p>Cost: one {@code listAvailable} cascade-walk per turn (Mongo +
 * classpath) plus O(skills × triggers) matching. Pattern compilation
 * is amortised across turns; the rest is microseconds. No cache layer
 * in v1 — Mongo queries dominate latency.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SkillTriggerMatcher {

    /** Threshold for KEYWORDS matches: ≥ 50% of keywords must appear. */
    public static final double KEYWORD_THRESHOLD = 0.5;

    private final SkillResolver skillResolver;
    private final SkillSteerProcessor skillSteerProcessor;
    private final SessionService sessionService;

    /** Compiled regex cache keyed by the raw pattern string. */
    private final ConcurrentMap<String, Pattern> patternCache = new ConcurrentHashMap<>();

    /**
     * Detects any skill triggers fired by {@code userText} and
     * one-shot-activates the matched skills on the given process.
     * Quiet no-op when there is no user text or no candidate skills.
     *
     * @return names of skills that were freshly activated by this call
     */
    public List<String> detectAndActivate(
            ThinkProcessDocument process, @Nullable String userText) {
        if (userText == null || userText.isBlank()) return List.of();

        SkillScopeContext scope = scopeFor(process);
        List<ResolvedSkill> all = skillResolver.listAvailable(scope);
        if (all.isEmpty()) return List.of();

        Set<String> alreadyActive = activeSkillNames(process);
        Set<String> whitelist = process.getAllowedSkillsOverride();
        String lowered = userText.toLowerCase();
        Set<String> tokens = tokenize(lowered);

        List<String> activated = new ArrayList<>();
        for (ResolvedSkill skill : all) {
            if (!skill.enabled()) continue;
            if (alreadyActive.contains(skill.name())) continue;
            if (whitelist != null && !whitelist.contains(skill.name())) continue;
            if (skill.triggers() == null || skill.triggers().isEmpty()) continue;
            if (!anyTriggerMatches(skill, lowered, tokens)) continue;

            try {
                skillSteerProcessor.activate(process, skill.name(), /*oneShot*/ true);
                activated.add(skill.name());
                log.info("Skill auto-trigger id='{}' skill='{}' (one-shot)",
                        process.getId(), skill.name());
            } catch (RuntimeException e) {
                // Whitelist/unknown/etc. — don't fail the turn over a trigger miss.
                log.warn("Skill auto-trigger activation failed id='{}' skill='{}': {}",
                        process.getId(), skill.name(), e.toString());
            }
        }
        return activated;
    }

    private boolean anyTriggerMatches(
            ResolvedSkill skill, String loweredText, Set<String> tokens) {
        for (ResolvedSkill.Trigger trigger : skill.triggers()) {
            if (trigger == null || trigger.type() == null) continue;
            switch (trigger.type()) {
                case PATTERN -> {
                    if (matchesPattern(skill.name(), trigger.pattern(), loweredText)) {
                        return true;
                    }
                }
                case KEYWORDS -> {
                    if (matchesKeywords(trigger.keywords(), tokens)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean matchesPattern(String skillName, @Nullable String rawPattern, String text) {
        if (rawPattern == null || rawPattern.isBlank()) return false;
        Pattern compiled = patternCache.computeIfAbsent(rawPattern, p -> {
            try {
                return Pattern.compile(p, Pattern.CASE_INSENSITIVE);
            } catch (PatternSyntaxException e) {
                log.warn("Skill '{}' has invalid trigger pattern '{}': {}",
                        skillName, p, e.getDescription());
                // Sentinel for "never matches" — null in cache wouldn't survive computeIfAbsent.
                return INVALID_PATTERN;
            }
        });
        if (compiled == INVALID_PATTERN) return false;
        return compiled.matcher(text).find();
    }

    private static boolean matchesKeywords(List<String> keywords, Set<String> tokens) {
        if (keywords == null || keywords.isEmpty()) return false;
        int hits = 0;
        for (String kw : keywords) {
            if (kw == null || kw.isBlank()) continue;
            if (tokens.contains(kw.toLowerCase())) hits++;
        }
        if (hits == 0) return false;
        // Edge case: keywords with only blanks → treat as no-match.
        long valid = keywords.stream().filter(k -> k != null && !k.isBlank()).count();
        if (valid == 0) return false;
        return ((double) hits) / valid >= KEYWORD_THRESHOLD;
    }

    /** Lowercase tokens of length ≥ 2. Punctuation collapsed to whitespace. */
    private static Set<String> tokenize(String lowered) {
        String cleaned = lowered.replaceAll("[^a-z0-9]+", " ").trim();
        if (cleaned.isEmpty()) return Collections.emptySet();
        Set<String> out = new HashSet<>();
        for (String t : cleaned.split("\\s+")) {
            if (t.length() >= 2) out.add(t);
        }
        return out;
    }

    private static Set<String> activeSkillNames(ThinkProcessDocument process) {
        List<ActiveSkillRefEmbedded> active = process.getActiveSkills();
        if (active == null || active.isEmpty()) return Collections.emptySet();
        Set<String> out = new HashSet<>();
        for (ActiveSkillRefEmbedded ref : active) {
            if (ref != null && ref.getName() != null) out.add(ref.getName());
        }
        return out;
    }

    private SkillScopeContext scopeFor(ThinkProcessDocument process) {
        SessionDocument session = sessionService.findBySessionId(process.getSessionId())
                .orElse(null);
        String userId = session != null && !session.getUserId().isBlank()
                ? session.getUserId() : null;
        String projectId = session != null && !session.getProjectId().isBlank()
                ? session.getProjectId() : null;
        return SkillScopeContext.of(process.getTenantId(), userId, projectId);
    }

    /** Sentinel for patterns that failed to compile — caches the failure. */
    private static final Pattern INVALID_PATTERN =
            Pattern.compile("(?!)");  // matches nothing
}
