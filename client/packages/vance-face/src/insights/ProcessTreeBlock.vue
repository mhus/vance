<script setup lang="ts">
import { computed } from 'vue';
import { MarkdownView } from '@/components';
import type { ThinkProcessInsightsDto } from '@vance/generated';

export interface ProcessTreeNode {
  process: ThinkProcessInsightsDto;
  children: ProcessTreeNode[];
}

export type EventKind = 'spawn' | 'chat' | 'memory' | 'marvin' | 'pending';

export interface ProcessEvent {
  kind: EventKind;
  at: string | null;
  id: string;
  label: string;
  tag?: string;
  detail: string;
  detailIsMarkdown: boolean;
}

const props = defineProps<{
  node: ProcessTreeNode;
  /** All event lists keyed by process id. */
  eventsByProcess: Record<string, ProcessEvent[]>;
  /** Process ids whose event-list is collapsed (defaulting to expanded). */
  collapsedProcesses: ReadonlySet<string>;
  /** Composite keys "{processId}|{eventId}" for currently-expanded events. */
  expandedEvents: ReadonlySet<string>;
}>();

const emit = defineEmits<{
  (e: 'select-process', id: string): void;
  (e: 'toggle-process', id: string): void;
  (e: 'toggle-event', processId: string, eventId: string): void;
}>();

const events = computed<ProcessEvent[]>(() =>
  props.eventsByProcess[props.node.process.id] ?? []);

const collapsed = computed<boolean>(() =>
  props.collapsedProcesses.has(props.node.process.id));

function isExpanded(eventId: string): boolean {
  return props.expandedEvents.has(props.node.process.id + '|' + eventId);
}

function fmtTime(at: string | null): string {
  if (at == null) return '—';
  try {
    return new Date(at).toISOString().replace('T', ' ').slice(0, 19);
  } catch {
    return at;
  }
}

function chatRoleClass(label: string): string {
  if (label.startsWith('USER:')) return 'badge-user';
  if (label.startsWith('ASSISTANT:')) return 'badge-assistant';
  if (label.startsWith('SYSTEM:')) return 'badge-system';
  return '';
}
</script>

<template>
  <div class="tp-block">
    <div class="tp-header">
      <button
        class="tp-chev"
        type="button"
        @click="emit('toggle-process', node.process.id)"
      >{{ collapsed ? '▸' : '▾' }}</button>
      <button
        class="tp-name"
        type="button"
        title="Open in process view"
        @click="emit('select-process', node.process.id)"
      >{{ node.process.name }}</button>
      <span class="tp-engine">{{ node.process.thinkEngine }}</span>
      <span class="tp-status">{{ node.process.status }}</span>
      <span v-if="node.process.recipeName" class="tp-recipe">· {{ node.process.recipeName }}</span>
      <span class="tp-count">
        {{ events.length }} event<span v-if="events.length !== 1">s</span>
      </span>
    </div>

    <ul v-if="!collapsed" class="tp-events">
      <li
        v-for="ev in events"
        :key="ev.id"
        class="tp-event"
      >
        <button
          class="tp-event-row"
          type="button"
          @click="emit('toggle-event', node.process.id, ev.id)"
        >
          <span class="tp-event-chev">{{ isExpanded(ev.id) ? '▾' : '▸' }}</span>
          <span class="tp-event-time">{{ fmtTime(ev.at) }}</span>
          <span
            class="tp-event-kind"
            :class="['kind-' + ev.kind, ev.kind === 'chat' ? chatRoleClass(ev.label) : '']"
          >{{ ev.kind }}</span>
          <span class="tp-event-label">{{ ev.label }}</span>
          <span v-if="ev.tag" class="tp-event-tag">{{ ev.tag }}</span>
        </button>
        <div v-if="isExpanded(ev.id)" class="tp-event-detail">
          <MarkdownView v-if="ev.detailIsMarkdown" :source="ev.detail" />
          <pre v-else class="tp-event-json">{{ ev.detail }}</pre>
        </div>
      </li>
    </ul>

    <ul v-if="!collapsed && node.children.length > 0" class="tp-children">
      <li v-for="child in node.children" :key="child.process.id">
        <ProcessTreeBlock
          :node="child"
          :events-by-process="eventsByProcess"
          :collapsed-processes="collapsedProcesses"
          :expanded-events="expandedEvents"
          @select-process="(id) => emit('select-process', id)"
          @toggle-process="(id) => emit('toggle-process', id)"
          @toggle-event="(pid, eid) => emit('toggle-event', pid, eid)"
        />
      </li>
    </ul>
  </div>
</template>

<style scoped>
.tp-block {
  border: 1px solid hsl(var(--bc) / 0.12);
  border-radius: 0.5rem;
  padding: 0.5rem 0.75rem;
  background: hsl(var(--b1));
}
.tp-header {
  display: flex;
  align-items: baseline;
  gap: 0.5rem;
  flex-wrap: wrap;
}
.tp-chev {
  background: transparent;
  cursor: pointer;
  font-size: 0.875rem;
  padding: 0 0.25rem;
}
.tp-name {
  background: transparent;
  cursor: pointer;
  font-family: ui-monospace, monospace;
  font-weight: 600;
  font-size: 0.95rem;
  text-decoration: underline;
  color: hsl(var(--p));
}
.tp-engine { font-size: 0.75rem; opacity: 0.7; }
.tp-status { font-size: 0.75rem; opacity: 0.6; }
.tp-recipe { font-size: 0.75rem; opacity: 0.6; }
.tp-count  { font-size: 0.75rem; opacity: 0.5; margin-left: auto; }

.tp-events {
  list-style: none;
  padding: 0.25rem 0 0 1.5rem;
  margin: 0;
  border-left: 2px solid hsl(var(--bc) / 0.12);
  display: flex;
  flex-direction: column;
  gap: 0.15rem;
}
.tp-event { list-style: none; }
.tp-event-row {
  display: flex;
  align-items: baseline;
  gap: 0.5rem;
  width: 100%;
  text-align: left;
  padding: 0.2rem 0.4rem;
  border-radius: 0.25rem;
  background: transparent;
  cursor: pointer;
}
.tp-event-row:hover { background: hsl(var(--bc) / 0.05); }
.tp-event-chev { font-size: 0.7rem; opacity: 0.6; width: 0.7rem; }
.tp-event-time {
  font-family: ui-monospace, monospace;
  font-size: 0.7rem;
  opacity: 0.55;
  min-width: 11rem;
}
.tp-event-kind {
  font-family: ui-monospace, monospace;
  font-size: 0.7rem;
  padding: 0 0.4rem;
  border-radius: 0.25rem;
  background: hsl(var(--bc) / 0.08);
  min-width: 4rem;
  text-align: center;
}
.kind-chat    { background: hsl(var(--p)  / 0.15); color: hsl(var(--p)); }
.kind-memory  { background: hsl(var(--in) / 0.15); color: hsl(var(--inc)); }
.kind-marvin  { background: hsl(var(--su) / 0.15); color: hsl(var(--suc)); }
.kind-pending { background: hsl(var(--wa) / 0.15); color: hsl(var(--wac)); }
.kind-spawn   { background: hsl(var(--bc) / 0.1);  color: hsl(var(--bc) / 0.7); }
.badge-user      { outline: 1px solid hsl(var(--p)); }
.badge-assistant { outline: 1px solid hsl(var(--su)); }
.badge-system    { outline: 1px solid hsl(var(--wa)); }
.tp-event-label {
  flex: 1 1 auto;
  font-size: 0.85rem;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.tp-event-tag {
  font-size: 0.7rem;
  opacity: 0.6;
  font-style: italic;
}

.tp-event-detail {
  padding: 0.4rem 0.5rem 0.5rem 2.6rem;
}
.tp-event-json {
  background: hsl(var(--bc) / 0.05);
  padding: 0.5rem 0.75rem;
  border-radius: 0.375rem;
  font-size: 0.75rem;
  white-space: pre-wrap;
  word-break: break-word;
}

.tp-children {
  list-style: none;
  padding: 0.5rem 0 0 1.5rem;
  margin: 0;
}
.tp-children > li { margin-top: 0.5rem; }
</style>
