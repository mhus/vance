/**
 * Wire DTOs for the Setting Form subsystem (see
 * {@code specification/setting-forms.md}).
 *
 * <p>Setting Forms are named YAML forms — analogous to prompt
 * wizards — that target the {@code settings} collection instead of
 * generating a chat prompt. They reuse the shared
 * {@link de.mhus.vance.api.form.FormFieldDto} contract verbatim and
 * add a parallel {@code settings:} section ({@link ComputedSettingDto})
 * for derived/computed values rendered via Pebble.
 *
 * <p>Apply pipeline (Web-UI → brain → settings collection):
 * <ol>
 *   <li>{@code GET    /brain/{tenant}/setting-forms} — listing</li>
 *   <li>{@code GET    /brain/{tenant}/setting-forms/{name}} — full
 *       definition with live cascade values per direct-mapped field</li>
 *   <li>{@code POST   /brain/{tenant}/setting-forms/{name}/apply} —
 *       validate + render + write/delete batch</li>
 *   <li>{@code POST   /brain/{tenant}/setting-forms/{name}/validate} —
 *       dry-run; returns the same plan that {@code /apply} would execute</li>
 *   <li>{@code POST   /brain/{tenant}/setting-forms/{name}/reset} —
 *       delete every key the form would write, on its respective scope</li>
 * </ol>
 *
 * <p>Pebble templates (the {@code value} in {@link ComputedSettingDto},
 * and the {@code showIf}/{@code writeIf} expressions on form fields)
 * stay backend-only; they are not exposed on these DTOs.
 */
@NullMarked
package de.mhus.vance.api.settingform;

import org.jspecify.annotations.NullMarked;
