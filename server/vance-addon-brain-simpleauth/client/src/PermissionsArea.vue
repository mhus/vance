<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { VAlert, VButton, VInput, VSelect } from '@vance/components';
import { listGrants, removeGrant, setGrant } from './api';
import type { GrantDto, GrantRole, GrantScopeType, GrantSubjectType } from './types';
import { useT } from './i18n';

const t = useT();

// Ids in code, labels looked up per render — a module-level literal list could
// not follow a language switch.
const SCOPE_TYPES: GrantScopeType[] = ['TENANT', 'PROJECT'];
const SUBJECT_TYPES: GrantSubjectType[] = ['USER', 'TEAM'];
const ROLES: GrantRole[] = ['READER', 'WRITER', 'ADMIN'];

const scopeTypeOptions = computed(() =>
  SCOPE_TYPES.map((value) => ({ value, label: t(`simpleauth.scopeType.${value}`) })),
);
const subjectTypeOptions = computed(() =>
  SUBJECT_TYPES.map((value) => ({ value, label: t(`simpleauth.subject.${value}`) })),
);
const roleOptions = computed(() =>
  ROLES.map((value) => ({ value, label: t(`simpleauth.roles.${value}`) })),
);

// Scope being viewed/edited.
const scopeType = ref<GrantScopeType>('PROJECT');
const scopeId = ref<string>('');

// New-grant form.
const subjectType = ref<GrantSubjectType>('USER');
const subjectId = ref<string>('');
const role = ref<GrantRole>('WRITER');

const grants = ref<GrantDto[]>([]);
const loading = ref(false);
const error = ref<string>('');
const notice = ref<string>('');

function scopeReady(): boolean {
  return scopeType.value === 'TENANT' || scopeId.value.trim().length > 0;
}

async function load(): Promise<void> {
  error.value = '';
  notice.value = '';
  if (!scopeReady()) {
    grants.value = [];
    return;
  }
  loading.value = true;
  try {
    grants.value = await listGrants(scopeType.value, scopeId.value.trim());
  } catch (e) {
    error.value = messageOf(e);
    grants.value = [];
  } finally {
    loading.value = false;
  }
}

async function add(): Promise<void> {
  error.value = '';
  notice.value = '';
  if (!scopeReady() || subjectId.value.trim().length === 0) {
    error.value = t('simpleauth.required');
    return;
  }
  loading.value = true;
  try {
    await setGrant({
      scopeType: scopeType.value,
      scopeId: scopeId.value.trim(),
      subjectType: subjectType.value,
      subjectId: subjectId.value.trim(),
      role: role.value,
    });
    notice.value = t('simpleauth.granted', {
      role: t(`simpleauth.roles.${role.value}`),
      subject: t(`simpleauth.subject.${subjectType.value}`),
      name: subjectId.value.trim(),
    });
    subjectId.value = '';
    await load();
  } catch (e) {
    error.value = messageOf(e);
  } finally {
    loading.value = false;
  }
}

async function revoke(g: GrantDto): Promise<void> {
  error.value = '';
  notice.value = '';
  loading.value = true;
  try {
    await removeGrant(g.scopeType, g.scopeId, g.subjectType, g.subjectId);
    await load();
  } catch (e) {
    error.value = messageOf(e);
  } finally {
    loading.value = false;
  }
}

function messageOf(e: unknown): string {
  if (e instanceof Error) return e.message;
  return String(e);
}

onMounted(() => {
  const project = new URLSearchParams(window.location.search).get('project');
  if (project) {
    scopeType.value = 'PROJECT';
    scopeId.value = project;
  }
  void load();
});
</script>

<template>
  <div class="mx-auto max-w-3xl p-6 flex flex-col gap-6">
    <div>
      <h1 class="text-xl font-semibold">{{ t('simpleauth.title') }}</h1>
      <p class="text-sm opacity-70">{{ t('simpleauth.subtitle') }}</p>
    </div>

    <!-- Scope selector -->
    <div class="flex flex-wrap items-end gap-3">
      <div class="w-40">
        <VSelect v-model="scopeType" :options="scopeTypeOptions" :label="t('simpleauth.scope')" @update:modelValue="load" />
      </div>
      <div v-if="scopeType === 'PROJECT'" class="flex-1 min-w-48">
        <VInput v-model="scopeId" :label="t('simpleauth.project')" @update:modelValue="notice = ''" />
      </div>
      <VButton variant="secondary" :loading="loading" @click="load">{{ t('simpleauth.load') }}</VButton>
    </div>

    <VAlert v-if="error" variant="error">{{ error }}</VAlert>
    <VAlert v-else-if="notice" variant="success">{{ notice }}</VAlert>

    <!-- Existing grants -->
    <div class="flex flex-col gap-2">
      <div class="text-sm font-medium opacity-70">{{ t('simpleauth.currentGrants') }}</div>
      <p v-if="grants.length === 0" class="text-sm opacity-60">{{ t('simpleauth.noGrants') }}</p>
      <ul v-else class="flex flex-col gap-2">
        <li
          v-for="g in grants"
          :key="g.subjectType + ':' + g.subjectId"
          class="flex items-center justify-between gap-3 rounded border border-base-300 px-3 py-2"
        >
          <span class="text-sm">
            <span class="font-mono">{{ g.subjectType.toLowerCase() }}:{{ g.subjectId }}</span>
            <span class="mx-2 opacity-50">→</span>
            <span class="font-semibold">{{ t(`simpleauth.roles.${g.role}`) }}</span>
          </span>
          <VButton variant="danger" size="sm" :disabled="loading" @click="revoke(g)">{{ t('simpleauth.revoke') }}</VButton>
        </li>
      </ul>
    </div>

    <!-- Add grant -->
    <div class="flex flex-col gap-3 rounded border border-base-300 p-4">
      <div class="text-sm font-medium opacity-70">{{ t('simpleauth.grantHeading') }}</div>
      <div class="flex flex-wrap items-end gap-3">
        <div class="w-36">
          <VSelect v-model="subjectType" :options="subjectTypeOptions" :label="t('simpleauth.subjectType')" />
        </div>
        <div class="flex-1 min-w-48">
          <VInput v-model="subjectId" :label="t('simpleauth.subjectId')" />
        </div>
        <div class="w-36">
          <VSelect v-model="role" :options="roleOptions" :label="t('simpleauth.role')" />
        </div>
        <VButton variant="primary" :loading="loading" @click="add">{{ t('simpleauth.grant') }}</VButton>
      </div>
    </div>
  </div>
</template>
