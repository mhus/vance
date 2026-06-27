import type { CalendarEventCreateRequest } from './generated/calendar/CalendarEventCreateRequest';
import type { CalendarEventUpdateRequest } from './generated/calendar/CalendarEventUpdateRequest';
import type { CalendarEventView } from './generated/calendar/CalendarEventView';
import type { CalendarPlannerView } from './generated/calendar/CalendarPlannerView';
import type { CalendarRebuildResponse } from './generated/calendar/CalendarRebuildResponse';
import { brainFetch } from '@vance/shared';

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
    `addon/calendar/planner?${qs({ projectId, folder })}`,
  );
}

export async function createCalendarEvent(
  projectId: string,
  folder: string,
  request: CalendarEventCreateRequest,
): Promise<CalendarEventView> {
  return brainFetch<CalendarEventView>(
    'POST',
    `addon/calendar/events?${qs({ projectId, folder })}`,
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
    `addon/calendar/events?${qs({ projectId, folder, id })}`,
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
    `addon/calendar/events?${qs({ projectId, folder, id })}`,
  );
}

export async function rebuildCalendarPlanner(
  projectId: string,
  folder: string,
): Promise<CalendarRebuildResponse> {
  return brainFetch<CalendarRebuildResponse>(
    'POST',
    `addon/calendar/rebuild?${qs({ projectId, folder })}`,
  );
}
