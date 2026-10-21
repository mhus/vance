<script setup lang="ts">
import { computed, ref } from 'vue';
import { VButton, VIconButton } from '@vance/components';
import type { LinkEntryView } from './generated/links/LinkEntryView';
import { useFrameHistory } from './frameHistory';
import { useT } from './i18n';

/**
 * One link, opened in place: the app area becomes a browsing pane with a
 * small chrome of its own.
 *
 * <p>Back, forward, reload — the three things a browser chrome gives and a
 * plain cross-origin {@code <iframe>} does not. None of them has a real API
 * against a foreign frame ({@code contentWindow.history} is blocked,
 * {@code location.reload()} is blocked); the buttons use the one mechanism
 * that works, the joint session history, and the counting that decides when
 * they are enabled lives in {@link useFrameHistory} together with its
 * honest limits.
 *
 * <p><b>The frame fills the remaining height and scrolls inside.</b> The
 * framed page's content height is unreadable cross-origin, so "grow to the
 * content" is not a design choice to decline — it does not exist. A fixed
 * pane is also the stabler reading surface: the chrome stays where it is
 * while the page loads and reflows.
 *
 * <p><b>Sandboxed, minus top navigation.</b> The framed page is a normal
 * page in its own origin — scripts, forms, logins, popups and downloads
 * have to work for this to be worth a mode at all, so the sandbox grants
 * them. What it withholds is {@code allow-top-navigation}: an unsandboxed
 * frame may, after one click inside it, replace this whole window
 * ({@code top.location = …}) with a look-alike login — a power the same page
 * in a tab does not have over the app. Frame-busting sites break out of
 * nothing then; they stay framed or show the browser's refusal, and the
 * "open in a new tab" button is the way out. {@code referrerpolicy="no-referrer"}
 * matches the app's other foreign requests.
 *
 * <p><b>Some sites refuse to be embedded</b> ({@code X-Frame-Options},
 * {@code frame-ancestors}) and the pane then shows the browser's refusal —
 * undetectable from here, which is why the toolbar keeps an "open in a new
 * tab" button at all times rather than offering help only after guessing.
 */
const props = defineProps<{ entry: LinkEntryView }>();

const emit = defineEmits<{ (e: 'close'): void }>();

const t = useT();

const frame = ref<HTMLIFrameElement | null>(null);
const history = useFrameHistory();

/** The address this list knows — the reload target and the escape hatch. */
const url = computed(() => props.entry.url);

function onLoad(): void {
  history.noteLoad();
}

function onBack(): void {
  if (history.beginBack()) window.history.back();
}

function onForward(): void {
  if (history.beginForward()) window.history.forward();
}

function onReload(): void {
  history.beginReload();
  // `location.replace` is the one navigation a cross-origin frame still
  // accepts from its parent, and the one that does not pile up a history
  // entry. It reloads the address the list knows — not whatever the reader
  // clicked their way to inside the frame, which is unreadable cross-origin.
  frame.value?.contentWindow?.location.replace(url.value);
}

function onOpenExternal(): void {
  window.open(url.value, '_blank', 'noopener,noreferrer');
}
</script>

<template>
  <div class="flex h-full min-h-0 flex-col gap-2">
    <!-- The pane's chrome. Hairline under it, like every toolbar in the
         app: navigation on the left, the address on the right, and the escape
         hatch that stays reachable when a site refuses to be embedded. -->
    <div
      class="flex flex-none flex-wrap items-center gap-1 border-b border-(--v-separator) pb-2"
    >
      <VButton variant="plain" icon="back" :title="t('links.frame.backToLinksTip')" @click="emit('close')">
        {{ t('links.frame.backToLinks') }}
      </VButton>

      <span class="mx-1 self-stretch border-l border-(--v-separator)" aria-hidden="true"></span>

      <VIconButton
        icon="arrow-left"
        :label="t('links.frame.back')"
        :disabled="!history.canBack.value"
        @click="onBack()"
      />
      <VIconButton
        icon="arrow-right"
        :label="t('links.frame.forward')"
        :disabled="!history.canForward.value"
        @click="onForward()"
      />
      <VIconButton
        icon="refresh"
        :label="t('links.frame.reload')"
        :title="t('links.frame.reloadTip')"
        @click="onReload()"
      />

      <span class="min-w-0 flex-1 self-center truncate text-right font-mono text-xs opacity-60" :title="url">
        {{ entry.host }}
      </span>

      <VIconButton
        icon="external"
        :label="t('links.frame.openExternal')"
        :title="t('links.frame.openExternalTip')"
        @click="onOpenExternal()"
      />
    </div>

    <!-- Fills the remaining height; the framed page scrolls inside its own
         viewport. Sandbox without allow-top-navigation: see the component
         comment. -->
    <iframe
      ref="frame"
      :src="url"
      class="min-h-0 w-full flex-1 rounded-lg border border-(--v-separator)"
      sandbox="allow-scripts allow-same-origin allow-forms allow-popups allow-popups-to-escape-sandbox allow-downloads allow-modals"
      referrerpolicy="no-referrer"
      @load="onLoad()"
    ></iframe>
  </div>
</template>
