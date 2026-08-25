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
 * @param options choices of a {@code select}.
 * @param variant severity of an {@code alert} or colour of a {@code badge} —
 *                one of {@link ViewParser#VARIANTS}. A literal, not a state
 *                key: a condition on the whole widget is what {@code show:} is
 *                for, and two badges with a {@code show:} each need no new
 *                binding concept.
 * @param mimeType language of a {@code code}, already resolved from the
 *                author's word to a mime type — the client passes it straight
 *                to the editor, so the list of names lives in one place and a
 *                typo is a parse error rather than plain text.
 * @param accept  what a {@code file} offers to pick, as the HTML attribute:
 *                {@code ".csv,text/plain"}.
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
        /**
         * State key deciding whether this widget is shown at all.
         *
         * <p>A key, not an expression. The program computes the boolean and puts
         * it in state; the widget reads it. That keeps exactly one expression
         * language in the browser — the sandbox's — and it is the reason
         * {@code visibleIf} was refused rather than implemented.
         */
        @Nullable String show,
        List<String> columns,
        List<ViewOption> options,
        @Nullable String variant,
        @Nullable String mimeType,
        @Nullable String accept,
        List<FormFieldDto> fields,
        Map<String, ViewAction> on,
        List<ViewNode> children) {

    public ViewNode {
        if (columns == null) columns = List.of();
        if (options == null) options = List.of();
        if (fields == null) fields = List.of();
        if (on == null) on = Map.of();
        if (children == null) children = List.of();
    }
}
