/**
 * Setting Forms — named YAML forms that target the {@code settings}
 * collection. Counterpart to {@link de.mhus.vance.brain.wizard} but
 * with a settings-write pipeline instead of a prompt-render pipeline.
 *
 * <p>Components:
 * <ul>
 *   <li>{@link SettingFormLoader} — YAML parsing + four-tier cascade
 *       (project → user → vance → resource), Pebble-template
 *       compile-validation at load time.</li>
 *   <li>{@link SettingFormPlanBuilder} — translates a {@link ResolvedSettingForm}
 *       plus submitted values into a flat list of write/delete actions
 *       (the apply plan).</li>
 *   <li>{@link SettingFormService} — orchestrates plan execution
 *       against {@link de.mhus.vance.shared.settings.SettingService},
 *       handles scope mapping, password semantics, and reset.</li>
 *   <li>{@link SettingFormController} — REST endpoints under
 *       {@code /brain/{tenant}/setting-forms}.</li>
 * </ul>
 *
 * <p>See {@code specification/setting-forms.md} for the user-facing
 * contract.
 */
@NullMarked
package de.mhus.vance.brain.settingform;

import org.jspecify.annotations.NullMarked;
