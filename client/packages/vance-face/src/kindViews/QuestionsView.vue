<script setup lang="ts">
/**
 * Renderer for `kind: questions` — several questions to the user at once.
 *
 * In a chat (`inline` mode, the conversation's open end) it is a wizard: one
 * page per question with a progress bar, the question's options or a
 * confirm button for the asker's proposal, the standard exits ("good as it
 * is", "later", "don't know") and a free-text field; an overview at the end
 * lists every answer, each one can be reopened, then the set is sent. The
 * answers leave as a bubbling DOM event ({@link QUESTION_ANSWERS_EVENT}); the
 * chat view turns it into a process-steer with `questionAnswers` plus a
 * readable short form as the message text. Once a later user message exists
 * the set is answered and shows read-only.
 *
 * In the editor and embedded modes it is a read-only list.
 *
 * Spec: specification/public/doc-kind-questions.md.
 */
import { computed, inject, ref } from 'vue';
import { VAlert, VButton, VTextarea, useT } from '@vance/components';
import {
  QuestionKind,
  QuestionOutcome,
  QuestionWeight,
  type DocumentDto,
  type QuestionAnswerDto,
  type QuestionSetDto,
} from '@vance/generated';
import type { FenceMeta } from '@/kindRenderers/parseFenceLang';
import { parseQuestions } from './questionsCodec';
import {
  QUESTION_ANSWERS_EVENT,
  QUESTIONS_ACTIONABLE_KEY,
  answerLabel,
  readableAnswers,
  setIdFor,
  toSetAnswer,
  type OutcomeLabels,
  type QuestionAnswersDetail,
} from './questionsAnswers';

defineOptions({ name: 'QuestionsView' });

const props = withDefaults(defineProps<{
  mode?: 'editor' | 'inline' | 'embedded';
  doc?: string;
  content?: string;
  meta?: FenceMeta;
  document?: DocumentDto;
}>(), {
  mode: 'editor',
  doc: '',
  meta: () => ({}),
});

const t = useT();

const source = computed(() => {
  if (props.mode === 'inline') return props.content ?? '';
  if (props.mode === 'embedded') return props.document?.inlineText ?? '';
  return props.doc;
});

const parsed = computed<{ set?: QuestionSetDto; error?: string }>(() => {
  try {
    return { set: parseQuestions(source.value, props.mode === 'embedded' ? props.document?.mimeType : undefined) };
  } catch (e) {
    return { error: e instanceof Error ? e.message : String(e) };
  }
});

const actionableRef = inject(QUESTIONS_ACTIONABLE_KEY, ref(false));
const sent = ref(false);
const interactive = computed(() => props.mode === 'inline' && actionableRef.value && !sent.value);

const page = ref(0);
const answers = ref<Record<string, QuestionAnswerDto>>({});
const draft = ref('');
const root = ref<HTMLElement | null>(null);

const labels = computed<OutcomeLabels>(() => ({
  settled: t('kindViews.questions.settled'),
  later: t('kindViews.questions.later'),
  unknown: t('kindViews.questions.unknown'),
  confirmed: t('kindViews.questions.confirmed'),
  open: t('kindViews.questions.open'),
}));

const total = computed(() => parsed.value.set?.questions.length ?? 0);
const onOverview = computed(() => page.value >= total.value);
const current = computed(() => parsed.value.set?.questions[page.value]);
const progress = computed(() => (total.value === 0 ? 0 : Math.round((Math.min(page.value, total.value) / total.value) * 100)));

function goTo(index: number): void {
  page.value = Math.max(0, Math.min(index, total.value));
  const q = parsed.value.set?.questions[page.value];
  const a = q ? answers.value[q.key] : undefined;
  draft.value = a?.outcome === QuestionOutcome.ANSWERED && q?.kind === QuestionKind.TEXT ? (a.value ?? '') : '';
}

function answer(outcome: QuestionOutcome, value?: string): void {
  const q = current.value;
  if (!q) return;
  answers.value = { ...answers.value, [q.key]: { key: q.key, outcome, value } };
  goTo(page.value + 1);
}

function answerWithText(): void {
  const text = draft.value.trim();
  if (text) answer(QuestionOutcome.ANSWERED, text);
  else goTo(page.value + 1);
}

function send(): void {
  const set = parsed.value.set;
  if (!set || !root.value) return;
  const detail: QuestionAnswersDetail = {
    answers: toSetAnswer(setIdFor(set, source.value), set, answers.value),
    content: readableAnswers(set, answers.value, labels.value),
  };
  root.value.dispatchEvent(new CustomEvent(QUESTION_ANSWERS_EVENT, { bubbles: true, detail }));
  sent.value = true;
}
</script>

<template>
  <div ref="root" class="questions-view flex flex-col gap-3 p-3">
    <VAlert v-if="parsed.error" variant="error">
      {{ t('kindViews.questions.error') }} {{ parsed.error }}
    </VAlert>

    <template v-else-if="parsed.set">
      <p v-if="parsed.set.intro" class="text-sm">{{ parsed.set.intro }}</p>

      <!-- Wizard: one page per question -->
      <template v-if="interactive && !onOverview && current">
        <div class="flex items-center gap-3">
          <progress class="progress progress-primary flex-1" :value="progress" max="100" />
          <span class="text-xs opacity-70">{{ t('kindViews.questions.progress', { n: page + 1, total }) }}</span>
        </div>
        <div class="flex flex-col gap-2">
          <div class="font-medium">
            <span v-if="current.weight === QuestionWeight.BLOCKER" class="badge badge-error badge-sm mr-2">
              {{ t('kindViews.questions.blocker') }}
            </span>
            {{ current.text }}
          </div>
          <blockquote v-if="current.context" class="text-xs opacity-70 border-l-2 border-base-300 pl-2">
            {{ current.context }}
          </blockquote>
          <div v-if="current.kind === QuestionKind.CONFIRM && current.proposal" class="flex flex-col gap-2">
            <div class="text-sm"><span class="opacity-70">{{ t('kindViews.questions.proposal') }}</span> {{ current.proposal }}</div>
            <VButton variant="primary" size="sm" @click="answer(QuestionOutcome.ANSWERED, current.proposal)">
              {{ t('kindViews.questions.confirm') }}
            </VButton>
          </div>
          <div v-if="current.options.length > 0" class="flex flex-wrap gap-2">
            <VButton
              v-for="o in current.options"
              :key="o.label"
              variant="secondary"
              size="sm"
              :title="o.description"
              @click="answer(QuestionOutcome.ANSWERED, o.label)"
            >
              {{ o.label }}
            </VButton>
          </div>
          <VTextarea v-model="draft" :rows="2" :mono="false" :placeholder="t('kindViews.questions.freeText')" />
          <div class="flex flex-wrap gap-2">
            <VButton variant="primary" size="sm" @click="answerWithText">{{ t('kindViews.questions.next') }}</VButton>
            <VButton variant="ghost" size="sm" @click="answer(QuestionOutcome.SETTLED)">{{ labels.settled }}</VButton>
            <VButton variant="ghost" size="sm" @click="answer(QuestionOutcome.LATER)">{{ labels.later }}</VButton>
            <VButton variant="ghost" size="sm" @click="answer(QuestionOutcome.UNKNOWN)">{{ labels.unknown }}</VButton>
            <VButton v-if="page > 0" variant="ghost" size="sm" @click="goTo(page - 1)">{{ t('kindViews.questions.back') }}</VButton>
          </div>
        </div>
      </template>

      <!-- Overview: before sending, and the read-only form afterwards -->
      <template v-else>
        <div v-if="interactive" class="flex items-center gap-3">
          <progress class="progress progress-primary flex-1" value="100" max="100" />
          <span class="text-xs opacity-70">{{ t('kindViews.questions.overview') }}</span>
        </div>
        <ol class="list-decimal pl-5 flex flex-col gap-1 text-sm">
          <li v-for="(q, i) in parsed.set.questions" :key="q.key">
            <button
              v-if="interactive"
              type="button"
              class="text-left align-top hover:underline"
              @click="goTo(i)"
            >
              {{ q.text }} → <strong>{{ answerLabel(parsed.set, answers[q.key], labels) }}</strong>
            </button>
            <span v-else>
              {{ q.text }}
              <strong v-if="sent"> → {{ answerLabel(parsed.set, answers[q.key], labels) }}</strong>
            </span>
          </li>
        </ol>
        <div v-if="interactive" class="flex gap-2">
          <VButton variant="primary" size="sm" @click="send">{{ t('kindViews.questions.send') }}</VButton>
        </div>
        <div v-else-if="mode === 'inline' && (sent || !actionableRef)" class="text-xs opacity-60">
          {{ t('kindViews.questions.answered') }}
        </div>
      </template>
    </template>
  </div>
</template>
