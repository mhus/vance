<script setup lang="ts">
import { computed, reactive, ref } from 'vue';
import {
  VAlert, VButton, VGroup, VIconButton, VInput, VSegment, VSelect, VTagEditor, VTextarea,
} from '@vance/components';
import { useT } from './i18n';
import type { ContactAddress } from './generated/contacts/ContactAddress';
import type { ContactCard } from './generated/contacts/ContactCard';
import { ContactKind } from './generated/contacts/ContactKind';
import type { ContactScope } from './generated/contacts/ContactScope';
import type { ContactScopeView } from './generated/contacts/ContactScopeView';
import type { ContactValue } from './generated/contacts/ContactValue';

/**
 * Form for one card. Edits the fields people maintain by hand; everything
 * else the card carries (nicknames, messengers, relations, members, foreign
 * vCard properties, extra types) is kept as it was — the form never drops
 * what it does not show.
 */
const props = defineProps<{
  card: ContactCard | null;
  /** Scopes the reader may write to — the "save in" choice when creating. */
  scopes: ContactScopeView[];
  /** Set when editing: where the card lives (no scope choice then). */
  scope: ContactScope | null;
  defaultScope: ContactScope;
  busy?: boolean;
}>();

const emit = defineEmits<{
  (e: 'save', value: { card: ContactCard; scope: ContactScope }): void;
  (e: 'cancel'): void;
}>();

const t = useT();

interface Row { value: string; type: string; original: ContactValue | null }
interface AddressRow {
  type: string;
  street: string;
  postalCode: string;
  locality: string;
  region: string;
  country: string;
  original: ContactAddress | null;
}

const original = props.card;

function rows(values: ContactValue[] | undefined): Row[] {
  return (values ?? []).map((v) => ({ value: v.value, type: v.types?.[0] ?? '', original: v }));
}

const draft = reactive({
  kind: (original?.kind ?? ContactKind.INDIVIDUAL) as ContactKind,
  fullName: original?.fullName ?? '',
  given: original?.name?.given ?? '',
  family: original?.name?.family ?? '',
  orgName: original?.org?.name ?? '',
  orgUnit: (original?.org?.units ?? []).join(', '),
  title: original?.title ?? '',
  emails: rows(original?.emails),
  phones: rows(original?.phones),
  urls: rows(original?.urls),
  addresses: (original?.addresses ?? []).map<AddressRow>((a) => ({
    type: a.types?.[0] ?? '',
    street: a.street ?? '',
    postalCode: a.postalCode ?? '',
    locality: a.locality ?? '',
    region: a.region ?? '',
    country: a.country ?? '',
    original: a,
  })),
  birthday: original?.birthday ?? '',
  categories: [...(original?.categories ?? [])],
  note: original?.note ?? '',
});

const targetScope = ref<ContactScope>(props.scope ?? props.defaultScope);
const error = ref('');

const kindOptions = computed(() => [
  { value: ContactKind.INDIVIDUAL, label: t('contacts.kind.INDIVIDUAL') },
  { value: ContactKind.ORG, label: t('contacts.kind.ORG') },
  { value: ContactKind.GROUP, label: t('contacts.kind.GROUP') },
]);

const scopeOptions = computed(() =>
  props.scopes.filter((s) => s.writable).map((s) => ({ value: s.scope, label: t(`contacts.scope.${s.scope}`) })),
);

function typeOptions(kinds: string[]) {
  return [{ value: '', label: t('contacts.type.none') }, ...kinds.map((k) => ({ value: k, label: t(`contacts.type.${k}`) }))];
}
const emailTypes = computed(() => typeOptions(['work', 'home']));
const phoneTypes = computed(() => typeOptions(['cell', 'work', 'home', 'fax']));
const addressTypes = computed(() => typeOptions(['work', 'home']));

const isGroup = computed(() => draft.kind === ContactKind.GROUP);
const isPerson = computed(() => draft.kind === ContactKind.INDIVIDUAL);

/** The edited type replaces the first; further types of the original stay. */
function types(row: { type: string; original: { types?: string[] } | null }): string[] {
  const rest = (row.original?.types ?? []).slice(1);
  return row.type ? [row.type, ...rest.filter((x) => x !== row.type)] : rest;
}

function values(list: Row[]): ContactValue[] {
  return list
    .filter((r) => r.value.trim())
    .map((r) => ({ value: r.value.trim(), types: types(r), pref: r.original?.pref }));
}

function build(): ContactCard {
  const base: ContactCard = original ?? {
    kind: ContactKind.INDIVIDUAL, fullName: '', nicknames: [], emails: [], phones: [], addresses: [],
    urls: [], impps: [], languages: [], categories: [], related: [], members: [], extras: {},
  };
  const nameParts = original?.name;
  const units = draft.orgUnit.split(',').map((s) => s.trim()).filter(Boolean);
  return {
    ...base,
    kind: draft.kind,
    fullName: draft.fullName.trim(),
    name: isPerson.value && (draft.given.trim() || draft.family.trim() || nameParts)
      ? {
          additional: nameParts?.additional ?? [],
          prefixes: nameParts?.prefixes ?? [],
          suffixes: nameParts?.suffixes ?? [],
          given: draft.given.trim() || undefined,
          family: draft.family.trim() || undefined,
        }
      : undefined,
    org: draft.orgName.trim() || units.length
      ? { name: draft.orgName.trim() || undefined, units, ref: original?.org?.ref }
      : undefined,
    title: draft.title.trim() || undefined,
    emails: values(draft.emails),
    phones: values(draft.phones),
    urls: values(draft.urls),
    addresses: draft.addresses
      .filter((a) => a.street.trim() || a.locality.trim() || a.postalCode.trim() || a.country.trim() || a.region.trim())
      .map((a) => ({
        ...(a.original ?? {}),
        types: types(a),
        pref: a.original?.pref,
        street: a.street.trim() || undefined,
        postalCode: a.postalCode.trim() || undefined,
        locality: a.locality.trim() || undefined,
        region: a.region.trim() || undefined,
        country: a.country.trim() || undefined,
      })),
    birthday: draft.birthday.trim() || undefined,
    categories: draft.categories,
    note: draft.note.trim() || undefined,
    members: draft.kind === ContactKind.GROUP ? base.members ?? [] : [],
  };
}

function hasName(card: ContactCard): boolean {
  return !!(card.fullName || card.name?.given || card.name?.family || card.org?.name);
}

function save(): void {
  const card = build();
  if (!hasName(card)) {
    error.value = t('contacts.field.nameRequired');
    return;
  }
  error.value = '';
  emit('save', { card, scope: targetScope.value });
}

function addRow(list: Row[]): void {
  list.push({ value: '', type: '', original: null });
}

function addAddress(): void {
  draft.addresses.push({ type: '', street: '', postalCode: '', locality: '', region: '', country: '', original: null });
}
</script>

<template>
  <form id="contact-editor" class="flex flex-col gap-5" @submit.prevent="save">
    <VAlert v-if="error" variant="error">{{ error }}</VAlert>

    <div class="flex flex-wrap gap-4 items-end">
      <VSegment v-model="draft.kind" :options="kindOptions" :aria-label="t('contacts.field.kind')" />
      <div v-if="!scope && scopeOptions.length > 1" class="w-48">
        <VSelect v-model="targetScope" :options="scopeOptions" :label="t('contacts.field.scope')" />
      </div>
    </div>

    <div class="grid grid-cols-1 md:grid-cols-2 gap-3">
      <div v-if="isPerson"><VInput v-model="draft.given" :label="t('contacts.field.given')" autocomplete="off" /></div>
      <div v-if="isPerson"><VInput v-model="draft.family" :label="t('contacts.field.family')" autocomplete="off" /></div>
      <div class="md:col-span-2"><VInput v-model="draft.fullName" :label="t('contacts.field.fullName')" autocomplete="off" /></div>
      <div v-if="!isGroup"><VInput v-model="draft.orgName" :label="t('contacts.field.org')" autocomplete="off" /></div>
      <div v-if="isPerson"><VInput v-model="draft.orgUnit" :label="t('contacts.field.units')" autocomplete="off" /></div>
      <div v-if="isPerson"><VInput v-model="draft.title" :label="t('contacts.field.title')" autocomplete="off" /></div>
      <div v-if="isPerson">
        <VInput v-model="draft.birthday" :label="t('contacts.field.birthday')" :help="t('contacts.field.birthdayHelp')" />
      </div>
    </div>

    <template v-if="!isGroup">
      <VGroup :label="t('contacts.field.emails')">
        <div v-for="(row, i) in draft.emails" :key="`e${i}`" class="flex gap-2 items-center px-3 py-2">
          <div class="flex-1"><VInput v-model="row.value" type="email" size="sm" /></div>
          <div class="w-32"><VSelect v-model="row.type" :options="emailTypes" /></div>
          <VIconButton icon="delete" :label="t('contacts.field.remove')" size="sm" @click="draft.emails.splice(i, 1)" />
        </div>
        <div class="px-3 py-2">
          <VButton variant="plain" size="sm" icon="add" @click="addRow(draft.emails)">{{ t('contacts.field.add') }}</VButton>
        </div>
      </VGroup>

      <VGroup :label="t('contacts.field.phones')">
        <div v-for="(row, i) in draft.phones" :key="`p${i}`" class="flex gap-2 items-center px-3 py-2">
          <div class="flex-1"><VInput v-model="row.value" size="sm" placeholder="+49 30 1234567" /></div>
          <div class="w-32"><VSelect v-model="row.type" :options="phoneTypes" /></div>
          <VIconButton icon="delete" :label="t('contacts.field.remove')" size="sm" @click="draft.phones.splice(i, 1)" />
        </div>
        <div class="px-3 py-2">
          <VButton variant="plain" size="sm" icon="add" @click="addRow(draft.phones)">{{ t('contacts.field.add') }}</VButton>
        </div>
      </VGroup>

      <VGroup :label="t('contacts.field.addresses')">
        <div v-for="(a, i) in draft.addresses" :key="`a${i}`" class="grid grid-cols-6 gap-2 px-3 py-2 items-end">
          <div class="col-span-4"><VInput v-model="a.street" :label="t('contacts.field.street')" size="sm" /></div>
          <div class="col-span-2"><VSelect v-model="a.type" :options="addressTypes" :label="t('contacts.field.type')" /></div>
          <div class="col-span-2"><VInput v-model="a.postalCode" :label="t('contacts.field.postalCode')" size="sm" /></div>
          <div class="col-span-4"><VInput v-model="a.locality" :label="t('contacts.field.locality')" size="sm" /></div>
          <div class="col-span-3"><VInput v-model="a.region" :label="t('contacts.field.region')" size="sm" /></div>
          <div class="col-span-2"><VInput v-model="a.country" :label="t('contacts.field.country')" size="sm" /></div>
          <div class="col-span-1 flex justify-end">
            <VIconButton icon="delete" :label="t('contacts.field.remove')" size="sm" @click="draft.addresses.splice(i, 1)" />
          </div>
        </div>
        <div class="px-3 py-2">
          <VButton variant="plain" size="sm" icon="add" @click="addAddress">{{ t('contacts.field.add') }}</VButton>
        </div>
      </VGroup>

      <VGroup :label="t('contacts.field.urls')">
        <div v-for="(row, i) in draft.urls" :key="`u${i}`" class="flex gap-2 items-center px-3 py-2">
          <div class="flex-1"><VInput v-model="row.value" type="url" size="sm" /></div>
          <VIconButton icon="delete" :label="t('contacts.field.remove')" size="sm" @click="draft.urls.splice(i, 1)" />
        </div>
        <div class="px-3 py-2">
          <VButton variant="plain" size="sm" icon="add" @click="addRow(draft.urls)">{{ t('contacts.field.add') }}</VButton>
        </div>
      </VGroup>
    </template>

    <VTagEditor v-model="draft.categories" :label="t('contacts.field.categories')" />
    <VTextarea v-model="draft.note" :label="t('contacts.field.note')" :rows="4" :mono="false" auto-grow />

    <div class="flex justify-end gap-2">
      <VButton variant="secondary" @click="emit('cancel')">{{ t('contacts.common.cancel') }}</VButton>
      <VButton variant="primary" type="submit" :loading="busy">{{ t('contacts.common.save') }}</VButton>
    </div>
  </form>
</template>
