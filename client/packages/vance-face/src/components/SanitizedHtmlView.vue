<script setup lang="ts">
import { computed } from 'vue';
import { sanitizeHtml } from './sanitizeHtml';

/**
 * Untrusted HTML, sanitised and inserted **as written**.
 *
 * <p>The counterpart to {@link MarkdownView} and deliberately not a mode of it:
 * Markdown is a lossy channel for HTML, and the loss is structural. A blank line
 * inside a block element makes the parser wrap the content in a `<p>` nobody
 * wrote; a line starting with `-` becomes a list. For prose that is the whole
 * point, for a layout somebody designed it is a different layout.
 *
 * <p><b>Same allow-list, no extra authority.</b> Both renderers sanitise through
 * `sanitizeHtml.ts`, so this is not a way to get a `<script>` onto the page. What
 * it buys is fidelity: the markup arrives the way it was written, minus what the
 * sanitiser removes.
 *
 * <p>No click delegation here. `MarkdownView` intercepts `vance:` links because
 * it *creates* them from Markdown; a `vance:` href written by hand in raw HTML
 * survives sanitisation but navigates nowhere — which is honest, and the reason
 * the manual says to use Markdown for links.
 */
const props = defineProps<{ content?: string | null }>();

const html = computed(() => sanitizeHtml(String(props.content ?? '')));
</script>

<template>
  <!-- eslint-disable-next-line vue/no-v-html -->
  <div class="sanitized-html" v-html="html" />
</template>

<style scoped>
.sanitized-html {
  overflow-wrap: anywhere;
}
</style>
