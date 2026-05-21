<script setup lang="ts">
/**
 * Common frame for rich-content blocks rendered from the chat or
 * other Markdown surfaces. Header carries label/icon/title plus an
 * action-slot. Body is the renderer output (or any inline content)
 * via the default slot.
 *
 * Channel-specific wrappers ({@code InlineKindBox},
 * {@code EmbeddedKindBox}) supply the default action buttons; this
 * component is intentionally generic so unknown-kind fallback cards
 * and ad-hoc test scaffolds can reuse it.
 *
 * Spec: specification/inline-and-embedded-content.md §11.6.
 */
interface Props {
  /** Kind name (lowercase). Drives icon/label lookup if not given. */
  kind?: string;
  /** Header label. */
  label: string;
  /** Header icon (emoji or short glyph). */
  icon?: string;
  /** Optional secondary line — e.g. document title, file path. */
  title?: string;
}

defineProps<Props>();
</script>

<template>
  <div class="kind-box">
    <div class="kind-box-header">
      <span v-if="icon" class="kind-box-icon">{{ icon }}</span>
      <span class="kind-box-label">{{ label }}</span>
      <span v-if="title" class="kind-box-title">— {{ title }}</span>
      <div class="kind-box-actions">
        <slot name="actions" />
      </div>
    </div>
    <div class="kind-box-body">
      <slot />
    </div>
  </div>
</template>

<style scoped>
.kind-box {
  display: block;
  width: 100%;
  box-sizing: border-box;
  /* Don't shrink inside a flex parent (chat bubble row, etc.). */
  flex: 0 0 100%;
  border: 1px solid hsl(var(--bc) / 0.18);
  border-radius: 0.5rem;
  overflow: hidden;
  margin: 0.6em 0;
  background: hsl(var(--b1));
}
.kind-box-header {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.4rem 0.75rem;
  background: hsl(var(--bc) / 0.06);
  border-bottom: 1px solid hsl(var(--bc) / 0.12);
  font-size: 0.85rem;
}
.kind-box-icon { font-size: 1rem; line-height: 1; }
.kind-box-label { font-weight: 600; }
.kind-box-title {
  opacity: 0.7;
  font-weight: 400;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  min-width: 0;
  flex: 1;
}
.kind-box-actions {
  margin-left: auto;
  display: flex;
  gap: 0.25rem;
  flex: 0 0 auto;
}
.kind-box-body {
  padding: 0.5rem;
}
</style>
