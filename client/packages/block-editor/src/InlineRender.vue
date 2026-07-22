<script setup lang="ts">
import { computed } from 'vue';
import { parseInline, type InlineSegment } from './markdown/inline';
import { safeHref } from './safeHref';

/**
 * Renders a Markdown inline string with bold/italic/code/strike/link
 * marks. Used by BlockView (read-only) for the textual content of
 * paragraphs, headings, list items, etc. Text is XSS-safe via Vue's
 * auto-escaping (no v-html, no external Markdown lib); link hrefs are
 * scheme-filtered through {@link safeHref} because Vue does NOT escape
 * the scheme of a :href binding (a `javascript:` URL would otherwise
 * execute on click — code-review B6).
 */
const props = defineProps<{
  text: string;
}>();

const segments = computed<InlineSegment[]>(() => parseInline(props.text));

function hasMark(seg: InlineSegment, type: string): boolean {
  return seg.marks.some((m) => m.type === type);
}

function linkHref(seg: InlineSegment): string {
  const link = seg.marks.find((m) => m.type === 'link');
  return safeHref(link?.attrs?.href as string | undefined);
}
</script>

<template>
  <template v-for="(seg, i) in segments" :key="i">
    <a
      v-if="hasMark(seg, 'link')"
      :href="linkHref(seg)"
      target="_blank"
      rel="noopener noreferrer"
      class="inline-link"
    >{{ seg.text }}</a>
    <template v-else>
      <strong v-if="hasMark(seg, 'bold')">
        <em v-if="hasMark(seg, 'italic')">
          <code v-if="hasMark(seg, 'code')">{{ seg.text }}</code>
          <s v-else-if="hasMark(seg, 'strike')">{{ seg.text }}</s>
          <template v-else>{{ seg.text }}</template>
        </em>
        <code v-else-if="hasMark(seg, 'code')">{{ seg.text }}</code>
        <s v-else-if="hasMark(seg, 'strike')">{{ seg.text }}</s>
        <template v-else>{{ seg.text }}</template>
      </strong>
      <em v-else-if="hasMark(seg, 'italic')">
        <code v-if="hasMark(seg, 'code')">{{ seg.text }}</code>
        <s v-else-if="hasMark(seg, 'strike')">{{ seg.text }}</s>
        <template v-else>{{ seg.text }}</template>
      </em>
      <code v-else-if="hasMark(seg, 'code')">{{ seg.text }}</code>
      <s v-else-if="hasMark(seg, 'strike')">{{ seg.text }}</s>
      <template v-else>{{ seg.text }}</template>
    </template>
  </template>
</template>

<style>
.inline-link {
  color: oklch(var(--p));
  text-decoration: underline;
}
.inline-link:hover { filter: brightness(0.85); }
</style>
