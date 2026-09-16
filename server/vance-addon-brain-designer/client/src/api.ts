import { brainBaseUrl, brainFetch, getTenantId } from '@vance/shared';
import type { DesignerDesignCreateRequest } from './generated/designer/DesignerDesignCreateRequest';
import type { DesignerDesignMetaRequest } from './generated/designer/DesignerDesignMetaRequest';
import type { DesignerPreviewSession } from './generated/designer/DesignerPreviewSession';
import type { DesignerReorderRequest } from './generated/designer/DesignerReorderRequest';
import type { DesignerSkillList } from './generated/designer/DesignerSkillList';
import type { DesignerView } from './generated/designer/DesignerView';

function qs(params: Record<string, string>): string {
  const u = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) u.set(k, v);
  return u.toString();
}

/** Scans the app folder: designs, manifest title/description. */
export async function getDesigner(
  projectId: string,
  folder: string,
): Promise<DesignerView> {
  return brainFetch<DesignerView>(
    'GET',
    `addon/designer/view?${qs({ projectId, folder })}`,
  );
}

/**
 * Mints a preview session — the short-lived credential the sandboxed
 * iframe embeds into its content URLs. Re-mint when the previous
 * session's `expiresAt` is near.
 */
export async function createPreviewSession(
  projectId: string,
  folder: string,
): Promise<DesignerPreviewSession> {
  return brainFetch<DesignerPreviewSession>(
    'POST',
    `addon/designer/preview-session?${qs({ projectId, folder })}`,
  );
}

/** Creates a design folder (entry file + optional design.yaml). */
export async function createDesign(
  projectId: string,
  folder: string,
  request: DesignerDesignCreateRequest,
): Promise<DesignerView> {
  return brainFetch<DesignerView>(
    'POST',
    `addon/designer/design?${qs({ projectId, folder })}`,
    { body: request },
  );
}

/** Moves every file of one design to the trash (recoverable delete). */
export async function deleteDesign(
  projectId: string,
  folder: string,
  name: string,
): Promise<DesignerView> {
  return brainFetch<DesignerView>(
    'DELETE',
    `addon/designer/design?${qs({ projectId, folder, name })}`,
  );
}

/** Persists the complete design order after a drag-and-drop. */
export async function reorderDesigns(
  projectId: string,
  folder: string,
  order: string[],
): Promise<DesignerView> {
  return brainFetch<DesignerView>(
    'POST',
    `addon/designer/reorder?${qs({ projectId, folder })}`,
    { body: { order } satisfies DesignerReorderRequest },
  );
}

/** Edits one design's design.yaml (blank values clear the key). */
export async function updateDesignMeta(
  projectId: string,
  folder: string,
  request: DesignerDesignMetaRequest,
): Promise<DesignerView> {
  return brainFetch<DesignerView>(
    'POST',
    `addon/designer/design-meta?${qs({ projectId, folder })}`,
    { body: request },
  );
}

/**
 * Content URL for the sandboxed preview iframe:
 *
 * `<base>/brain/<tenant>/addon/designer/content/<appDocId>/<token>/<design>/<inner>`
 *
 * The token is a path segment on purpose: the iframe runs at an opaque
 * origin (sandbox="allow-scripts" — no cookies, no headers), and a
 * query-parameter token would be dropped by the first relative
 * sub-resource URL the design resolves. Inside the path, `css/style.css`
 * asked from `.../landing/` lands back inside the same token prefix.
 *
 * `innerPath` may be empty — the server maps that to the design's
 * `index.html`.
 */
export function designContentUrl(
  appDocumentId: string,
  token: string,
  design: string,
  innerPath = '',
): string {
  const tenant = getTenantId();
  if (!tenant) return '';
  const segments = [
    'brain',
    encodeURIComponent(tenant),
    'addon',
    'designer',
    'content',
    encodeURIComponent(appDocumentId),
    // JWT compact form: base64url characters and dots only — safe as
    // a path segment, but encoded defensively anyway.
    encodeURIComponent(token),
    encodeURIComponent(design),
  ];
  const base = `${brainBaseUrl()}/${segments.join('/')}`;
  if (!innerPath) {
    // Trailing slash: relative sub-resources resolve inside the design.
    return `${base}/`;
  }
  const inner = innerPath
    .split('/')
    .filter((s) => s.length > 0)
    .map((s) => encodeURIComponent(s))
    .join('/');
  return `${base}/${inner}`;
}

/**
 * Lists the design skills visible in the app's project scope — skills
 * tagged `design`, with the style.css convention reported per skill.
 *
 * When a chat is open, pass its session and process name: the server
 * joins in each skill's activation state for that chat's think-process
 * (READ-enforced on the process, same as the `process-skill` WS LIST).
 * Without them `active` stays absent from the response — without a
 * running chat there is no activation state, and "inactive" would be a
 * claim nobody can make.
 */
export async function getDesignSkills(
  projectId: string,
  sessionId?: string | null,
  processName?: string | null,
): Promise<DesignerSkillList> {
  const params: Record<string, string> = { projectId };
  if (sessionId && processName) {
    params.sessionId = sessionId;
    params.processName = processName;
  }
  return brainFetch<DesignerSkillList>('GET', `addon/designer/design-skills?${qs(params)}`);
}

/**
 * Style-preview URL for the skill catalogue's small sandboxed boxes:
 *
 * `<base>/brain/<tenant>/addon/designer/skill-preview/<appDocId>/<token>?skill=<name>`
 *
 * Same trust model as the content route — the short-lived preview token
 * sits as a path segment because the iframe runs at an opaque origin
 * with no cookies and no headers. The served document is self-contained
 * (the skill's real `style.css` inlined around a fixed demo body), so
 * unlike the content route there are no relative sub-resources and the
 * token only has to survive this one request.
 */
export function designSkillPreviewUrl(
  appDocumentId: string,
  token: string,
  skill: string,
): string {
  const tenant = getTenantId();
  if (!tenant) return '';
  const segments = [
    'brain',
    encodeURIComponent(tenant),
    'addon',
    'designer',
    'skill-preview',
    encodeURIComponent(appDocumentId),
    encodeURIComponent(token),
  ];
  return `${brainBaseUrl()}/${segments.join('/')}?${qs({ skill })}`;
}
