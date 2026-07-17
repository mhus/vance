/**
 * Document-template subsystem — loads YAML template definitions plus
 * their Pebble body files from the document cascade and applies them to
 * create new documents.
 *
 * <p>A template is a pair: {@code _vance/templates/<name>.yaml}
 * (definition — picker metadata, optional form, name policy, optional
 * MIME override) and {@code _vance/templates/<name>.tmpl.<ext>} (the
 * Pebble body whose extension carries the target type and whose rendered
 * content carries the target kind). Resolution runs the three-tier
 * cascade {@code project → _tenant → classpath:vance-defaults/}. On
 * apply the brain renders the body and writes exactly one document.
 *
 * <p>Architecturally a template is "a wizard whose output is a file":
 * the same form-engine ({@code FormFieldDto}, {@code FormValidator}) and
 * the same {@code PromptTemplateRenderer} — see
 * {@code specification/public/document-templates.md}.
 */
@NullMarked
package de.mhus.vance.brain.template;

import org.jspecify.annotations.NullMarked;
