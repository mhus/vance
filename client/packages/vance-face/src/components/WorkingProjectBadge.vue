<script setup lang="ts">
import { computed } from 'vue';
import { useI18n } from 'vue-i18n';
import { VBadge } from '@vance/components';
import { workingProject } from '@/process/workingProjectStore';

/**
 * Topbar badge with the session chat-process's working project — Eddie's
 * current focus ("spot"). Shows where agent-side tool calls (doc_*, team_*,
 * inbox_post without an explicit projectId) will land, which is otherwise
 * invisible in the UI.
 *
 * <p>Self-hiding when no spot is set: Arthur/Ford sessions are bound to
 * exactly one project (the breadcrumb already says where they are), and a
 * hub without a spot has nothing to show. Fed by the
 * {@code working-project-changed} push — see WorkingProjectPusher.
 */
const { t } = useI18n();

const visible = computed(() => workingProject.value !== null);
const tooltip = computed(() => t('workingProject.tooltip', { name: workingProject.value ?? '' }));
</script>

<template>
  <span v-if="visible" :title="tooltip" :aria-label="tooltip">
    <VBadge variant="neutral" size="sm" outline>
      {{ workingProject }}
    </VBadge>
  </span>
</template>
