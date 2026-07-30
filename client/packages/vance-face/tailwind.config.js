/** @type {import('tailwindcss').Config} */
// Loaded from src/style/app.css via `@config` — Tailwind 4 no longer
// auto-detects a JS config, and our `content` globs deliberately reach
// across packages (the shared component library + every addon client's
// source), which v4 automatic content detection would not scan.
// Dark mode + the daisyUI plugin now live in the CSS (`@custom-variant`,
// `@plugin "daisyui"`), so only content + theme remain here.
export default {
  content: [
    './*.html',
    './src/**/*.{vue,ts}',
    '../components/src/**/*.{vue,ts}',
    '../../../server/vance-addon-brain-*/client/src/**/*.{vue,ts}',
  ],
  theme: {
    extend: {
      fontFamily: {
        sans: ['Inter', 'ui-sans-serif', 'system-ui', 'sans-serif'],
        mono: ['JetBrains Mono', 'ui-monospace', 'SFMono-Regular', 'monospace'],
      },
    },
  },
};
