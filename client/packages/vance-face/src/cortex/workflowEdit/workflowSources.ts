import { stringify } from 'yaml';
import {
  EventSource,
  SchedulerSource,
  type EventSummary,
  type SchedulerSummary,
  type ServiceCatalogTriggerDto,
  type ToolServiceDto,
} from '@vance/generated';

/**
 * What starts a workflow, as the editor lists it: events that name it as their
 * target and schedulers that start it. Pure functions over the lists the
 * server already serves — nothing here talks to the server.
 *
 * Spec: {@code specification/public/workflows.md} §8.4, §8.6.
 */

export interface WorkflowSource {
  kind: 'event' | 'scheduler';
  /** The name of the event or scheduler document. */
  name: string;
  /** One line about it: {@code webhook}, or the cron expression. */
  summary: string;
  /** {@code <service>/<trigger>} when it was made from a catalog trigger. */
  from?: string;
  enabled: boolean;
  /** The editor can remove it: an event or a scheduler of this project (not one inherited from the tenant). */
  removable: boolean;
}

const WORKFLOW_PATH = /^_vance\/workflows\/(.+)\.ya?ml$/;

/**
 * The name a workflow document is started by, or {@code null} when the document
 * does not live under {@code _vance/workflows/} — only there is a workflow
 * found by name, and so only there can an event or scheduler point at it.
 */
export function workflowNameOf(path: string | undefined): string | null {
  const hit = path ? WORKFLOW_PATH.exec(path) : null;
  return hit ? hit[1] : null;
}

/** The events and schedulers that start {@code workflow}, events first, each group by name. */
export function sourcesOf(
  events: EventSummary[],
  schedulers: SchedulerSummary[],
  workflow: string,
): WorkflowSource[] {
  const fromEvents = events
    .filter((e) => e.workflow === workflow)
    .map<WorkflowSource>((e) => ({
      kind: 'event',
      name: e.name,
      summary: 'webhook',
      from: e.from,
      enabled: e.enabled,
      removable: e.source === EventSource.PROJECT,
    }));
  const fromSchedulers = schedulers
    .filter((s) => s.workflow === workflow)
    .map<WorkflowSource>((s) => ({
      kind: 'scheduler',
      name: s.name,
      summary: s.cron ?? '',
      enabled: s.enabled,
      removable: s.source === SchedulerSource.PROJECT,
    }));
  const byName = (a: WorkflowSource, b: WorkflowSource): number => a.name.localeCompare(b.name);
  return [...fromEvents.sort(byName), ...fromSchedulers.sort(byName)];
}

/** One trigger a catalog entry offers, with the entry it belongs to. */
export interface CatalogTriggerChoice {
  /** The catalog entry's name — what the server takes as {@code service}. */
  service: string;
  serviceTitle: string;
  trigger: ServiceCatalogTriggerDto;
}

/** The triggers of every service whose catalog entry has any. */
export function catalogTriggers(services: ToolServiceDto[]): CatalogTriggerChoice[] {
  const out: CatalogTriggerChoice[] = [];
  const seen = new Set<string>();
  for (const svc of services) {
    const catalog = svc.catalog;
    // Two packs can share one entry (jira, jira_rest): list its triggers once.
    if (!catalog || seen.has(catalog.name)) continue;
    seen.add(catalog.name);
    for (const trigger of catalog.triggers) {
      out.push({ service: catalog.name, serviceTitle: catalog.title, trigger });
    }
  }
  return out;
}

/** The YAML body of a scheduler that starts {@code workflow} on {@code cron}. */
export function schedulerYaml(input: { description: string; cron: string; workflow: string }): string {
  return stringify({
    description: input.description,
    cron: input.cron,
    workflow: input.workflow,
  });
}

/** A name for a new event or scheduler that is not taken yet: {@code base}, {@code base-2}, … */
export function freeSourceName(base: string, taken: readonly string[]): string {
  const clean = base.replace(/[^A-Za-z0-9_-]+/g, '-').replace(/^-+|-+$/g, '') || 'source';
  if (!taken.includes(clean)) return clean;
  for (let n = 2; ; n++) {
    if (!taken.includes(`${clean}-${n}`)) return `${clean}-${n}`;
  }
}
