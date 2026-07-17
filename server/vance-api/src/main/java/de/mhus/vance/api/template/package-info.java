/**
 * Wire-contract DTOs for the document-template subsystem.
 *
 * <p>A document template is a YAML definition ({@code _vance/templates/&lt;name&gt;.yaml})
 * plus a separate Pebble body file ({@code &lt;name&gt;.tmpl.&lt;ext&gt;}) under the
 * standard document-cascade ({@code project → _tenant →
 * classpath:vance-defaults/_vance/templates/}). The Web-UI offers them
 * as a "Template" tab in the create-document dialog; on apply the brain
 * renders the body and writes exactly one new document — templates are
 * a document-authoring affordance, not a spawn path. See
 * {@code specification/public/document-templates.md} for the full spec.
 *
 * <p>Served by {@code de.mhus.vance.brain.template.TemplateController}
 * under {@code /brain/{tenant}/templates/...}.
 */
@NullMarked
package de.mhus.vance.api.template;

import org.jspecify.annotations.NullMarked;
