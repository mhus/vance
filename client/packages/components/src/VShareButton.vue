<script setup lang="ts">
/**
 * Icon button that hands one entry to Milliways: "show this to someone".
 *
 * Lives here rather than in each app because three apps want the same thing
 * (search hit, feed item, link entry) and a fourth will want it too. The
 * subject is the whole contract — title, link, snippet — and everything after
 * the click is Milliways logic: which ways exist, what each one asks for, who
 * may receive it.
 *
 * **Renders nothing when the host does not offer sharing.** The opener comes
 * from `provide('vance:share', fn)` (Cortex does that); an app mounted
 * somewhere without it simply shows no button rather than a dead one. Federated
 * addons therefore need no dependency on `vance-face` — the string key is the
 * whole bridge.
 *
 * The label is a prop because this package has no i18n. A host that has one
 * passes a translated string; an addon that has none gets readable English.
 *
 * See specification/public/milliways-system.md.
 */
import { inject } from 'vue';

/**
 * What is being shared. At least one of `link` / `snippet` / `documentPath`
 * must be set — the server refuses a subject that names nothing to show, and
 * `title` alone is a label, not a thing.
 */
export interface ShareSubjectInput {
  title?: string;
  link?: string;
  snippet?: string;
  documentPath?: string;
}

const props = withDefaults(defineProps<{
  subject: ShareSubjectInput;
  /** Tooltip and accessible name. Pass a translated string when you have one. */
  label?: string;
  size?: 'xs' | 'sm';
}>(), { label: 'Share', size: 'sm' });

const share = inject<((subject: ShareSubjectInput) => void) | null>('vance:share', null);

function onClick(): void {
  share?.(props.subject);
}
</script>

<template>
  <!-- click.stop because these buttons sit inside cards that are themselves
       toggles: sharing an entry is not a request to collapse it. -->
  <button
    v-if="share"
    type="button"
    class="rounded px-1.5 leading-none opacity-60 hover:opacity-100 hover:bg-base-200"
    :class="size === 'xs' ? 'text-xs py-0' : 'text-sm py-0.5'"
    :title="label"
    :aria-label="label"
    @click.stop="onClick"
  >📤</button>
</template>
