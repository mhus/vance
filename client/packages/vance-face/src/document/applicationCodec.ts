// Codec for `kind: application` documents — the manifest at the root
// of a Vance "app folder" (à la macOS .app bundles). Carries the
// app-type discriminator ($meta.app) plus an app-specific nested
// config block (e.g. `calendar: { lanes, gantt, conflicts, window }`
// for app=calendar).
//
// JSON and YAML only — Markdown makes no sense for an app manifest.
//
// Mirrors the server-side `ApplicationCodec` in `vance-shared`.

import {
  dumpYamlBody,
  parseYamlBody,
  unwrapJsonMeta,
} from './documentHeaderCodec';

export interface ApplicationDocument {
  /** Always `'application'`. */
  kind: string;
  /** App-type discriminator. Known v1 value: `'calendar'`. */
  app: string;
  title?: string;
  description?: string;
  /** App-specific configuration, keyed by the app type at the top
   *  level (e.g. `config.calendar = { … }`). The nested form lets a
   *  folder host multiple app faces in v2 without schema work. */
  config: Record<string, unknown>;
  extra: Record<string, unknown>;
}

export class ApplicationCodecError extends Error {
  constructor(message: string, public override readonly cause?: unknown) {
    super(message);
    this.name = 'ApplicationCodecError';
  }
}

// ── MIME helpers ─────────────────────────────────────────────────────

function isJson(mime: string): boolean {
  return mime === 'application/json';
}
function isYaml(mime: string): boolean {
  return mime === 'application/yaml'
    || mime === 'application/x-yaml'
    || mime === 'text/yaml'
    || mime === 'text/x-yaml';
}

// ── Public API ───────────────────────────────────────────────────────

export function parseApplication(body: string, mimeType: string): ApplicationDocument {
  if (isJson(mimeType)) return parseApplicationJson(body);
  if (isYaml(mimeType)) return parseApplicationYaml(body);
  throw new ApplicationCodecError(`Unsupported mime type for application: ${mimeType}`);
}

export function serializeApplication(doc: ApplicationDocument, mimeType: string): string {
  if (isJson(mimeType)) return serializeApplicationJson(doc);
  if (isYaml(mimeType)) return serializeApplicationYaml(doc);
  throw new ApplicationCodecError(`Unsupported mime type for application: ${mimeType}`);
}

export function isApplicationMime(mimeType: string | null | undefined): boolean {
  if (!mimeType) return false;
  return isJson(mimeType) || isYaml(mimeType);
}

export function emptyApplication(app: string = ''): ApplicationDocument {
  return { kind: 'application', app, config: {}, extra: {} };
}

// ── JSON ─────────────────────────────────────────────────────────────

function parseApplicationJson(body: string): ApplicationDocument {
  if (body.trim() === '') return emptyApplication();
  let parsed: unknown;
  try {
    parsed = JSON.parse(body);
  } catch (e) {
    throw new ApplicationCodecError(
      'Invalid JSON: ' + (e instanceof Error ? e.message : String(e)),
      e,
    );
  }
  if (!isObject(parsed)) {
    throw new ApplicationCodecError('Top-level JSON must be an object');
  }
  return promoteToDocument(unwrapJsonMeta(parsed));
}

function serializeApplicationJson(doc: ApplicationDocument): string {
  // $meta needs both kind + app inline — we build the wrapper by
  // hand since wrapJsonMeta only knows about `kind`.
  const meta: Record<string, unknown> = { kind: doc.kind || 'application' };
  if (doc.app) meta.app = doc.app;
  const wrapped: Record<string, unknown> = {
    $meta: meta,
    ...buildBody(doc),
  };
  return JSON.stringify(wrapped, null, 2) + '\n';
}

// ── YAML ─────────────────────────────────────────────────────────────

function parseApplicationYaml(body: string): ApplicationDocument {
  if (body.trim() === '') return emptyApplication();
  let merged: Record<string, unknown>;
  try {
    merged = parseYamlBody(body);
  } catch (e) {
    throw new ApplicationCodecError(
      'Invalid YAML: ' + (e instanceof Error ? e.message : String(e)),
      e,
    );
  }
  return promoteToDocument(merged);
}

function serializeApplicationYaml(doc: ApplicationDocument): string {
  const headerExtra: Record<string, unknown> = {};
  if (doc.app) headerExtra.app = doc.app;
  return dumpYamlBody(doc.kind || 'application', buildBody(doc), headerExtra);
}

// ── Shared promotion ─────────────────────────────────────────────────

function promoteToDocument(obj: Record<string, unknown>): ApplicationDocument {
  const kind = typeof obj.kind === 'string' && obj.kind ? obj.kind : 'application';
  const app = typeof obj.app === 'string' ? obj.app : '';
  const title = typeof obj.title === 'string' && obj.title ? obj.title : undefined;
  const description = typeof obj.description === 'string' && obj.description
    ? obj.description : undefined;

  const config: Record<string, unknown> = {};
  const extra: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(obj)) {
    if (k === 'kind' || k === 'app' || k === 'title' || k === 'description') continue;
    if (isObject(v)) {
      config[k] = v;
    } else {
      extra[k] = v;
    }
  }
  return { kind, app, title, description, config, extra };
}

function buildBody(doc: ApplicationDocument): Record<string, unknown> {
  const body: Record<string, unknown> = {};
  if (doc.title) body.title = doc.title;
  if (doc.description) body.description = doc.description;
  for (const [k, v] of Object.entries(doc.config)) {
    if (!(k in body)) body[k] = v;
  }
  for (const [k, v] of Object.entries(doc.extra)) {
    if (!(k in body)) body[k] = v;
  }
  return body;
}

function isObject(v: unknown): v is Record<string, unknown> {
  return typeof v === 'object' && v !== null && !Array.isArray(v);
}

// ── App-type helpers ─────────────────────────────────────────────────

export function applicationAppType(doc: ApplicationDocument): string {
  return doc.app || '';
}
