/**
 * Shared form-rendering DTOs.
 *
 * <p>This package carries the universal {@link FormFieldDto} contract
 * used by all form-driven subsystems: prompt wizards
 * ({@code de.mhus.vance.api.wizard}) and kit tool-templates
 * ({@code de.mhus.vance.api.kit.ToolTemplateInputDto}, which mirrors
 * the same structure on the wire). The Web-UI ships a single
 * {@code FormFields.vue} renderer that consumes these.
 *
 * <p>{@link FormFieldDto#getLabel()} and similar localized fields are
 * {@code Map<String, String>}-shaped (language-code → text), resolved
 * server-side at render time against the tenant's default language.
 *
 * <p>See {@code specification/wizards.md §3} for the field-type
 * catalog and full schema.
 *
 * <p>Setting Forms (see {@code specification/setting-forms.md}) reuse
 * {@link FormFieldDto} verbatim and pull in three optional extras —
 * {@code bindsTo} (direct 1:1 mapping to a setting key, see
 * {@link BindsToDto}), {@code showIf}/{@code writeIf} (Pebble
 * expressions for conditional rendering and conditional write/clear),
 * and the {@code currentValue}/{@code currentSource} live-cascade
 * fields populated by the brain on read. Wizards and tool-templates
 * leave these {@code null}; {@code @JsonInclude(NON_NULL)} keeps them
 * off the wire there.
 */
@NullMarked
package de.mhus.vance.api.form;

import org.jspecify.annotations.NullMarked;
