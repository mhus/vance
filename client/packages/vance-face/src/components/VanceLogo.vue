<script setup lang="ts">
/**
 * Vance brand mark — mathematical italic small "v" (U+1D463), outlined
 * from STIX Two Math so it renders identically on every device without
 * relying on a math font being installed. {@code fill="currentColor"}
 * lets the consumer tint it via CSS (text-primary, opacity-70, …).
 *
 * <p>Sizing is purely a function of the parent's font-size and the
 * {@link Props.size} prop — the SVG itself has no width/height
 * attribute so flex/grid layouts can scale it freely. Aspect ratio is
 * preserved (468:536, the glyph's tight bounding box + 5 % padding).
 *
 * <p>The path is the bezier outline; do not edit by hand. Regenerate
 * via the snippet in `specification/responsive-focus-layout.md` if a
 * different math font is chosen.
 */
interface Props {
  /** Logical size; maps to a Tailwind text-* class for the SVG height. */
  size?: 'sm' | 'md' | 'lg' | 'xl';
}

const props = withDefaults(defineProps<Props>(), { size: 'md' });

const sizeClass: Record<NonNullable<Props['size']>, string> = {
  sm: 'h-5',   // ~20px — topbar inline
  md: 'h-8',   // ~32px — section headers
  lg: 'h-16',  // ~64px — login form
  xl: 'h-32',  // ~128px — splash / hero
};
</script>

<template>
  <svg
    xmlns="http://www.w3.org/2000/svg"
    viewBox="0 0 468 536"
    :class="['inline-block align-middle', sizeClass[props.size]]"
    aria-label="Vance"
    role="img"
  >
    <path
      fill="currentColor"
      transform="matrix(1 0 0 -1 -32 503)"
      d="M200 476 53 465V437C53 437 83 440 99 440C117 440 123 426 123 409C123 389 116 365 113 354L93 278C82 236 71 183 71 135C71 58 100 -9 200 -9C380 -9 479 206 479 358C479 429 448 479 403 479C382 479 365 464 365 437C365 393 426 369 426 299C426 206 368 55 231 55C170 55 153 99 153 151C153 196 166 249 175 284L222 476Z"
    />
  </svg>
</template>
