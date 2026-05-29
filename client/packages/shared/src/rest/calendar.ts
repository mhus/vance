import type {
  CalendarEventCreateRequest,
  CalendarEventUpdateRequest,
  CalendarEventView,
  CalendarPlannerView,
  CalendarRebuildResponse,
} from '@vance/generated';
import { brainFetch } from './restClient';

function qs(params: Record<string, string>): string {
  const u = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) u.set(k, v);
  return u.toString();
}

export async function getCalendarPlanner(
  projectId: string,
  folder: string,
): Promise<CalendarPlannerView> {
  return brainFetch<CalendarPlannerView>(
    'GET',
    `calendar/planner?${qs({ projectId, folder })}`,
  );
}

export async function createCalendarEvent(
  projectId: string,
  folder: string,
  request: CalendarEventCreateRequest,
): Promise<CalendarEventView> {
  return brainFetch<CalendarEventView>(
    'POST',
    `calendar/events?${qs({ projectId, folder })}`,
    { body: request },
  );
}

export async function updateCalendarEvent(
  projectId: string,
  folder: string,
  id: string,
  request: CalendarEventUpdateRequest,
): Promise<CalendarEventView> {
  return brainFetch<CalendarEventView>(
    'PATCH',
    `calendar/events?${qs({ projectId, folder, id })}`,
    { body: request },
  );
}

export async function deleteCalendarEvent(
  projectId: string,
  folder: string,
  id: string,
): Promise<void> {
  return brainFetch<void>(
    'DELETE',
    `calendar/events?${qs({ projectId, folder, id })}`,
  );
}

export async function rebuildCalendarPlanner(
  projectId: string,
  folder: string,
): Promise<CalendarRebuildResponse> {
  return brainFetch<CalendarRebuildResponse>(
    'POST',
    `calendar/rebuild?${qs({ projectId, folder })}`,
  );
}
