// Hand-typed response shapes for the finance REST endpoints whose Java
// side returns model records directly (calc/project/processors/report).
// The editable tree shape is generated from Java DTOs — see ./generated/finance.

export interface NodeSnapshot {
  name: string;
  perYear: number;
  perMonth: number;
  perWeek: number;
  perDay: number;
  base: number;
  interest: number;
  oneTimeSum: number;
}

export interface FinanceComputed {
  computedAt?: string | null;
  nodes: NodeSnapshot[];
}

export interface ProjectionPeriod {
  label: string;
  from: string;
  to: string;
}

export interface ProjectionRow {
  name: string;
  amounts: number[];
  total: number;
}

export interface FinanceProjection {
  periods: ProjectionPeriod[];
  rows: ProjectionRow[];
}

export interface ProcessorInfo {
  type: string;
  title: string;
  outputKind: string;
  paramForm?: string | null;
}

export interface ReportResult {
  outputKind: string;
  mimeType: string;
  body?: string | null;
  path?: string | null;
  id?: string | null;
}

export type Granularity = 'day' | 'week' | 'month' | 'year';
export type ValueModeWire = 'recurring' | 'one_time';

export type NodeAction =
  | 'select'
  | 'add-child'
  | 'remove'
  | 'move-up'
  | 'move-down'
  | 'indent'
  | 'outdent';
