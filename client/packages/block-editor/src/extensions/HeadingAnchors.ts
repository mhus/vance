/**
 * Heading anchors — Tiptap extension that decorates every {@code h1/h2/h3}
 * with a slug-based {@code id} attribute (so native {@code #hash}
 * navigation jumps to it) plus a tiny copy-link button that becomes
 * visible on hover. Clicking the button writes the current URL with
 * the heading's slug appended to the clipboard.
 *
 * The slugifier keeps a transient {@code Map<text, count>} per render
 * pass so two headings with identical text get unique ids
 * ({@code "intro"}, {@code "intro-2"}, …).
 */
import { Extension } from '@tiptap/core';
import { Plugin, PluginKey } from '@tiptap/pm/state';
import { Decoration, DecorationSet } from '@tiptap/pm/view';
import type { EditorState } from '@tiptap/pm/state';

function slugify(text: string): string {
  return text
    .toLowerCase()
    .trim()
    .replace(/[^\w\s-]/g, '')
    .replace(/\s+/g, '-')
    .replace(/-+/g, '-')
    .replace(/^-+|-+$/g, '');
}

function copyAnchor(slug: string): void {
  if (typeof window === 'undefined') return;
  try {
    const url = new URL(window.location.href);
    url.hash = slug;
    void navigator.clipboard?.writeText(url.toString());
  } catch {
    /* ignored — clipboard API unavailable */
  }
}

function buildDecorations(state: EditorState): DecorationSet {
  const decos: Decoration[] = [];
  const used = new Map<string, number>();
  state.doc.descendants((node, pos) => {
    if (node.type.name !== 'heading') return;
    const base = slugify(node.textContent || 'heading');
    const seen = used.get(base) ?? 0;
    used.set(base, seen + 1);
    const slug = seen === 0 ? base : `${base}-${seen + 1}`;

    decos.push(Decoration.node(pos, pos + node.nodeSize, { id: slug }));
    decos.push(
      Decoration.widget(
        pos + 1,
        () => {
          const btn = document.createElement('button');
          btn.className = 'heading-anchor-btn';
          btn.contentEditable = 'false';
          btn.type = 'button';
          btn.title = 'Copy link to this heading';
          btn.textContent = '#';
          btn.dataset.slug = slug;
          btn.addEventListener('mousedown', (e) => {
            // Stop ProseMirror from treating the click as a selection
            // change inside the heading.
            e.preventDefault();
            e.stopPropagation();
          });
          btn.addEventListener('click', (e) => {
            e.preventDefault();
            e.stopPropagation();
            copyAnchor(slug);
            btn.classList.add('heading-anchor-btn--copied');
            window.setTimeout(() => btn.classList.remove('heading-anchor-btn--copied'), 1200);
          });
          return btn;
        },
        { side: -1, ignoreSelection: true, key: `anchor:${slug}` },
      ),
    );
  });
  return DecorationSet.create(state.doc, decos);
}

export const HeadingAnchors = Extension.create({
  name: 'headingAnchors',
  addProseMirrorPlugins() {
    const pluginKey = new PluginKey('headingAnchors');
    return [
      new Plugin({
        key: pluginKey,
        state: {
          init: (_cfg, state) => buildDecorations(state),
          apply: (tr, oldSet, _oldState, newState) =>
            tr.docChanged ? buildDecorations(newState) : oldSet,
        },
        props: {
          decorations(state) {
            return pluginKey.getState(state) as DecorationSet | undefined;
          },
        },
      }),
    ];
  },
});
