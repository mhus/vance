import { brainFetch } from '@vance/shared';
import type { DesktopView } from './generated/common-desktop/DesktopView';

function qs(params: Record<string, string>): string {
  const u = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) u.set(k, v);
  return u.toString();
}

/** Live desktop aggregation for the app at {@code folder}. */
export async function getDesktopStatus(
  projectId: string,
  folder: string,
): Promise<DesktopView> {
  return brainFetch<DesktopView>(
    'GET',
    `addon/desktop/status?${qs({ projectId, folder })}`,
  );
}
