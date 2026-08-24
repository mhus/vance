package de.mhus.vance.addon.brain.bistromath;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import de.mhus.vance.api.annotations.TypeScriptImport;
import de.mhus.vance.api.form.FormFieldDto;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * One node of a view's widget tree, as the client receives it.
 *
 * <p>The field set is closed rather than an open property bag. A bag would be
 * shorter here and worse everywhere else: the renderer could not be
 * type-checked against it, and a typo in an app document would reach the
 * browser as a silently missing property instead of a message naming the line.
 * A new widget adds a field here and a branch in the renderer.
 *
 * @param type    the widget, lowercase, from {@link WidgetType}.
 * @param label   caption — a page heading, a button's text, a tab's title.
 *                Authored as either {@code title:} or {@code label:}.
 * @param text    body of {@code text} and {@code markdown}.
 * @param from    state key this widget reads. The only binding there is: a
 *                script writes the key, the widget shows it. No paths, no
 *                table names, no expression — a name.
 * @param columns columns of a {@code table}; empty means "the table's declared
 *                fields", which is the normal case.
 * @param fields  form-engine fields of a {@code form}. The same
 *                {@link FormFieldDto} the wizards and setting forms use — this
 *                addon adds nothing to it.
 * @param on      event name → parsed handler.
 * @param children nested nodes, for the widgets that carry them.
 */
// FormFieldDto lives in vance-api, so it is generated into @vance/generated
// rather than into this addon's folder. Without this line the emitted
// interface would name a type it never imports.
@TypeScriptImport("import type { FormFieldDto } from '@vance/generated';")
@GenerateTypeScript("bistromath")
public record ViewNode(
        String type,
        @Nullable String label,
        @Nullable String text,
        @Nullable String from,
        List<String> columns,
        List<FormFieldDto> fields,
        Map<String, ViewAction> on,
        List<ViewNode> children) {

    public ViewNode {
        if (columns == null) columns = List.of();
        if (fields == null) fields = List.of();
        if (on == null) on = Map.of();
        if (children == null) children = List.of();
    }
}
