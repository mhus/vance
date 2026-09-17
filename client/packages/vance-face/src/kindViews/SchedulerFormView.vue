<script setup lang="ts">
/**
 * Form view for `kind: vance-scheduler` documents — a Ursa scheduler
 * definition (spec `specification/public/scheduler.md`).
 *
 * The model is the whole YAML map (see `schedulerFormCodec.ts`): the form
 * owns exactly the fields it renders — trigger, target, identity — and
 * every other key (`$meta`, `params`, `tags`, `lockMode`, …) passes through
 * untouched. Mutations bubble up as a fresh map through `update:doc`; the
 * shell re-serialises and saves through the ordinary document pipeline
 * (writing `_vance/scheduler/` requires project admin).
 *
 * The schedule section degrades, never misrepresents: a cron expression
 * the form can't express (Quartz macros, `L`, `#`, second-granularity)
 * switches to the `other` mode with the raw expression in a free-form
 * input — editable exactly as written.
 *
 * Spec: `specification/public/scheduler.md` §2 / kind `vance-scheduler`.
 */
import { computed, nextTick, reactive, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { VAlert, VCheckbox, VInput, VSelect, VTextarea } from '@/components';
import {
  WEEKDAYS,
  applySchedule,
  applyTarget,
  cronFromSchedule,
  scheduleFromDoc,
  targetFromDoc,
  type SchedulerDoc,
  type SchedulerMode,
  type SchedulerSchedule,
  type SchedulerTarget,
  type TargetKind,
} from './schedulerFormCodec';

defineOptions({ name: 'SchedulerFormView' });

const props = defineProps<{
  /** Parsed model — the whole YAML map (identity: parse/serialize in the codec). */
  doc: SchedulerDoc;
  /** Supplied by the shell for location-aware views. */
  projectId?: string;
  docPath?: string;
  /** Shell contract: a read-only binding greys its controls out. */
  readOnly?: boolean;
}>();

const emit = defineEmits<{
  (event: 'update:doc', doc: SchedulerDoc): void;
}>();

const { t } = useI18n();

// ── Form state, re-derived whenever the shell hands us a new model ──────

const schedule = reactive<SchedulerSchedule>(scheduleFromDoc(props.doc));
const target = reactive<SchedulerTarget>(targetFromDoc(props.doc));
const form = reactive({
  description: stringOf(props.doc.description),
  enabled: props.doc.enabled !== false,
  timezone: stringOf(props.doc.timezone),
  initialMessage: stringOf(props.doc.initialMessage),
  runAs: stringOf(props.doc.runAs),
  overlap: overlapOf(props.doc),
});

/** Guards the re-derivation against echoing the doc back as an edit. */
let suspend = false;

watch(
  () => props.doc,
  (doc) => {
    suspend = true;
    Object.assign(schedule, scheduleFromDoc(doc));
    Object.assign(target, targetFromDoc(doc));
    form.description = stringOf(doc.description);
    form.enabled = doc.enabled !== false;
    form.timezone = stringOf(doc.timezone);
    form.initialMessage = stringOf(doc.initialMessage);
    form.runAs = stringOf(doc.runAs);
    form.overlap = overlapOf(doc);
    void nextTick(() => {
      suspend = false;
    });
  },
);

// Any form change rebuilds the model: clone the incoming map, apply the
// owned keys, leave everything else as it is.
watch(
  [schedule, target, form],
  () => {
    if (suspend) return;
    let out: SchedulerDoc = { ...props.doc };
    putOrDrop(out, 'description', form.description.trim());
    putOrDrop(out, 'timezone', form.timezone.trim());
    putOrDrop(out, 'initialMessage', form.initialMessage.trim());
    putOrDrop(out, 'runAs', form.runAs.trim());
    putOrDrop(out, 'overlap', form.overlap === 'skip' ? '' : form.overlap);
    if (form.enabled) delete out.enabled;
    else out.enabled = false;
    out = applySchedule(out, schedule);
    out = applyTarget(out, target);
    emit('update:doc', out);
  },
  { deep: true },
);

const disabled = computed(() => props.readOnly === true);

/**
 * A scheduler document outside `_vance/scheduler/` is the same kind but
 * inert — kind and location are independent (same rule as workflows
 * §2.5). The form still edits it (it's a draft), the banner says what's
 * missing to make it fire.
 */
const isDraft = computed(
  () => !!props.docPath && !props.docPath.startsWith('_vance/scheduler/'),
);

// ── Schedule inputs ─────────────────────────────────────────────────────

const modeOptions = computed(() =>
  (['hourly', 'daily', 'weekly', 'monthly', 'once', 'other'] as SchedulerMode[]).map(
    (mode) => ({ value: mode, label: t(`scheduler.form.mode_${mode}`) }),
  ),
);


/** `type="time"` binds "HH:MM"; the schedule keeps numeric hour/minute. */
const timeValue = computed<string>({
  get: () => `${pad2(schedule.hour)}:${pad2(schedule.minute)}`,
  set: (v) => {
    const m = /^(\d{1,2}):(\d{2})$/.exec(v);
    if (m) {
      schedule.hour = Math.min(23, Number(m[1]));
      schedule.minute = Math.min(59, Number(m[2]));
    }
  },
});

/** `datetime-local` binds "YYYY-MM-DDTHH:MM"; `at:` stores ISO with seconds. */
const atValue = computed<string>({
  get: () => (schedule.at.length >= 16 ? schedule.at.substring(0, 16) : ''),
  set: (v) => {
    schedule.at = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(v) ? `${v}:00` : v;
  },
});

const everyHoursValue = computed<string>({
  get: () => String(schedule.everyHours),
  set: (v) => {
    const n = Number.parseInt(v, 10);
    if (Number.isFinite(n) && n >= 1) schedule.everyHours = n;
  },
});

const monthDayValue = computed<string>({
  get: () => String(schedule.monthDay),
  set: (v) => {
    const n = Number.parseInt(v, 10);
    if (Number.isFinite(n) && n >= 1) schedule.monthDay = n;
  },
});

function toggleWeekday(day: string, on: boolean): void {
  if (on) {
    if (!schedule.weekdays.includes(day)) {
      schedule.weekdays = WEEKDAYS.filter(d => schedule.weekdays.includes(d) || d === day);
    }
  } else {
    schedule.weekdays = schedule.weekdays.filter(d => d !== day);
  }
}

/** Live preview of the expression the current schedule writes back. */
const cronPreview = computed<string>(() =>
  schedule.mode === 'once' ? schedule.at : cronFromSchedule(schedule),
);

// ── Target inputs ───────────────────────────────────────────────────────

const targetOptions = computed(() =>
  (['recipe', 'workflow', 'script'] as TargetKind[]).map((kind) => ({
    value: kind,
    label: t(`scheduler.form.target_${kind}`),
  })),
);


const overlapOptions = computed(() =>
  ['skip', 'queue', 'cancelPrevious'].map((value) => ({
    value,
    label: t(`scheduler.form.overlap_${value}`),
  })),
);

// ── Helpers ────────────────────────────────────────────────────────────

function stringOf(v: unknown): string {
  return typeof v === 'string' ? v : '';
}

function overlapOf(doc: SchedulerDoc): string {
  const raw = doc.overlap;
  return typeof raw === 'string' ? raw : 'skip';
}

function putOrDrop(out: SchedulerDoc, key: string, value: string): void {
  if (value === '') delete out[key];
  else out[key] = value;
}

function pad2(n: number): string {
  return String(n).padStart(2, '0');
}
</script>

<template>
  <div class="max-w-3xl mx-auto p-4 space-y-5">
    <VAlert v-if="isDraft" variant="info">
      {{ t('scheduler.form.draftHint') }}
    </VAlert>

    <!-- ── What happens ──────────────────────────────────────────── -->
    <section class="space-y-3">
      <h3 class="text-sm font-semibold text-base-content/70 uppercase tracking-wide">
        {{ t('scheduler.form.sectionWhat') }}
      </h3>
      <VInput
        v-model="form.description"
        :label="t('scheduler.form.description')"
        :help="t('scheduler.form.descriptionHelp')"
        required
        :disabled="disabled"
      />
      <div class="grid grid-cols-1 sm:grid-cols-2 gap-3">
        <VSelect
          :model-value="target.kind"
          :options="targetOptions"
          :label="t('scheduler.form.targetLabel')"
          :disabled="disabled"
          @update:model-value="(k: TargetKind | null) => { if (k) target.kind = k; }"
        />
        <VInput
          v-if="target.kind === 'recipe' || target.kind === 'workflow'"
          v-model="target.name"
          :label="t('scheduler.form.targetName')"
          :help="target.kind === 'recipe' ? t('scheduler.form.recipeHelp') : undefined"
          required
          :disabled="disabled"
        />
      </div>
      <div v-if="target.kind === 'script'" class="grid grid-cols-1 sm:grid-cols-2 gap-3">
        <VInput
          v-model="target.scriptSource"
          :label="t('scheduler.form.scriptSource')"
          required
          :disabled="disabled"
        />
        <VInput
          v-model="target.scriptPath"
          :label="t('scheduler.form.scriptPath')"
          required
          :disabled="disabled"
        />
      </div>
      <VSelect
        v-if="target.kind === 'recipe'"
        :model-value="form.overlap"
        :options="overlapOptions"
        :label="t('scheduler.form.overlap')"
        :help="t('scheduler.form.overlapHelp')"
        :disabled="disabled"
        @update:model-value="(o: string | null) => { form.overlap = o ?? 'skip'; }"
      />
      <VTextarea
        v-model="form.initialMessage"
        :label="t('scheduler.form.initialMessage')"
        :help="t('scheduler.form.initialMessageHelp')"
        :rows="3"
        :mono="false"
        :disabled="disabled"
      />
    </section>

    <!-- ── When it fires ─────────────────────────────────────────── -->
    <section class="space-y-3">
      <h3 class="text-sm font-semibold text-base-content/70 uppercase tracking-wide">
        {{ t('scheduler.form.sectionWhen') }}
      </h3>
      <VSelect
        :model-value="schedule.mode"
        :options="modeOptions"
        :label="t('scheduler.form.mode')"
        :disabled="disabled"
        @update:model-value="(m: SchedulerMode | null) => { if (m) schedule.mode = m; }"
      />

      <!-- hourly: every N hours -->
      <div v-if="schedule.mode === 'hourly'" class="grid grid-cols-2 gap-3">
        <VInput
          v-model="everyHoursValue"
          type="number"
          :label="t('scheduler.form.everyHours')"
          :help="t('scheduler.form.everyHoursHelp')"
          :disabled="disabled"
        />
        <VInput
          v-model="timeValue"
          type="time"
          :label="t('scheduler.form.minute')"
          :help="t('scheduler.form.minuteHelp')"
          :disabled="disabled"
        />
      </div>

      <!-- daily: time of day -->
      <div v-else-if="schedule.mode === 'daily'" class="grid grid-cols-2 gap-3">
        <VInput
          v-model="timeValue"
          type="time"
          :label="t('scheduler.form.timeOfDay')"
          :disabled="disabled"
        />
      </div>

      <!-- weekly: weekdays + time -->
      <div v-else-if="schedule.mode === 'weekly'" class="space-y-3">
        <div class="flex flex-wrap gap-3">
          <VCheckbox
            v-for="day in WEEKDAYS"
            :key="day"
            :model-value="schedule.weekdays.includes(day)"
            :label="day"
            :disabled="disabled"
            @update:model-value="(on: boolean) => toggleWeekday(day, on)"
          />
        </div>
        <div class="grid grid-cols-2 gap-3">
          <VInput v-model="timeValue" type="time" :label="t('scheduler.form.timeOfDay')" :disabled="disabled" />
        </div>
      </div>

      <!-- monthly: day of month + time -->
      <div v-else-if="schedule.mode === 'monthly'" class="grid grid-cols-2 gap-3">
        <VInput
          v-model="monthDayValue"
          type="number"
          :label="t('scheduler.form.monthDay')"
          :help="t('scheduler.form.monthDayHelp')"
          :disabled="disabled"
        />
        <VInput v-model="timeValue" type="time" :label="t('scheduler.form.timeOfDay')" :disabled="disabled" />
      </div>

      <!-- once: concrete point in time -->
      <div v-else-if="schedule.mode === 'once'" class="grid grid-cols-2 gap-3">
        <VInput
          v-model="atValue"
          type="datetime-local"
          :label="t('scheduler.form.onceAt')"
          :help="t('scheduler.form.onceAtHelp')"
          required
          :disabled="disabled"
        />
      </div>

      <!-- other: raw cron expression -->
      <div v-else class="space-y-1">
        <VInput
          v-model="schedule.cron"
          :label="t('scheduler.form.cron')"
          :help="t('scheduler.form.cronHelp')"
          class="font-mono"
          required
          :disabled="disabled"
        />
      </div>

      <!-- timezone + enabled, shared by every mode -->
      <div class="grid grid-cols-1 sm:grid-cols-2 gap-3">
        <VInput
          v-model="form.timezone"
          :label="t('scheduler.form.timezone')"
          :help="t('scheduler.form.timezoneHelp')"
          :disabled="disabled"
        />
        <VCheckbox
          v-model="form.enabled"
          :label="t('scheduler.form.enabled')"
          :help="t('scheduler.form.enabledHelp')"
          :disabled="disabled"
        />
      </div>

      <p class="text-xs text-base-content/60 font-mono break-all">
        {{ schedule.mode === 'once' ? 'at:' : 'cron:' }} {{ cronPreview }}
        <template v-if="form.timezone"> · {{ form.timezone }}</template>
      </p>
    </section>

    <!-- ── Identity ─────────────────────────────────────────────── -->
    <section class="space-y-3">
      <h3 class="text-sm font-semibold text-base-content/70 uppercase tracking-wide">
        {{ t('scheduler.form.sectionIdentity') }}
      </h3>
      <VInput
        v-model="form.runAs"
        :label="t('scheduler.form.runAs')"
        :help="t('scheduler.form.runAsHelp')"
        :disabled="disabled"
      />
    </section>
  </div>
</template>
