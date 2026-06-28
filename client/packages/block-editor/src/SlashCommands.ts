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

interface CommandContext {
  editor: Editor;
  range: Range;
}

interface SlashItemDef extends SlashCommandItem {
  run: (ctx: CommandContext) => void;
}

const ITEMS: SlashItemDef[] = [
  {
    id: 'paragraph',
    title: 'Text',
    hint: 'Plain paragraph',
    run: ({ editor, range }) =>
      editor.chain().focus().deleteRange(range).setParagraph().run(),
  },
  {
    id: 'heading-1',
    title: 'Heading 1',
    hint: 'Large section heading',
    run: ({ editor, range }) =>
      editor.chain().focus().deleteRange(range).setNode('heading', { level: 1 }).run(),
  },
  {
    id: 'heading-2',
    title: 'Heading 2',
    hint: 'Medium heading',
    run: ({ editor, range }) =>
      editor.chain().focus().deleteRange(range).setNode('heading', { level: 2 }).run(),
  },
  {
    id: 'heading-3',
    title: 'Heading 3',
    hint: 'Small heading',
    run: ({ editor, range }) =>
      editor.chain().focus().deleteRange(range).setNode('heading', { level: 3 }).run(),
  },
  {
    id: 'bullet',
    title: 'Bullet list',
    hint: '- item',
    run: ({ editor, range }) =>
      editor.chain().focus().deleteRange(range).toggleBulletList().run(),
  },
  {
    id: 'numbered',
    title: 'Numbered list',
    hint: '1. item',
    run: ({ editor, range }) =>
      editor.chain().focus().deleteRange(range).toggleOrderedList().run(),
  },
  {
    id: 'todo',
    title: 'To-do',
    hint: '- [ ] task',
    run: ({ editor, range }) =>
      editor.chain().focus().deleteRange(range).toggleTaskList().run(),
  },
  {
    id: 'quote',
    title: 'Quote',
    hint: 'Set-off citation',
    run: ({ editor, range }) =>
      editor.chain().focus().deleteRange(range).toggleBlockquote().run(),
  },
  {
    id: 'code',
    title: 'Code block',
    hint: 'Fenced code',
    run: ({ editor, range }) =>
      editor.chain().focus().deleteRange(range).toggleCodeBlock().run(),
  },
  {
    id: 'divider',
    title: 'Divider',
    hint: '---',
    run: ({ editor, range }) =>
      editor.chain().focus().deleteRange(range).setHorizontalRule().run(),
  },
  {
    id: 'callout',
    title: 'Callout',
    hint: 'Info / Warn / Note',
    run: ({ editor, range }) =>
      editor
        .chain()
        .focus()
        .deleteRange(range)
        .insertContent({
          type: 'vanceCallout',
          attrs: { severity: 'info', title: 'Hinweis', body: '' },
        })
        .run(),
  },
  {
    id: 'toggle',
    title: 'Toggle',
    hint: 'Collapsible section',
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
    title: 'Link card',
    hint: 'Rich URL preview',
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
    title: 'Dataview',
    hint: 'Embed aggregation (stub)',
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
    title: 'Image',
    hint: 'Pick from assets or upload',
    run: ({ editor, range }) => {
      editor.chain().focus().deleteRange(range).run();
      editor.view.dom.dispatchEvent(
        new CustomEvent('vance:open-asset-picker', { bubbles: true }),
      );
    },
  },
  {
    id: 'toc',
    title: 'Table of contents',
    hint: 'Auto-generated from page headings',
    run: ({ editor, range }) =>
      editor.chain().focus().deleteRange(range).insertContent({ type: 'vanceToc' }).run(),
  },
  {
    id: 'embed',
    title: 'Embed document',
    hint: 'Inline another Vance document — kind-aware card',
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
    id: 'columns2',
    title: '2 columns',
    hint: 'Side-by-side layout',
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
    title: '3 columns',
    hint: 'Three-pane layout',
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
        items: ({ query }: { query: string }): SlashItemDef[] => {
          const q = query.toLowerCase();
          if (!q) return ITEMS;
          return ITEMS.filter(
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
                appendTo: () => document.body,
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
