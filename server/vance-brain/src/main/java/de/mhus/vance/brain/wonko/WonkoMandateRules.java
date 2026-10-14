package de.mhus.vance.brain.wonko;

import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The rules for category changes Wonko requests on its own mandate (plan
 * §6.4) — code, not prompt. Wonko may reshape the vocabulary freely and
 * tighten freely; loosening is allowed only up to a category's ceiling,
 * beyond that it becomes a proposal the user has to confirm. Locked
 * categories never change. Moving a topic into a looser category counts as
 * loosening — otherwise "define a decide-category, move the ask-topic there"
 * would be self-authorisation.
 */
final class WonkoMandateRules {

    static final String BY_WONKO = "wonko";
    static final String BY_USER = "user";

    enum Outcome {
        APPLIED,
        /** Loosening beyond the ceiling — needs the user's confirmation. */
        PROPOSED,
        REJECTED
    }

    record Result(Outcome outcome, String description) {}

    private WonkoMandateRules() {}

    /** Applies a change Wonko requested, under the rules above. */
    static Result request(WonkoRegister register, WonkoCategoryChange change, int round) {
        Map<String, WonkoCategory> categories = register.getMandate().getCategories();
        String name = WonkoKeys.slug(change.category());
        String target = WonkoKeys.slug(change.target());
        WonkoCategory existing = categories.get(name);
        return switch (change.op()) {
            case DEFINE -> {
                if (name.isEmpty() || existing != null) {
                    yield rejected("define '" + name + "': exists or empty");
                }
                categories.put(
                        name,
                        WonkoCategory.builder()
                                .level(register.getMandate().getDefaultLevel())
                                .description(change.description())
                                .createdByWonko(true)
                                .build());
                yield applied(register, round, BY_WONKO, "category " + name + " defined", change.reason());
            }
            case DESCRIBE -> {
                if (existing == null) {
                    yield rejected("describe '" + name + "': no such category");
                }
                existing.setDescription(change.description());
                yield applied(register, round, BY_WONKO, "category " + name + " described", change.reason());
            }
            case RENAME -> {
                if (existing == null || target.isEmpty() || categories.containsKey(target)) {
                    yield rejected("rename '" + name + "' → '" + target + "': missing source or target taken");
                }
                if (existing.isLocked()) {
                    yield rejected("rename '" + name + "': category is locked");
                }
                renameCategory(register, name, target);
                yield applied(register, round, BY_WONKO, "category " + name + " renamed to " + target, change.reason());
            }
            case SPLIT -> {
                if (existing == null || target.isEmpty() || categories.containsKey(target)) {
                    yield rejected("split '" + name + "' → '" + target + "': missing parent or target taken");
                }
                // The child inherits level, ceiling and lock — otherwise split-then-loosen
                // would be the way around a locked or ceiling-bound parent.
                categories.put(
                        target,
                        WonkoCategory.builder()
                                .level(existing.getLevel())
                                .ceiling(existing.getCeiling())
                                .locked(existing.isLocked())
                                .description(change.description())
                                .createdByWonko(true)
                                .build());
                yield applied(register, round, BY_WONKO, "category " + target + " split from " + name, change.reason());
            }
            case MERGE -> {
                WonkoCategory into = categories.get(target);
                if (existing == null || into == null || name.equals(target)) {
                    yield rejected("merge '" + name + "' → '" + target + "': missing category");
                }
                if (existing.isLocked()) {
                    yield rejected("merge '" + name + "': category is locked");
                }
                MandateLevel merged = MandateLevel.stricter(existing.getLevel(), into.getLevel());
                if (into.isLocked() && merged != into.getLevel()) {
                    yield rejected("merge into '" + target + "': locked target would change its level");
                }
                into.setLevel(merged);
                categories.remove(name);
                reclassifyAll(register, name, target);
                yield applied(
                        register,
                        round,
                        BY_WONKO,
                        "category " + name + " merged into " + target + " (level " + level(merged) + ")",
                        change.reason());
            }
            case LEVEL -> {
                MandateLevel wanted = change.level();
                if (existing == null || wanted == null) {
                    yield rejected("level '" + name + "': missing category or level");
                }
                if (existing.isLocked()) {
                    yield rejected("level '" + name + "': category is locked");
                }
                if (wanted == existing.getLevel()) {
                    yield rejected("level '" + name + "': already " + level(wanted));
                }
                if (wanted.isLooserThan(existing.getLevel()) && !withinCeiling(existing, wanted)) {
                    yield new Result(
                            Outcome.PROPOSED,
                            "loosen category " + name + " from " + level(existing.getLevel()) + " to " + level(wanted));
                }
                MandateLevel before = existing.getLevel();
                existing.setLevel(wanted);
                yield applied(
                        register,
                        round,
                        BY_WONKO,
                        "category " + name + ": " + level(before) + " → " + level(wanted),
                        change.reason());
            }
            case CLASSIFY -> classify(register, change, target, round);
        };
    }

    /**
     * Applies a change the user confirmed (a former {@link Outcome#PROPOSED}).
     * The loosening check is the user's call now; the lock still holds —
     * a proposal on a locked category is never created in the first place.
     */
    static Result confirm(WonkoRegister register, WonkoCategoryChange change, int round) {
        Map<String, WonkoCategory> categories = register.getMandate().getCategories();
        String name = WonkoKeys.slug(change.category());
        String target = WonkoKeys.slug(change.target());
        if (change.op() == WonkoCategoryChange.Op.LEVEL && change.level() != null) {
            WonkoCategory existing = categories.get(name);
            if (existing == null || existing.isLocked()) {
                return rejected("level '" + name + "': missing or locked");
            }
            existing.setLevel(change.level());
            return applied(
                    register,
                    round,
                    BY_USER,
                    "category " + name + " set to " + level(change.level()) + " (confirmed proposal)",
                    change.reason());
        }
        if (change.op() == WonkoCategoryChange.Op.CLASSIFY) {
            String topicKey = WonkoKeys.topicKey(change.topic());
            return register.topic(topicKey)
                    .map(topic -> {
                        topic.setCategory(target);
                        return applied(
                                register,
                                round,
                                BY_USER,
                                "topic " + topicKey + " moved to " + target + " (confirmed proposal)",
                                change.reason());
                    })
                    .orElseGet(() -> rejected("classify: no topic '" + topicKey + "'"));
        }
        return rejected("only level and classify changes are ever proposed");
    }

    /**
     * The user sets a category directly — no rule applies, the user may also
     * lock or unlock. A missing category is created.
     */
    static Result userSet(
            WonkoRegister register,
            String rawCategory,
            @Nullable MandateLevel level,
            @Nullable Boolean locked,
            int round) {
        String name = WonkoKeys.slug(rawCategory);
        if (name.isEmpty()) {
            return rejected("user change without category");
        }
        WonkoCategory category = register.getMandate().getCategories().computeIfAbsent(name, k -> new WonkoCategory());
        StringBuilder what = new StringBuilder("category ").append(name);
        if (level != null) {
            category.setLevel(level);
            what.append(" set to ").append(level(level));
        }
        if (locked != null) {
            category.setLocked(locked);
            what.append(locked ? ", locked" : ", unlocked");
        }
        return applied(register, round, BY_USER, what.toString(), null);
    }

    private static Result classify(WonkoRegister register, WonkoCategoryChange change, String target, int round) {
        String topicKey = WonkoKeys.topicKey(change.topic());
        WonkoTopic topic = register.topic(topicKey).orElse(null);
        if (topic == null || target.isEmpty()) {
            return rejected("classify '" + topicKey + "': missing topic or target");
        }
        WonkoMandate mandate = register.getMandate();
        MandateLevel from = mandate.levelFor(topic.getCategory());
        MandateLevel to = mandate.levelFor(target);
        if (to.isLooserThan(from)) {
            WonkoCategory source =
                    topic.getCategory() == null ? null : mandate.getCategories().get(topic.getCategory());
            boolean allowed = source != null && !source.isLocked() && withinCeiling(source, to);
            if (!allowed) {
                return new Result(
                        Outcome.PROPOSED,
                        "move topic " + topicKey + " from " + topic.getCategory() + " (" + level(from) + ") to "
                                + target + " (" + level(to) + ")");
            }
        }
        topic.setCategory(target);
        return applied(register, round, BY_WONKO, "topic " + topicKey + " moved to " + target, change.reason());
    }

    private static boolean withinCeiling(WonkoCategory category, MandateLevel wanted) {
        MandateLevel ceiling = category.getCeiling();
        return ceiling != null && !wanted.isLooserThan(ceiling);
    }

    private static void renameCategory(WonkoRegister register, String from, String to) {
        Map<String, WonkoCategory> renamed = new LinkedHashMap<>();
        register.getMandate().getCategories().forEach((k, v) -> renamed.put(k.equals(from) ? to : k, v));
        register.getMandate().setCategories(renamed);
        reclassifyAll(register, from, to);
    }

    private static void reclassifyAll(WonkoRegister register, String from, String to) {
        for (WonkoTopic topic : register.getTopics()) {
            if (from.equals(topic.getCategory())) {
                topic.setCategory(to);
            }
        }
    }

    private static Result applied(
            WonkoRegister register, int round, String by, String description, @Nullable String reason) {
        register.getMandateLog()
                .add(WonkoMandateChange.builder()
                        .round(round)
                        .by(by)
                        .change(description)
                        .reason(reason)
                        .build());
        return new Result(Outcome.APPLIED, description);
    }

    private static Result rejected(String description) {
        return new Result(Outcome.REJECTED, description);
    }

    private static String level(MandateLevel level) {
        return level.name().toLowerCase(java.util.Locale.ROOT);
    }
}
