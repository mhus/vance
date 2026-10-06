<script setup lang="ts">
/**
 * Form view for `kind: vance-feed-source` documents — one feed endpoint of
 * the Centauri feed reader (`_vance/config/feeds/<name>.yaml`, spec
 * `specification/public/centauri-service.md`).
 *
 * The model is the whole YAML map (see `feedSourceCodec.ts`): the form owns
 * exactly the fields it renders — protocol, endpoint, credential, enabled —
 * and every other key (`$meta`, `readerIdentity`, protocol fields this
 * build does not know) passes through untouched. Mutations bubble up as a
 * fresh map through `update:doc`; the host's settings shell re-serialises
 * and saves through the ordinary document pipeline (writing
 * `_vance/config/` requires project admin).
 *
 * The form never misrepresents a protocol: a document using an id this
 * build does not list keeps it verbatim in a raw input instead of snapping
 * to one of the known ones. Same degrade rule as the research form.
 *
 * The kind marker is guaranteed on every save (`$meta.kind`), so a feed
 * written before this kind existed routes here even though its body
 * started without the marker.
 */
import { computed, reactive, watch } from 'vue';
import { VAlert, VCheckbox, VInput, VSelect } from '@vance/components';
import { useT } from './i18n';
import {
  FEED_PROTOCOLS,
  applyForm,
  formFromDoc,
  preservedKeyCount,
  type FeedSourceDoc,
  type FeedSourceForm,
} from './feedSourceCodec';

defineOptions({ name: 'FeedSourceFormView' });

const props = defineProps<{
  /** Parsed model — the whole YAML map (identity: parse/serialize in the codec). */
  doc: FeedSourceDoc;
  /** Supplied by the shell for location-aware views. */
  projectId?: string;
  docPath?: string;
  /** Shell contract: a read-only binding greys its controls out. */
  readOnly?: boolean;
}>();

const emit = defineEmits<{
  (event: 'update:doc', doc: FeedSourceDoc): void;
}>();

const t = useT();

/**
 * The two secret notations, interpolated into the help text so their braces
 * stay literal — a message containing them would be read as interpolation.
 */
const notation = { ref: '{{secret:vault:…}}', literal: '{noop}…' };

// ── Form state, re-derived whenever the shell hands us a new model ──────

const form = reactive<FeedSourceForm>(formFromDoc(props.doc));

/** Guards the re-derivation against echoing the doc back as an edit. */
let suspend = false;

watch(
  () => props.doc,
  (doc) => {
    suspend = true;
    Object.assign(form, formFromDoc(doc));
    // One tick is enough: the reactive writes above are batched.
    setTimeout(() => {
      suspend = false;
    }, 0);
  },
);

// Any form change rebuilds the model: clone the incoming map, apply the
// owned keys, leave everything else as it is.
watch(
  form,
  () => {
    if (suspend) return;
    emit('update:doc', applyForm({ ...props.doc }, form));
  },
  { deep: true },
);

const disabled = computed(() => props.readOnly === true);

/**
 * A feed source outside `_vance/config/feeds/` is the same kind but inert —
 * the feed factory reads exactly that folder. The form still edits it
 * (it's a draft), the banner says what's missing to make it take part.
 */
const isDraft = computed(
  () => !!props.docPath && !props.docPath.startsWith('_vance/config/feeds/'),
);

/** Unknown keys the form keeps but does not render. */
const preserved = computed(() => preservedKeyCount(props.doc));

// ── Protocol select with raw degrade ────────────────────────────────────

const CUSTOM_PROTOCOL = '__custom__';

const protocolKnown = computed(() =>
  (FEED_PROTOCOLS as readonly string[]).includes(form.protocol.trim()),
);

const protocolChoice = computed<string>({
  get: () => (form.protocol.trim() === '' ? 'usgs' : protocolKnown.value ? form.protocol.trim() : CUSTOM_PROTOCOL),
  set: (v) => {
    if (v !== CUSTOM_PROTOCOL) form.protocol = v;
  },
});

const protocolOptions = computed(() => [
  ...FEED_PROTOCOLS.map((id) => ({ value: id, label: t(`feeds.protocols.${id}`) })),
  { value: CUSTOM_PROTOCOL, label: t('feeds.sourceForm.protocolCustom') },
]);
</script>

<template>
  <div class="space-y-6 p-4">
    <VAlert v-if="isDraft" variant="warning">
      {{ t('feeds.sourceForm.draftWarning') }}
    </VAlert>

    <!-- ── What it is ─────────────────────────────────────────── -->
    <section class="space-y-3">
      <h3 class="text-sm font-semibold text-base-content/70 uppercase tracking-wide">
        {{ t('feeds.sourceForm.sectionSource') }}
      </h3>

      <div class="grid grid-cols-1 sm:grid-cols-2 gap-3">
        <div>
          <VSelect
            :model-value="protocolChoice"
            :options="protocolOptions"
            :label="t('feeds.sourceForm.protocol')"
            :help="t('feeds.sourceForm.protocolHelp')"
            :disabled="disabled"
            @update:model-value="(v: string | null) => { if (v) protocolChoice = v; }"
          />
        </div>
        <div>
          <VInput
            v-if="protocolChoice === CUSTOM_PROTOCOL"
            v-model="form.protocol"
            :label="t('feeds.sourceForm.protocolCustom')"
            :help="t('feeds.sourceForm.protocolCustomHelp')"
            required
            :disabled="disabled"
          />
        </div>
      </div>

      <VInput
        v-model="form.baseUrl"
        :label="t('feeds.sourceForm.baseUrl')"
        :help="t('feeds.sourceForm.baseUrlHelp')"
        required
        :disabled="disabled"
      />

      <VInput
        v-model="form.apiKey"
        type="password"
        :label="t('feeds.sourceForm.apiKey')"
        :help="t('feeds.sourceForm.apiKeyHelp', notation)"
        :disabled="disabled"
      />

      <VCheckbox
        v-model="form.enabled"
        :label="t('feeds.sourceForm.enabled')"
        :help="t('feeds.sourceForm.enabledHelp')"
        :disabled="disabled"
      />
    </section>

    <p v-if="preserved > 0" class="text-xs text-base-content/60">
      {{ t('feeds.sourceForm.preserved', { count: preserved }) }}
    </p>
  </div>
</template>
