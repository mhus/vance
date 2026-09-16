<script lang="ts">
/**
 * Chat session theme frame — the /chat counterpart of
 * {@link MarkdownDocumentPreview}: it fetches the server-filtered,
 * server-scoped CSS of the session's chat theme
 * (`GET …/chat-themes/{name}/css`, the name resolved server-side from
 * the recipe's `webTheme`) and injects it as a `<style>` element in
 * the light DOM above the transcript.
 *
 * <p><b>Why a separate component.</b> The served CSS is scoped
 * server-side to `.chat-theme` (every selector carries the class), so
 * the style element can sit in the light DOM without a Shadow
 * boundary — the same compromise the markdown document preview made
 * (report-themes.md §9.3: Vue VNodes of embedded kinds break in a
 * shadow root). The scope root itself is the transcript scroll
 * container: ChatView binds `.chat-theme` + `data-mode` there, so a
 * theme's root rules (`.chat-theme { background: … }`) paint the
 * full message area, not just the centered column. This component
 * renders a fragment — the style element, then the slot content —
 * and adds nothing else to the DOM.
 *
 * <p><b>data-mode.</b> The light/dark mode lives on `<html>` — an
 * ancestor, which a descendant-scoped stylesheet cannot address. The
 * scroll container (the scope root) mirrors the resolved mode
 * (`data-mode="light|dark"`, bound to {@link resolvedUiTheme}) so a
 * theme can write mode rules against its own root:
 * `.chat-theme[data-mode=dark] …` — those selectors pass the server
 * prefixer untouched (pre-scoped pass-through).
 *
 * <p><b>Fail-open.</b> A failed fetch (network, 5xx) logs a warning
 * and renders with no theme — the default styles still apply. Not
 * cached: a failed fetch is a moment, not an answer. Same policy as
 * the markdown preview and the PDF path: a styling problem never
 * blocks a render.
 */
import { ref, watch, onMounted, h, useSlots, type PropType } from 'vue';
import type { VNode } from 'vue';
import { brainFetchText } from '@vance/shared';

const THEME_CSS_CACHE = new Map<string, string>();

/** Cap on the in-memory cache. Insertion-ordered, so the oldest key goes. */
const THEME_CSS_CACHE_MAX = 50;

function rememberThemeCss(key: string, css: string): void {
  THEME_CSS_CACHE.set(key, css);
  while (THEME_CSS_CACHE.size > THEME_CSS_CACHE_MAX) {
    const oldest = THEME_CSS_CACHE.keys().next().value;
    if (oldest === undefined) break;
    THEME_CSS_CACHE.delete(oldest);
  }
}

export default {
  name: 'ChatTheme',
  props: {
    /**
     * Theme name to fetch, already resolved server-side (the recipe's
     * `webTheme`, or `default` when none applies). {@code null} skips
     * the fetch — no transcript styling.
     */
    themeName: {
      type: [String, null] as unknown as PropType<string | null>,
      default: null,
    },
    /** Owning project — the theme cascade is project-local. {@code null}
     *  or empty skips the fetch (no project, no cascade). */
    projectId: {
      type: [String, null] as unknown as PropType<string | null>,
      default: null,
    },
  },
  setup(props) {
    const slots = useSlots();
    const themeCss = ref<string>('');

    async function loadTheme(name: string, projectId: string): Promise<void> {
      // Same-tab re-mounts are covered by this in-memory cache; the
      // server sets `Cache-Control: private, max-age=60`, so the
      // browser cache covers the cross-tab case. The key carries the
      // project — a project-layer theme with the same name is a
      // different answer.
      const key = `${projectId}/${name}`;
      const cached = THEME_CSS_CACHE.get(key);
      if (cached !== undefined) {
        themeCss.value = cached;
        return;
      }
      try {
        const css = await brainFetchText(
          `projects/${encodeURIComponent(projectId)}/chat-themes/${encodeURIComponent(name)}/css`,
        );
        const body = css ?? '';
        rememberThemeCss(key, body);
        themeCss.value = body;
      } catch (e) {
        // Fail-open: log and render with no theme. We do NOT surface
        // an error banner — a theme problem is a styling problem, not
        // a content problem.
        themeCss.value = '';
        console.warn('ChatTheme: chat-theme css fetch failed, rendering without theme', e);
      }
    }

    onMounted(() => {
      if (props.themeName && props.projectId) void loadTheme(props.themeName, props.projectId);
    });

    // Both inputs matter: a different theme obviously, and a
    // different project (same name, different cascade layer).
    watch(
      () => [props.themeName, props.projectId] as const,
      ([name, project]) => {
        if (name && project) void loadTheme(name, project);
        else themeCss.value = '';
      },
    );

    return () => {
      const children: VNode[] = [];
      // The theme CSS sits in the light DOM, scoped to this root by
      // the server-side CssScopePrefixer (every selector already
      // carries .chat-theme). DOMPurify is NOT involved here — the CSS
      // comes from our own filtered endpoint, not from message
      // content.
      if (themeCss.value) {
        children.push(h('style', themeCss.value));
      }
      children.push(...(slots.default?.() ?? []));
      // Fragment, no wrapper: the scope root is the transcript scroll
      // container (see ChatView) — this component contributes the
      // style element and nothing else.
      return children;
    };
  },
};
</script>
