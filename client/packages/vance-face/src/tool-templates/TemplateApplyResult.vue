<script setup lang="ts">
import { computed } from 'vue';
import type { ToolTemplateApplyResultDto } from '@vance/generated';
import { VAlert } from '@/components';

interface Props {
  result: ToolTemplateApplyResultDto;
}

// The dialog's actions — Close and, for an oauth-connect post-install step,
// Connect — live in the parent's VModal #actions slot (corporate-design.md
// §6.4); this component only renders the report.
const props = defineProps<Props>();

interface StatRow {
  key: string;
  label: string;
  values: string[];
}

const rows = computed<StatRow[]>(() => {
  const r = props.result.installer;
  const out: StatRow[] = [];
  const add = (key: string, label: string, values: string[]) => {
    if (values && values.length > 0) out.push({ key, label, values });
  };
  add('documentsAdded', 'Documents added', r.documentsAdded);
  add('documentsUpdated', 'Documents updated', r.documentsUpdated);
  add('settingsAdded', 'Settings added', r.settingsAdded);
  add('settingsUpdated', 'Settings updated', r.settingsUpdated);
  add('toolsAdded', 'Server tools added', r.toolsAdded);
  add('toolsUpdated', 'Server tools updated', r.toolsUpdated);
  add('inheritedKits', 'Inherited kits', r.inheritedKits);
  add('warnings', 'Warnings', r.warnings);
  return out;
});

const postInstall = computed(() => props.result.postInstall ?? null);
</script>

<template>
  <div class="flex flex-col gap-4">
    <VAlert variant="success">
      <span>{{ $t('templateResult.applied', { name: result.templateName }) }}</span>
    </VAlert>

    <section v-if="rows.length > 0" class="flex flex-col gap-2 text-sm">
      <div v-for="row in rows" :key="row.key" class="flex flex-col gap-1">
        <div class="text-xs uppercase tracking-wide opacity-60">{{ row.label }}</div>
        <ul class="ml-4 list-disc">
          <li v-for="v in row.values" :key="v" class="font-mono text-xs">{{ v }}</li>
        </ul>
      </div>
    </section>

    <section v-if="postInstall" class="flex flex-col gap-2">
      <VAlert variant="info">
        <span v-if="postInstall.message">{{ postInstall.message }}</span>
        <span v-else>Post-install: {{ postInstall.kind }}</span>
      </VAlert>
    </section>
  </div>
</template>
