<script setup lang="ts">
/**
 * Closed UI unit for ASK_USER chat messages — renders the question
 * text + structured option buttons as a single canvas.
 *
 * Replaces the old two-channel render path (Markdown bullets in
 * content + separate button row reading meta.askUserOptions). The
 * engine still appends bullets to {@code content} as a graceful
 * fallback for non-canvas clients (foot-CLI, voice); here we strip
 * the trailing bullet list when we have the structured options so
 * we don't show the same options twice.
 *
 * Spec: specification/inline-and-embedded-content.md §11 (chat-
 * message dispatch on {@code meta.actionType}).
 */
import { computed } from 'vue';
import { MarkdownView } from '@components/index';

export interface QuestionOption {
  label: string;
  description?: string;
}

interface Props {
  /** Full content from the chat message (question + fallback bullets). */
  content: string;
  /** Structured options from {@code meta.askUserOptions}. */
  options: QuestionOption[];
  /** Whether the picker is still actionable (no answering USER msg yet). */
  actionable: boolean;
}

const props = defineProps<Props>();

const emit = defineEmits<{
  (e: 'pick', label: string): void;
}>();

/**
 * Strip the trailing Markdown bullet list that the engine appends as
 * a graceful fallback for non-canvas clients. We render the buttons
 * directly so showing the bullets too is duplication. Heuristic:
 * walk back over consecutive lines that start with `- ` or `* ` and
 * carry one of the option labels; cut once we hit a non-bullet line.
 */
const questionText = computed<string>(() => {
  if (props.options.length === 0) return props.content;
  const labels = new Set(props.options.map((o) => o.label.toLowerCase()));
  const lines = props.content.split('\n');
  let cut = lines.length;
  for (let i = lines.length - 1; i >= 0; i--) {
    const ln = lines[i].trimEnd();
    if (ln === '') continue;
    const m = /^\s*[-*]\s+(?:\*\*)?([^*]+?)(?:\*\*)?\s*(?:—.*)?$/.exec(ln);
    if (m && labels.has(m[1].trim().toLowerCase())) {
      cut = i;
      continue;
    }
    break;
  }
  return lines.slice(0, cut).join('\n').replace(/\s+$/, '');
});

function onPick(label: string): void {
  if (!props.actionable) return;
  emit('pick', label);
}
</script>

<template>
  <div class="question-canvas">
    <MarkdownView :source="questionText" />
    <div class="question-canvas__options">
      <button
        v-for="opt in options"
        :key="opt.label"
        type="button"
        :disabled="!actionable"
        class="question-canvas__option"
        :class="actionable ? 'is-actionable' : 'is-stale'"
        :title="opt.description ?? opt.label"
        @click="onPick(opt.label)"
      >
        <span class="question-canvas__option-label">{{ opt.label }}</span>
        <span
          v-if="opt.description"
          class="question-canvas__option-desc"
        >{{ opt.description }}</span>
      </button>
    </div>
  </div>
</template>

<style scoped>
.question-canvas {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}
.question-canvas__options {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
}
.question-canvas__option {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 0.15rem;
  padding: 0.55rem 0.9rem;
  border-radius: 0.5rem;
  border: 1px solid;
  text-align: left;
  font-size: 0.9rem;
  cursor: pointer;
  transition: background-color 0.12s, border-color 0.12s, opacity 0.12s;
}
.question-canvas__option.is-actionable {
  border-color: hsl(var(--p) / 0.4);
  background: hsl(var(--p) / 0.08);
}
.question-canvas__option.is-actionable:hover {
  background: hsl(var(--p) / 0.18);
  border-color: hsl(var(--p) / 0.6);
}
.question-canvas__option.is-stale {
  border-color: hsl(var(--bc) / 0.18);
  background: hsl(var(--bc) / 0.06);
  cursor: default;
  opacity: 0.55;
}
.question-canvas__option-label {
  font-weight: 600;
}
.question-canvas__option-desc {
  font-size: 0.75rem;
  opacity: 0.75;
  font-weight: 400;
}
</style>
