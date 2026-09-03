// Inline slash-command extension. Typing `/` at the cursor opens a
// Notion-style popup with filter-as-you-type — arrow keys + Enter to
// pick a block type. Wired up via @tiptap/suggestion + tippy.js for the
// floating anchor, SlashCommandList.vue for the popup body.
//
// Trigger rule: `/` at a position where a word would naturally start
// (start of line OR after a space). That's the @tiptap/suggestion
// default with `startOfLine: false` and the built-in word-boundary
// match — works well for "type 'asdf' then /" but also accepts a
// fresh empty paragraph.

import { Extension, type Editor, type Range } from '@tiptap/core';
import Suggestion, { type SuggestionOptions } from '@tiptap/suggestion';
import { VueRenderer } from '@tiptap/vue-3';
import tippy, { type Instance as TippyInstance } from 'tippy.js';
import SlashCommandList, { type SlashCommandItem } from './SlashCommandList.vue';
import { registeredBlocks } from './blockRegistry';
import { translatorFor, type Translate } from './useT';

interface CommandContext {
  editor: Editor;
  range: Range;
}

interface SlashItemDef extends SlashCommandItem {
  run: (ctx: CommandContext) => void;
}

/**
 * A core item as it is written down: label and hint as i18n keys, because this
 * list is a module-level constant and a literal could not follow a language
 * switch. {@link resolve} turns one into a {@link SlashItemDef}.
 */
interface SlashItemSpec {
  id: string;
  titleKey: string;
  hintKey: string;
  run: (ctx: CommandContext) => void;
}

function resolve(spec: SlashItemSpec, t: Translate): SlashItemDef {
  return { id: spec.id, title: t(spec.titleKey), hint: t(spec.hintKey), run: spec.run };
}

const ITEMS: SlashItemSpec[] = [
  {
    id: 'paragraph',
    titleKey: 'blockEditor.slash.paragraph.title',
    hintKey: 'blockEditor.slash.paragraph.hint',
    run: ({ editor, range }) =>
      editor.chain().focus().deleteRange(range).setParagraph().run(),
  },
  {
    id: 'heading-1',
    titleKey: 'blockEditor.slash.heading-1.title',
    hintKey: 'blockEditor.slash.heading-1.hint',
    run: ({ editor, range }) =>
      editor.chain().focus().deleteRange(range).setNode('heading', { level: 1 }).run(),
  },
  {
    id: 'heading-2',
    titleKey: 'blockEditor.slash.heading-2.title',
    hintKey: 'blockEditor.slash.heading-2.hint',
    run: ({ editor, range }) =>
      editor.chain().focus().deleteRange(range).setNode('heading', { level: 2 }).run(),
  },
  {
    id: 'heading-3',
    titleKey: 'blockEditor.slash.heading-3.title',
    hintKey: 'blockEditor.slash.heading-3.hint',
    run: ({ editor, range }) =>
      editor.chain().focus().deleteRange(range).setNode('heading', { level: 3 }).run(),
  },
  {
    id: 'bullet',
    titleKey: 'blockEditor.slash.bullet.title',
    hintKey: 'blockEditor.slash.bullet.hint',
    run: ({ editor, range }) =>
      editor.chain().focus().deleteRange(range).toggleBulletList().run(),
  },
  {
    id: 'numbered',
    titleKey: 'blockEditor.slash.numbered.title',
    hintKey: 'blockEditor.slash.numbered.hint',
    run: ({ editor, range }) =>
      editor.chain().focus().deleteRange(range).toggleOrderedList().run(),
  },
  {
    id: 'todo',
    titleKey: 'blockEditor.slash.todo.title',
    hintKey: 'blockEditor.slash.todo.hint',
    run: ({ editor, range }) =>
      editor.chain().focus().deleteRange(range).toggleTaskList().run(),
  },
  {
    id: 'quote',
    titleKey: 'blockEditor.slash.quote.title',
    hintKey: 'blockEditor.slash.quote.hint',
    run: ({ editor, range }) =>
      editor.chain().focus().deleteRange(range).toggleBlockquote().run(),
  },
  {
    id: 'code',
    titleKey: 'blockEditor.slash.code.title',
    hintKey: 'blockEditor.slash.code.hint',
    run: ({ editor, range }) =>
      editor.chain().focus().deleteRange(range).toggleCodeBlock().run(),
  },
  {
    id: 'divider',
    titleKey: 'blockEditor.slash.divider.title',
    hintKey: 'blockEditor.slash.divider.hint',
    run: ({ editor, range }) =>
      editor.chain().focus().deleteRange(range).setHorizontalRule().run(),
  },
  {
    id: 'toggle',
    titleKey: 'blockEditor.slash.toggle.title',
    hintKey: 'blockEditor.slash.toggle.hint',
    run: ({ editor, range }) =>
      editor
        .chain()
        .focus()
        .deleteRange(range)
        .insertContent({
          type: 'vanceToggle',
          attrs: { summary: 'Details', body: '' },
        })
        .run(),
  },
  {
    id: 'link-card',
    titleKey: 'blockEditor.slash.link-card.title',
    hintKey: 'blockEditor.slash.link-card.hint',
    run: ({ editor, range }) =>
      editor
        .chain()
        .focus()
        .deleteRange(range)
        .insertContent({
          type: 'vanceLink',
          attrs: { href: '', title: null, description: null },
        })
        .run(),
  },
  {
    id: 'dataview',
    titleKey: 'blockEditor.slash.dataview.title',
    hintKey: 'blockEditor.slash.dataview.hint',
    run: ({ editor, range }) =>
      editor
        .chain()
        .focus()
        .deleteRange(range)
        .insertContent({ type: 'vanceDataview', attrs: { source: '' } })
        .run(),
  },
  {
    id: 'image',
    titleKey: 'blockEditor.slash.image.title',
    hintKey: 'blockEditor.slash.image.hint',
    run: ({ editor, range }) => {
      editor.chain().focus().deleteRange(range).run();
      editor.view.dom.dispatchEvent(
        new CustomEvent('vance:open-asset-picker', { bubbles: true }),
      );
    },
  },
  {
    id: 'toc',
    titleKey: 'blockEditor.slash.toc.title',
    hintKey: 'blockEditor.slash.toc.hint',
    run: ({ editor, range }) =>
      editor.chain().focus().deleteRange(range).insertContent({ type: 'vanceToc' }).run(),
  },
  {
    id: 'embed',
    titleKey: 'blockEditor.slash.embed.title',
    hintKey: 'blockEditor.slash.embed.hint',
    run: ({ editor, range }) => {
      // Remove the slash trigger, then bubble a DOM event so the host
      // (workspace addon, etc.) can open its embed picker. Same
      // pattern as the asset picker for image inserts.
      editor.chain().focus().deleteRange(range).run();
      editor.view.dom.dispatchEvent(
        new CustomEvent('vance:open-embed-picker', { bubbles: true }),
      );
    },
  },
  {
    id: 'form',
    titleKey: 'blockEditor.slash.form.title',
    hintKey: 'blockEditor.slash.form.hint',
    run: ({ editor, range }) => {
      // Same pattern as the embed picker: drop the slash trigger and
      // bubble a DOM event so the host opens its form (edit-config)
      // picker, which calls back via insertForm.
      editor.chain().focus().deleteRange(range).run();
      editor.view.dom.dispatchEvent(
        new CustomEvent('vance:open-form-picker', { bubbles: true }),
      );
    },
  },
  {
    id: 'input',
    titleKey: 'blockEditor.slash.input.title',
    hintKey: 'blockEditor.slash.input.hint',
    run: ({ editor, range }) => {
      editor.chain().focus().deleteRange(range).run();
      editor.view.dom.dispatchEvent(
        new CustomEvent('vance:open-input-picker', { bubbles: true }),
      );
    },
  },
  {
    id: 'button',
    titleKey: 'blockEditor.slash.button.title',
    hintKey: 'blockEditor.slash.button.hint',
    run: ({ editor, range }) =>
      editor.chain().focus().deleteRange(range)
        .insertContent({ type: 'vanceButton', attrs: { type: 'script', script: '', title: '' } })
        .run(),
  },
  {
    id: 'compose',
    titleKey: 'blockEditor.slash.compose.title',
    hintKey: 'blockEditor.slash.compose.hint',
    run: ({ editor, range }) =>
      editor.chain().focus().deleteRange(range)
        .insertContent({
          type: 'vanceCompose',
          attrs: {
            yaml:
              'title: My Compose\n'
              + 'description: What this compose does.\n'
              + 'workspace:\n  name: my-workspace\n  type: temp\n'
              + 'tasks:\n  - type: exec\n    command: echo "hello from damogran" > out.txt\n'
              + '    outputs:\n      - out.txt\n',
          },
        })
        .run(),
  },
  {
    id: 'columns2',
    titleKey: 'blockEditor.slash.columns2.title',
    hintKey: 'blockEditor.slash.columns2.hint',
    run: ({ editor, range }) => {
      editor
        .chain()
        .focus()
        .deleteRange(range)
        .insertContent({
          type: 'vanceColumns',
          content: [
            { type: 'vanceColumn', content: [{ type: 'paragraph' }] },
            { type: 'vanceColumn', content: [{ type: 'paragraph' }] },
          ],
        })
        .run();
    },
  },
  {
    id: 'columns3',
    titleKey: 'blockEditor.slash.columns3.title',
    hintKey: 'blockEditor.slash.columns3.hint',
    run: ({ editor, range }) => {
      editor
        .chain()
        .focus()
        .deleteRange(range)
        .insertContent({
          type: 'vanceColumns',
          content: [
            { type: 'vanceColumn', content: [{ type: 'paragraph' }] },
            { type: 'vanceColumn', content: [{ type: 'paragraph' }] },
            { type: 'vanceColumn', content: [{ type: 'paragraph' }] },
          ],
        })
        .run();
    },
  },
];

export const SlashCommands = Extension.create({
  name: 'slashCommands',

  addOptions() {
    return {
      suggestion: {
        char: '/',
        startOfLine: false,
        command: ({ editor, range, props }: {
          editor: Editor;
          range: Range;
          props: SlashItemDef;
        }) => {
          props.run({ editor, range });
        },
        items: ({ editor, query }: { editor: Editor; query: string }): SlashItemDef[] => {
          // Labels are resolved here, not at module load: the menu is rebuilt
          // on every keystroke, so this is also where a language switch takes
          // effect. The translator comes off the editor's app context, which
          // Tiptap copied from the component that mounted the editor.
          const t = translatorFor(
            (editor as unknown as {
              appContext?: { config?: { globalProperties?: Record<string, unknown> } };
            }).appContext,
          );
          // Core items + addon-contributed slash items (block-extension-
          // registry). Registry entries are read per-keystroke so an
          // addon that registers after this module loaded still shows up.
          const registryItems: SlashItemDef[] = registeredBlocks()
            .filter((b) => b.slash)
            .map((b) => ({
              id: `ext:${b.fence}`,
              title: b.slash!.titleKey ? t(b.slash!.titleKey) : b.slash!.title,
              hint: b.slash!.hintKey ? t(b.slash!.hintKey) : b.slash!.hint,
              run: (ctx) => b.slash!.insert(ctx),
            }));
          const all = [...ITEMS.map((spec) => resolve(spec, t)), ...registryItems];
          const q = query.toLowerCase();
          if (!q) return all;
          return all.filter(
            (item) =>
              item.title.toLowerCase().includes(q) ||
              item.id.toLowerCase().includes(q) ||
              item.hint.toLowerCase().includes(q),
          );
        },
        render: () => {
          let component: VueRenderer | null = null;
          let popup: TippyInstance[] | null = null;

          return {
            onStart: (suggestionProps: {
              clientRect?: () => DOMRect | null;
              editor: Editor;
              command: (item: SlashItemDef) => void;
              items: SlashItemDef[];
            }) => {
              component = new VueRenderer(SlashCommandList, {
                props: suggestionProps,
                editor: suggestionProps.editor,
              });
              if (!suggestionProps.clientRect) return;
              popup = tippy('body', {
                getReferenceClientRect: suggestionProps.clientRect as () => DOMRect,
                // Append to the enclosing <dialog> when the editor is inside
                // a modal — a showModal() dialog lives in the browser top
                // layer, so a body-appended popup would render BEHIND it.
                appendTo: () =>
                  suggestionProps.editor.view.dom.closest('dialog') ?? document.body,
                content: component.element as Element,
                showOnCreate: true,
                interactive: true,
                trigger: 'manual',
                placement: 'bottom-start',
                offset: [0, 6],
              });
            },
            onUpdate(suggestionProps: {
              clientRect?: () => DOMRect | null;
              items: SlashItemDef[];
            }) {
              component?.updateProps(suggestionProps);
              if (!suggestionProps.clientRect || !popup) return;
              popup[0].setProps({
                getReferenceClientRect: suggestionProps.clientRect as () => DOMRect,
              });
            },
            onKeyDown(props: { event: KeyboardEvent }) {
              if (props.event.key === 'Escape') {
                popup?.[0].hide();
                return true;
              }
              const exposed = component?.ref as
                | { onKeyDown?: (p: { event: KeyboardEvent }) => boolean }
                | undefined;
              return exposed?.onKeyDown?.(props) ?? false;
            },
            onExit() {
              popup?.[0].destroy();
              component?.destroy();
              popup = null;
              component = null;
            },
          };
        },
      } as Partial<SuggestionOptions>,
    };
  },

  addProseMirrorPlugins() {
    return [
      Suggestion({
        editor: this.editor,
        ...this.options.suggestion,
      }),
    ];
  },
});
