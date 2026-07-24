import { brainFetch } from '@vance/shared';
import type { FinanceTreeDto } from './generated/finance/FinanceTreeDto';
import type {
  FinanceComputed,
  FinanceProjection,
  Granularity,
  ProcessorInfo,
  ReportResult,
} from './types';

function qs(params: Record<string, string>): string {
  const u = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) u.set(k, v);
  return u.toString();
}

export async function getTree(projectId: string, path: string): Promise<FinanceTreeDto> {
  return brainFetch<FinanceTreeDto>('GET', `addon/finance/tree?${qs({ projectId, path })}`);
}

export async function putTree(
  projectId: string,
  path: string,
  tree: FinanceTreeDto,
): Promise<FinanceTreeDto> {
  return brainFetch<FinanceTreeDto>('PUT', `addon/finance/tree?${qs({ projectId, path })}`, {
    body: tree,
  });
}

export async function createTree(
  projectId: string,
  path: string,
  title?: string,
): Promise<FinanceTreeDto> {
  const params: Record<string, string> = { projectId, path };
  if (title) params.title = title;
  return brainFetch<FinanceTreeDto>('POST', `addon/finance/create?${qs(params)}`);
}

export async function calc(projectId: string, path: string): Promise<FinanceComputed> {
  return brainFetch<FinanceComputed>('POST', `addon/finance/calc?${qs({ projectId, path })}`);
}

export async function getSnapshot(projectId: string, path: string): Promise<FinanceComputed> {
  return brainFetch<FinanceComputed>('GET', `addon/finance/snapshot?${qs({ projectId, path })}`);
}

export async function project(
  projectId: string,
  path: string,
  from: string,
  to: string,
  granularity: Granularity,
): Promise<FinanceProjection> {
  return brainFetch<FinanceProjection>(
    'GET',
    `addon/finance/project?${qs({ projectId, path, from, to, granularity })}`,
  );
}

export async function listProcessors(projectId: string): Promise<ProcessorInfo[]> {
  return brainFetch<ProcessorInfo[]>('GET', `addon/finance/processors?${qs({ projectId })}`);
}

export async function generateReport(
  projectId: string,
  path: string,
  processor: string,
  params: Record<string, unknown>,
  persist: boolean,
  outputPath?: string,
): Promise<ReportResult> {
  const q: Record<string, string> = { projectId, path, processor, persist: String(persist) };
  if (outputPath) q.outputPath = outputPath;
  return brainFetch<ReportResult>('POST', `addon/finance/report?${qs(q)}`, { body: params });
}
