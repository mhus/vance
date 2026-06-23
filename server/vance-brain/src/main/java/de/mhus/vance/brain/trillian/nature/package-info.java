/**
 * Trillian Nature — the per-generation behaviour layer.
 *
 * <p>Trillian's Engine classes ({@code TrillianControlEngine},
 * {@code TrillianUserEngine}) carry the <b>framework</b> — the loop,
 * the LLM-call infrastructure, the inbox handling, the tool dispatch.
 * They never reference Nature-specific behaviour directly; instead
 * they look up a {@link TrillianNature} via
 * {@link TrillianNatureRegistry} at each turn and delegate the
 * Nature-specific hooks (prompt addenda, per-turn state mutation,
 * termination policy, future personality / reflexion / mode-switch
 * logic) to it.
 *
 * <p>Adding a new Nature is a single Spring {@code @Component} —
 * implement {@link TrillianNature} with the right {@link
 * TrillianNature#id() id()}, and the registry picks it up
 * automatically. Recipes pin the Nature via
 * {@code params.nature: '<id>'}.
 *
 * <p>Nature-0 ({@link TrillianNature0}) is the baseline architecture
 * proof: every hook returns the default. Future natures (A, B, …)
 * overlay personality, reflexion phases, mode-switching, token
 * budgets — strictly through the {@link TrillianNature} interface.
 *
 * <p>See {@code specification/trillian-engine.md} §2 + §4.
 */
@org.jspecify.annotations.NullMarked
package de.mhus.vance.brain.trillian.nature;
