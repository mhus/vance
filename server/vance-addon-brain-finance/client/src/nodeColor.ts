import { accentColorDotClass } from '@vance/components';
import { AccentColor } from '@vance/generated';

// A node color is an AccentColor enum name (picked from the shared swatches).
// Older trees may carry a free-form CSS color ("#4f8") — rendered as an inline
// background so those still show until the user picks a swatch.

function isAccent(v: string): v is AccentColor {
  return (Object.values(AccentColor) as string[]).includes(v);
}

/** Tailwind dot class for an accent color, '' otherwise. */
export function nodeColorClass(color: string | null | undefined): string {
  return color && isAccent(color) ? accentColorDotClass(color) : '';
}

/** Inline background for a legacy free-form color, undefined otherwise. */
export function nodeColorStyle(color: string | null | undefined): Record<string, string> | undefined {
  return color && !isAccent(color) ? { backgroundColor: color } : undefined;
}
