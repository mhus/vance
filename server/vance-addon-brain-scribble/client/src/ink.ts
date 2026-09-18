/**
 * Ink pipeline: raw pointer samples → committed stroke.
 *
 * <p>One grammar end to end — the wire shape {@code [[x, y, pressure], …]} is
 * the only representation that ever leaves this module. The pipeline is
 * one-directional on purpose: the raw 240 Hz pointer stream is simplified
 * (RDP) and rounded at commit time, and only the simplified points are kept.
 *
 * <p>The outline rendering uses `perfect-freehand` (MIT, steveruizok): the
 * pressure-aware variable-width stroke is the reason handwriting feels right.
 */
import { getStroke } from 'perfect-freehand';
import type { ScribbleStrokeDto } from './generated/scribble/ScribbleStrokeDto';

/** One raw input sample in sheet space. */
export interface InkPoint {
  x: number;
  y: number;
  pressure: number;
}

/** Pen sizes — three fixed values, deliberately no slider. */
export type PenWidth = 's' | 'm' | 'l';

/** Stroke width per size, in sheet units (A4 @150dpi ≈ 1 unit ≈ 0.17 mm). */
export const WIDTH_PX: Record<string, number> = { s: 2.5, m: 5, l: 11 };

/** Palette: color index → CSS hex. The doc stores only the index. */
export const PALETTE: Record<string, string> = {
  '1': '#111827',
  '2': '#dc2626',
  '3': '#2563eb',
  '4': '#16a34a',
};

export function paletteColor(index: string): string {
  return PALETTE[index] ?? PALETTE['1'];
}

/**
 * Simplify with Ramer–Douglas–Peucker. Epsilon is in sheet units; ~0.7 keeps
 * handwriting crisp while collapsing the micro-jitter of a hand-held pen —
 * typically a 3–5× point reduction without visible loss.
 */
export function simplify(points: InkPoint[], epsilon = 0.7): InkPoint[] {
  if (points.length <= 2) return points;
  const keep = new Uint8Array(points.length);
  keep[0] = 1;
  keep[points.length - 1] = 1;
  rdpMark(points, 0, points.length - 1, epsilon, keep);
  const out: InkPoint[] = [];
  for (let i = 0; i < points.length; i++) if (keep[i]) out.push(points[i]);
  return out;
}

function rdpMark(pts: InkPoint[], first: number, last: number, eps: number, keep: Uint8Array): void {
  let maxDist = 0;
  let index = -1;
  for (let i = first + 1; i < last; i++) {
    const d = perpendicularDistance(pts[i], pts[first], pts[last]);
    if (d > maxDist) {
      maxDist = d;
      index = i;
    }
  }
  if (index < 0 || maxDist <= eps) return;
  keep[index] = 1;
  rdpMark(pts, first, index, eps, keep);
  rdpMark(pts, index, last, eps, keep);
}

function perpendicularDistance(p: InkPoint, a: InkPoint, b: InkPoint): number {
  const dx = b.x - a.x;
  const dy = b.y - a.y;
  if (dx === 0 && dy === 0) return Math.hypot(p.x - a.x, p.y - a.y);
  // Distance point→line (not point→segment): RDP convention.
  return Math.abs(dy * p.x - dx * p.y + b.x * a.y - b.y * a.x) / Math.hypot(dx, dy);
}

/** Commit a raw point list: simplify, round, clamp pressure. */
export function commitPoints(raw: InkPoint[]): ScribbleStrokeDto['points'] {
  const simplified = simplify(raw);
  return simplified.map((p) => [
    Math.round(p.x),
    Math.round(p.y),
    Math.round(Math.min(1, Math.max(0, p.pressure)) * 10) / 10,
  ]);
}

/** Wire points → render points. */
export function toInkPoints(points: ScribbleStrokeDto['points']): InkPoint[] {
  return points.map((p) => ({ x: p[0], y: p[1], pressure: p.length > 2 ? p[2] : 0.5 }));
}

/**
 * Outline of a stroke: perfect-freehand's filled outline, as a `Path2D` ready
 * to fill on a context that is already transformed into sheet space.
 *
 * <p>{@code simulatePressure} only when the input had none — a mouse or a
 * finger reports constant pressure, and velocity-based simulation is what
 * makes those strokes look like ink instead of a wire.
 */
export function strokePath(stroke: ScribbleStrokeDto, simulatePressure = false): Path2D {
  const ink = toInkPoints(stroke.points);
  const outline = getStroke(
    ink.map((p) => [p.x, p.y, p.pressure] as [number, number, number]),
    {
      size: WIDTH_PX[(stroke.width as PenWidth) ?? 'm'] ?? WIDTH_PX.m,
      thinning: 0.6,
      smoothing: 0.5,
      streamline: 0.35,
      simulatePressure,
      last: true,
    },
  );
  const path = new Path2D();
  if (outline.length === 0) return path;
  path.moveTo(outline[0][0], outline[0][1]);
  for (let i = 1; i < outline.length; i++) {
    // Midpoint quadratics: the outline is dense, so this reads smooth.
    const [x0, y0] = outline[i - 1];
    const [x1, y1] = outline[i];
    path.quadraticCurveTo(x0, y0, (x0 + x1) / 2, (y0 + y1) / 2);
  }
  path.closePath();
  return path;
}

/**
 * Hit-test a stroke for the eraser: distance from a sheet-space point to the
 * stroke's point chain. Threshold covers the stroke's own width so touching
 * the ink (not its center line) erases.
 */
export function strokeHit(stroke: ScribbleStrokeDto, x: number, y: number): boolean {
  const threshold = ((WIDTH_PX[stroke.width] ?? WIDTH_PX.m) / 2) + 6;
  const pts = stroke.points;
  if (pts.length === 0) return false;
  if (pts.length === 1) return Math.hypot(pts[0][0] - x, pts[0][1] - y) <= threshold;
  for (let i = 1; i < pts.length; i++) {
    if (segmentDistance(x, y, pts[i - 1], pts[i]) <= threshold) return true;
  }
  return false;
}

function segmentDistance(px: number, py: number, a: number[], b: number[]): number {
  const ax = a[0];
  const ay = a[1];
  const bx = b[0];
  const by = b[1];
  const dx = bx - ax;
  const dy = by - ay;
  const lenSq = dx * dx + dy * dy;
  if (lenSq === 0) return Math.hypot(px - ax, py - ay);
  let t = ((px - ax) * dx + (py - ay) * dy) / lenSq;
  t = Math.max(0, Math.min(1, t));
  return Math.hypot(px - (ax + t * dx), py - (ay + t * dy));
}
