<script setup lang="ts">
import { computed } from 'vue';
import { VGroup, VGroupRow, VBadge } from '@vance/components';
import { safeUrl } from '@vance/shared';
import ContactAvatar from './ContactAvatar.vue';
import { useT } from './i18n';
import type { ContactAddress } from './generated/contacts/ContactAddress';
import type { ContactCard } from './generated/contacts/ContactCard';
import type { ContactMemberView } from './generated/contacts/ContactMemberView';
import type { ContactScope } from './generated/contacts/ContactScope';
import type { ContactSummaryView } from './generated/contacts/ContactSummaryView';
import type { ContactValue } from './generated/contacts/ContactValue';

/**
 * Read view of one card: picture and name on top, then one group per kind
 * of contact data. Used by the address book and by the `contact` document
 * kind alike.
 */
const props = withDefaults(defineProps<{
  card: ContactCard;
  projectId: string | null;
  id?: string | null;
  scope?: ContactScope | null;
  hasPhoto?: boolean;
  photoVersion?: number | null;
  members?: ContactMemberView[];
  groups?: ContactSummaryView[];
}>(), { id: null, scope: null, hasPhoto: false, photoVersion: null, members: () => [], groups: () => [] });

const emit = defineEmits<{ (e: 'open', id: string): void }>();

const t = useT();

const displayName = computed(() => {
  const c = props.card;
  if (c.fullName) return c.fullName;
  const n = c.name;
  const parts = n ? [n.given, n.family].filter(Boolean) : [];
  return parts.length ? parts.join(' ') : c.org?.name ?? '';
});

const subtitle = computed(() => {
  const parts: string[] = [];
  if (props.card.title) parts.push(props.card.title);
  if (props.card.org?.name) {
    parts.push([props.card.org.name, ...(props.card.org.units ?? [])].join(', '));
  }
  return parts.join(' · ');
});

const KNOWN_TYPES = new Set(['work', 'home', 'cell', 'voice', 'fax', 'text']);

function typeLabel(v: ContactValue | ContactAddress): string {
  return (v.types ?? []).map((ty) => (KNOWN_TYPES.has(ty) ? t(`contacts.type.${ty}`) : ty)).join(', ');
}

function addressLines(a: ContactAddress): string {
  if (a.label && !a.street && !a.locality) return a.label;
  return [a.street, [a.postalCode, a.locality].filter(Boolean).join(' '), a.region, a.country]
    .filter((x) => x && String(x).trim())
    .join('\n');
}

function telHref(value: string): string {
  return `tel:${value.replace(/[^+\d]/g, '')}`;
}

const hasDetails = computed(() => {
  const c = props.card;
  return (c.emails?.length ?? 0) + (c.phones?.length ?? 0) + (c.addresses?.length ?? 0)
    + (c.urls?.length ?? 0) > 0 || !!c.birthday;
});
</script>

<template>
  <div class="flex flex-col gap-5">
    <div class="flex items-center gap-4">
      <ContactAvatar
        :name="displayName"
        :project-id="projectId"
        :id="id"
        :scope="scope"
        :has-photo="hasPhoto"
        :photo-version="photoVersion"
        size="lg"
      />
      <div class="min-w-0">
        <h2 class="text-xl font-semibold truncate">{{ displayName }}</h2>
        <p v-if="subtitle" class="text-sm text-(--v-label-secondary)">{{ subtitle }}</p>
        <div class="flex flex-wrap gap-1 mt-1">
          <VBadge v-if="card.kind !== 'INDIVIDUAL'" size="sm">{{ t(`contacts.kind.${card.kind}`) }}</VBadge>
          <VBadge v-for="cat in card.categories" :key="cat" size="sm" variant="neutral">{{ cat }}</VBadge>
        </div>
      </div>
    </div>

    <VGroup v-if="hasDetails">
      <VGroupRow v-for="(e, i) in card.emails" :key="`e${i}`" icon="mail" :description="typeLabel(e)">
        <template #label><a :href="`mailto:${e.value}`" class="text-(--v-accent-text) hover:underline">{{ e.value }}</a></template>
      </VGroupRow>
      <VGroupRow v-for="(p, i) in card.phones" :key="`p${i}`" icon="call" :description="typeLabel(p)">
        <template #label><a :href="telHref(p.value)" class="text-(--v-accent-text) hover:underline">{{ p.value }}</a></template>
      </VGroupRow>
      <VGroupRow v-for="(a, i) in card.addresses" :key="`a${i}`" icon="location" :description="typeLabel(a)">
        <template #label><span class="whitespace-pre-line">{{ addressLines(a) }}</span></template>
      </VGroupRow>
      <VGroupRow v-for="(u, i) in card.urls" :key="`u${i}`" icon="link">
        <template #label>
          <a :href="safeUrl(u.value) ?? undefined" target="_blank" rel="noopener noreferrer" class="text-(--v-accent-text) hover:underline">{{ u.value }}</a>
        </template>
      </VGroupRow>
      <VGroupRow v-if="card.birthday" icon="calendar" :label="card.birthday" :description="t('contacts.field.birthday')" />
    </VGroup>

    <VGroup v-if="card.kind === 'GROUP'" :label="t('contacts.detail.members')">
      <VGroupRow v-if="members.length === 0" :label="t('contacts.detail.noMembers')" />
      <template v-for="m in members" :key="m.member">
        <VGroupRow
          v-if="m.contact"
          :label="m.contact.displayName"
          :description="m.contact.email ?? undefined"
          navigates
          @click="emit('open', m.contact.id)"
        />
        <VGroupRow v-else :label="m.member" :description="m.member.includes(':') ? undefined : t('contacts.detail.memberMissing')" />
      </template>
    </VGroup>

    <VGroup v-if="groups.length" :label="t('contacts.detail.groups')">
      <VGroupRow
        v-for="g in groups"
        :key="g.id"
        icon="team"
        :label="g.displayName"
        navigates
        @click="emit('open', g.id)"
      />
    </VGroup>

    <VGroup v-if="card.note" :label="t('contacts.field.note')">
      <div class="px-4 py-3 text-sm whitespace-pre-line">{{ card.note }}</div>
    </VGroup>
  </div>
</template>
