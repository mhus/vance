package de.mhus.vance.addon.brain.bistromath;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.toolpack.ToolException;
import org.junit.jupiter.api.Test;

/**
 * The parser is where a view document's mistakes are supposed to surface, so
 * most of these tests assert on refusals rather than on successes.
 */
class ViewParserTest {

    @Test
    void parse_pageWithToolbarAndTable_buildsTree() {
        String yaml = """
                type: page
                title: Invoices
                children:
                  - type: toolbar
                    children:
                      - type: button
                        label: Reload
                        on:
                          click: reload
                  - type: table
                    from: invoices
                    columns: [nr, customer]
                """;

        ViewNode root = ViewParser.parse(yaml, "views/list.yaml");

        assertThat(root.type()).isEqualTo("page");
        assertThat(root.label()).isEqualTo("Invoices");
        assertThat(root.children()).hasSize(2);

        ViewNode button = root.children().get(0).children().get(0);
        assertThat(button.type()).isEqualTo("button");
        assertThat(button.on().get("click").kind()).isEqualTo(ActionKind.RELOAD);

        ViewNode table = root.children().get(1);
        assertThat(table.from()).isEqualTo("invoices");
        assertThat(table.columns()).containsExactly("nr", "customer");
    }

    @Test
    void parse_labelSpelledAsLabel_isTheSameFieldAsTitle() {
        ViewNode root = ViewParser.parse("type: page\nlabel: Hello\n", "v.yaml");

        assertThat(root.label()).isEqualTo("Hello");
    }

    @Test
    void parse_notAMapping_isRejected() {
        assertThatThrownBy(() -> ViewParser.parse("- one\n- two\n", "views/bad.yaml"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("views/bad.yaml")
                .hasMessageContaining("not a YAML mapping");
    }

    @Test
    void parse_missingType_isRejected() {
        assertThatThrownBy(() -> ViewParser.parse("title: nope\n", "v.yaml"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("missing `type`");
    }

    @Test
    void parse_unknownWidget_isRejectedAndListsTheKnownOnes() {
        assertThatThrownBy(() -> ViewParser.parse("type: carousel\n", "v.yaml"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("unknown widget `carousel`")
                .hasMessageContaining("toolbar");
    }

    /**
     * A planned widget gets a different message from an unknown one. Telling an
     * author "unknown widget: chart" would send them hunting for a typo.
     */
    @Test
    void parse_plannedWidget_saysItArrivesLaterRatherThanUnknown() {
        assertThatThrownBy(() -> ViewParser.parse("type: chart\n", "v.yaml"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("not rendered yet")
                .hasMessageNotContaining("unknown widget");
    }

    /**
     * The message has to name the replacement, because the author's intent is
     * right and only the spelling is wrong: a condition here is a state key the
     * program computes, never an expression the renderer evaluates.
     */
    @Test
    void parse_visibleIf_isRejectedAndPointsAtShow() {
        String yaml = "type: text\ntext: hi\nvisibleIf: \"state.x === 1\"\n";

        assertThatThrownBy(() -> ViewParser.parse(yaml, "v.yaml"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("visibleIf")
                .hasMessageContaining("show: <key>");
    }

    @Test
    void parse_show_isCarriedAsAStateKey() {
        ViewNode node = ViewParser.parse("type: text\ntext: hi\nshow: hasRows\n", "v.yaml");

        assertThat(node.show()).isEqualTo("hasRows");
    }

    /**
     * The direct inputs exist beside `form`, not inside it: one widget, one
     * state key, no field list. So `from` is the whole contract and its absence
     * is the one thing worth refusing.
     */
    @Test
    void parse_inputWithoutFrom_isRejected() {
        for (String widget : new String[] {"input", "number", "toggle"}) {
            assertThatThrownBy(() -> ViewParser.parse("type: " + widget + "\n", "v.yaml"))
                    .isInstanceOf(ToolException.class)
                    .hasMessageContaining("needs `from`");
        }
    }

    @Test
    void parse_selectWithoutOptions_isRejected() {
        assertThatThrownBy(() -> ViewParser.parse("type: select\nfrom: status\n", "v.yaml"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("a `select` needs `options`");
    }

    /**
     * A bare scalar is a value that is also its own caption; a mapping
     * separates the two. Both, because most option lists are short technical
     * words that read fine as they are.
     */
    /**
     * The distinction that made this check useful rather than obstructive:
     * absent means the author forgot, empty means the program supplies them.
     *
     * <p>Surfaced by {@code ui@1}: once a select's choices come from documents
     * there is nothing honest to write in the view, and demanding a placeholder
     * would show a wrong choice until {@code init()} has run.
     */
    @Test
    void parse_selectWithEmptyOptions_isAccepted() {
        ViewNode node = ViewParser.parse("type: select\nfrom: status\noptions: []\n", "v.yaml");

        assertThat(node.options()).isEmpty();
    }

    @Test
    void parse_selectWithoutOptions_saysHowToDeferThem() {
        assertThatThrownBy(() -> ViewParser.parse("type: select\nfrom: status\n", "v.yaml"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("`options: []`");
    }

    @Test
    void parse_selectOptions_takeBothSpellings() {
        String yaml = """
                type: select
                from: status
                options:
                  - open
                  - { value: paid, label: Bezahlt }
                """;

        ViewNode node = ViewParser.parse(yaml, "v.yaml");

        assertThat(node.options()).hasSize(2);
        assertThat(node.options().get(0).value()).isEqualTo("open");
        assertThat(node.options().get(0).label()).isEqualTo("open");
        assertThat(node.options().get(1).value()).isEqualTo("paid");
        assertThat(node.options().get(1).label()).isEqualTo("Bezahlt");
    }

    @Test
    void parse_optionEntryWithoutValue_isRejected() {
        String yaml = """
                type: select
                from: status
                options:
                  - { label: Bezahlt }
                """;

        assertThatThrownBy(() -> ViewParser.parse(yaml, "v.yaml"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("needs a `value`");
    }

    /**
     * The check that made this a separate branch rather than another `else if`:
     * chained onto the fields branch it would never run for a `form`, so the
     * key would be dropped without a word.
     */
    @Test
    void parse_optionsOnAForm_isRejectedRatherThanIgnored() {
        String yaml = """
                type: form
                from: draft
                options: [a, b]
                fields:
                  - name: x
                    type: string
                    label: { en: X }
                """;

        assertThatThrownBy(() -> ViewParser.parse(yaml, "v.yaml"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("`options` belongs to a `select`");
    }

    /**
     * A variant is a meaning, not a shade — so the set is closed and a typo is
     * a parse error rather than a widget that silently renders neutral.
     */
    @Test
    void parse_variant_isCheckedAgainstTheClosedSet() {
        ViewNode ok = ViewParser.parse(
                "type: alert\ntext: passt auf\nvariant: Warning\n", "v.yaml");
        assertThat(ok.variant()).isEqualTo("warning");

        assertThatThrownBy(() -> ViewParser.parse(
                "type: alert\ntext: x\nvariant: chartreuse\n", "v.yaml"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("unknown `variant`");
    }

    @Test
    void parse_variantOnAnotherWidget_isRejected() {
        assertThatThrownBy(() -> ViewParser.parse(
                "type: text\ntext: x\nvariant: info\n", "v.yaml"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("`variant` belongs to an `alert` or a `badge`");
    }

    /**
     * The author writes a language, the client gets a mime type: the list of
     * names exists once, and an unknown one fails here instead of falling
     * through to plain text where nobody would notice.
     */
    @Test
    void parse_codeLanguage_becomesAMimeType() {
        ViewNode node = ViewParser.parse(
                "type: code\nfrom: src\nlanguage: YAML\n", "v.yaml");

        assertThat(node.mimeType()).isEqualTo("application/yaml");
    }

    @Test
    void parse_unknownCodeLanguage_isRejected() {
        assertThatThrownBy(() -> ViewParser.parse(
                "type: code\nfrom: src\nlanguage: cobol\n", "v.yaml"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("unknown `language`");
    }

    @Test
    void parse_paginationAndFile_needFrom() {
        for (String widget : new String[] {"pagination", "file"}) {
            assertThatThrownBy(() -> ViewParser.parse("type: " + widget + "\n", "v.yaml"))
                    .isInstanceOf(ToolException.class)
                    .hasMessageContaining("needs `from`");
        }
    }

    @Test
    void parse_acceptOnAnotherWidget_isRejected() {
        assertThatThrownBy(() -> ViewParser.parse(
                "type: input\nfrom: q\naccept: .csv\n", "v.yaml"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("`accept` belongs to a `file`");
    }

    @Test
    void parse_card_carriesChildren() {
        String yaml = """
                type: card
                title: Zusammenfassung
                children:
                  - { type: text, text: hallo }
                """;

        ViewNode node = ViewParser.parse(yaml, "v.yaml");

        assertThat(node.label()).isEqualTo("Zusammenfassung");
        assertThat(node.children()).hasSize(1);
    }

    /**
     * There is exactly one drawing surface — the guest's own document — and it
     * cannot be moved to where a nested widget sits, because re-parenting an
     * iframe restarts the program. Accepting the key inside the tree and
     * rendering it elsewhere would be the "almost right" this parser prevents.
     */
    @Test
    void parse_region_isOnlyAllowedOnTheRoot() {
        assertThat(ViewParser.parse("type: page\nregion: 320\n", "v.yaml").region())
                .isEqualTo("320");
        assertThat(ViewParser.parse("type: page\nregion: FILL\n", "v.yaml").region())
                .isEqualTo("fill");

        String nested = """
                type: page
                children:
                  - { type: text, text: hi, region: 100 }
                """;
        assertThatThrownBy(() -> ViewParser.parse(nested, "v.yaml"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("`region` belongs on the view's root");
    }

    @Test
    void parse_regionWithANonsenseHeight_isRejected() {
        for (String bad : new String[] {"0", "-5", "9999", "gross"}) {
            assertThatThrownBy(() -> ViewParser.parse("type: page\nregion: " + bad + "\n",
                    "v.yaml"))
                    .as(bad)
                    .isInstanceOf(ToolException.class)
                    .hasMessageContaining("height in pixels");
        }
    }

    @Test
    void parse_withoutRegion_isNull() {
        assertThat(ViewParser.parse("type: page\n", "v.yaml").region()).isNull();
    }

    @Test
    void parse_embedWithoutAPath_isRejected() {
        assertThatThrownBy(() -> ViewParser.parse("type: embed\n", "v.yaml"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("an `embed` needs");
    }

    @Test
    void parse_repeatWithoutFrom_isRejected() {
        assertThatThrownBy(() -> ViewParser.parse("type: repeat\n", "v.yaml"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("a `repeat` needs `from`");
    }

    /**
     * A dialog without a `show:` key could be opened but never closed — and the
     * author would look for a missing close button rather than a missing key.
     */
    @Test
    void parse_dialogWithoutShow_isRejected() {
        String yaml = """
                type: dialog
                title: Really?
                children:
                  - { type: text, text: "Are you sure?" }
                """;

        assertThatThrownBy(() -> ViewParser.parse(yaml, "v.yaml"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("a `dialog` needs `show`");
    }

    @Test
    void parse_childrenOnLeafWidget_isRejected() {
        String yaml = """
                type: text
                text: hi
                children:
                  - type: text
                    text: nested
                """;

        assertThatThrownBy(() -> ViewParser.parse(yaml, "v.yaml"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("carries no `children`");
    }

    @Test
    void parse_tableWithoutFrom_isRejected() {
        assertThatThrownBy(() -> ViewParser.parse("type: table\n", "v.yaml"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("a `table` needs `from`");
    }

    /**
     * {@code source:} named a folder or a declared table; both concepts are
     * gone. Left unrecognised, such a widget would render empty and the author
     * would look for the mistake in the program.
     */
    @Test
    void parse_widgetWithTheRemovedSourceKey_isRejectedByName() {
        assertThatThrownBy(() -> ViewParser.parse("type: table\nsource: invoices\n", "v.yaml"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("`source` no longer exists")
                .hasMessageContaining("from:");
    }

    @Test
    void parse_textBoundToStateInsteadOfALiteral_isAccepted() {
        ViewNode node = ViewParser.parse("type: text\nfrom: greeting\n", "v.yaml");

        assertThat(node.from()).isEqualTo("greeting");
        assertThat(node.text()).isNull();
    }

    @Test
    void parse_textWithNeitherLiteralNorBinding_isRejected() {
        assertThatThrownBy(() -> ViewParser.parse("type: text\n", "v.yaml"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("needs `text` (a literal) or `from`");
    }

    @Test
    void parse_formWithoutFields_isRejected() {
        assertThatThrownBy(() -> ViewParser.parse("type: form\nfrom: rows\n", "v.yaml"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("at least one entry under `fields`");
    }

    /**
     * The read-only twin of {@code form}. It exists so that "can the reader
     * type here" is answered by the widget's name instead of by a boolean
     * whose default would be wrong for one of the two uses.
     */
    @Test
    void parse_details_readsTheSameFieldListAsAForm() {
        String yaml = """
                type: details
                from: invoice
                fields:
                  - name: customer
                    type: string
                    label: { en: Customer }
                """;

        ViewNode node = ViewParser.parse(yaml, "v.yaml");

        assertThat(node.type()).isEqualTo("details");
        assertThat(node.fields()).hasSize(1);
    }

    @Test
    void parse_detailsWithoutFrom_isRejected() {
        String yaml = """
                type: details
                fields:
                  - name: a
                    type: string
                    label: { en: A }
                """;

        assertThatThrownBy(() -> ViewParser.parse(yaml, "v.yaml"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("a `details` needs `from`");
    }

    /**
     * The setting-form keys are refused on a {@code details} too. They are
     * dropped by the shared field parser either way, so a condition written on
     * a read-only field would be just as silently ineffective.
     */
    @Test
    void parse_detailsFieldWithShowIf_isRejected() {
        String yaml = """
                type: details
                from: invoice
                fields:
                  - name: a
                    type: string
                    label: { en: A }
                    showIf: other
                """;

        assertThatThrownBy(() -> ViewParser.parse(yaml, "v.yaml"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("setting forms");
    }

    @Test
    void parse_fieldsOnNonForm_isRejected() {
        String yaml = """
                type: text
                text: hi
                fields:
                  - name: a
                    type: string
                    label: { en: A }
                """;

        assertThatThrownBy(() -> ViewParser.parse(yaml, "v.yaml"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("`fields` belongs to a `form` or a `details`");
    }

    /**
     * {@code showIf} is a setting-form key that the shared field parser
     * deliberately does not read, so it would be dropped without a word and
     * the field would always show. This is checked against the document, not
     * against the parsed DTO — on the DTO the key is always null, which is why
     * the first version of the check could never fire.
     */
    @Test
    void parse_formFieldWithShowIf_isRejectedWithItsOwnMessage() {
        String yaml = """
                type: form
                from: rows
                fields:
                  - name: a
                    type: string
                    label: { en: A }
                    showIf: "values.b"
                """;

        assertThatThrownBy(() -> ViewParser.parse(yaml, "v.yaml"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("showIf")
                .hasMessageContaining("setting forms");
    }

    @Test
    void parse_formFieldWithBindsTo_isRejected() {
        String yaml = """
                type: form
                from: rows
                fields:
                  - name: a
                    type: string
                    label: { en: A }
                    bindsTo:
                      key: some.setting
                """;

        assertThatThrownBy(() -> ViewParser.parse(yaml, "v.yaml"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("bindsTo");
    }

    /** A {@code repeat} nests its own fields, so the check has to recurse. */
    @Test
    void parse_settingFormKeyNestedInARepeatItem_isRejected() {
        String yaml = """
                type: form
                from: rows
                fields:
                  - name: lines
                    type: repeat
                    label: { en: Lines }
                    item:
                      - name: amount
                        type: integer
                        label: { en: Amount }
                        writeIf: "values.x"
                """;

        assertThatThrownBy(() -> ViewParser.parse(yaml, "v.yaml"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("writeIf");
    }

    @Test
    void parse_formFields_areReadWithTheSharedFormParser() {
        String yaml = """
                type: form
                from: rows
                fields:
                  - name: customer
                    type: string
                    required: true
                    label: { en: Customer }
                  - name: note
                    type: textarea
                    rows: 3
                    label: { en: Note }
                """;

        ViewNode form = ViewParser.parse(yaml, "v.yaml");

        assertThat(form.fields()).hasSize(2);
        assertThat(form.fields().get(0).getName()).isEqualTo("customer");
        assertThat(form.fields().get(0).isRequired()).isTrue();
        assertThat(form.fields().get(1).getRows()).isEqualTo(3);
    }

    // ── handler grammar ───────────────────────────────────────────

    @Test
    void action_reload_parsesAsReload() {
        ViewAction action = ViewParser.action("reload", "v.yaml", "");

        assertThat(action.kind()).isEqualTo(ActionKind.RELOAD);
    }

    /**
     * The generic {@code ref:function} split takes the last colon, so
     * {@code navigate:edit} would otherwise become a script called "navigate"
     * exporting "edit". The prefix has to be tested first.
     */
    @Test
    void action_navigate_isNotMistakenForAScriptCall() {
        ViewAction action = ViewParser.action("navigate:edit", "v.yaml", "");

        assertThat(action.kind()).isEqualTo(ActionKind.NAVIGATE);
        assertThat(action.target()).isEqualTo("edit");
        assertThat(action.scriptRef()).isNull();
    }

    @Test
    void action_navigateWithoutHandle_isRejected() {
        assertThatThrownBy(() -> ViewParser.action("navigate:", "v.yaml", ""))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("needs a view handle");
    }

    @Test
    void action_scriptReference_splitsOnTheLastColon() {
        ViewAction action = ViewParser.action("scripts/main.js:openInvoice", "v.yaml", "");

        assertThat(action.kind()).isEqualTo(ActionKind.SCRIPT);
        assertThat(action.scriptRef()).isEqualTo("scripts/main.js");
        assertThat(action.function()).isEqualTo("openInvoice");
    }

    @Test
    void action_withoutSeparator_isRejected() {
        assertThatThrownBy(() -> ViewParser.action("doSomething", "v.yaml", ""))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("cannot read handler");
    }

    @Test
    void action_functionNameNotAnIdentifier_isRejected() {
        assertThatThrownBy(() -> ViewParser.action("main.js:not a name", "v.yaml", ""))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("is not a function name");
    }

    @Test
    void parse_emptyHandler_isRejected() {
        String yaml = """
                type: button
                label: Go
                on:
                  click: ""
                """;

        assertThatThrownBy(() -> ViewParser.parse(yaml, "v.yaml"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("handler is empty");
    }

    // ── bounds ────────────────────────────────────────────────────

    @Test
    void parse_nestedBeyondTheDepthLimit_isRejected() {
        StringBuilder sb = new StringBuilder("type: page\n");
        String indent = "";
        for (int i = 0; i <= ViewParser.MAX_DEPTH + 1; i++) {
            sb.append(indent).append("children:\n");
            indent += "  ";
            sb.append(indent).append("- type: page\n");
            indent += "  ";
        }

        assertThatThrownBy(() -> ViewParser.parse(sb.toString(), "v.yaml"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("nested deeper than");
    }

    @Test
    void parse_moreWidgetsThanTheNodeLimit_isRejected() {
        StringBuilder sb = new StringBuilder("type: page\nchildren:\n");
        for (int i = 0; i <= ViewParser.MAX_NODES; i++) {
            sb.append("  - type: text\n    text: x\n");
        }

        assertThatThrownBy(() -> ViewParser.parse(sb.toString(), "v.yaml"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("more than");
    }
}
