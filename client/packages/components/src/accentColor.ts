import { AccentColor } from '@vance/generated';

/**
 * Tailwind {@code bg-*-500} class for each {@link AccentColor} swatch.
 * Used by list/tree renderers that show a small colored dot next to an
 * item — siblings to {@link VColorPicker} on the editing side.
 *
 * Returns an empty string when the color is null/undefined so callers
 * can spread the result into a {@code :class} array unconditionally.
 */
const DOT_CLASS: Record<AccentColor, string> = {
  [AccentColor.SLATE]: 'bg-slate-500',
  [AccentColor.RED]: 'bg-red-500',
  [AccentColor.ORANGE]: 'bg-orange-500',
  [AccentColor.AMBER]: 'bg-amber-500',
  [AccentColor.GREEN]: 'bg-green-500',
  [AccentColor.TEAL]: 'bg-teal-500',
  [AccentColor.CYAN]: 'bg-cyan-500',
  [AccentColor.BLUE]: 'bg-blue-500',
  [AccentColor.INDIGO]: 'bg-indigo-500',
  [AccentColor.PURPLE]: 'bg-purple-500',
  [AccentColor.PINK]: 'bg-pink-500',
  [AccentColor.ROSE]: 'bg-rose-500',
};

export function accentColorDotClass(
  color: AccentColor | null | undefined,
): string {
  if (!color) return '';
  return DOT_CLASS[color] ?? '';
}
