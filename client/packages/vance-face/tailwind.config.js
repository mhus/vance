/** @type {import('tailwindcss').Config} */
export default {
  content: [
    './*.html',
    './src/**/*.{vue,ts}',
    '../components/src/**/*.{vue,ts}',
    '../../../server/vance-addon-brain-*/client/src/**/*.{vue,ts}',
  ],
  // 'class' instead of 'media' so dark: utilities follow the explicit
  // webui.theme choice. The themeWeb.ts boot path toggles `dark` on
  // <html> based on the resolved theme (auto → match prefers-color-scheme,
  // light/dark → pinned).
  darkMode: 'class',
  theme: {
    extend: {
      fontFamily: {
        sans: ['Inter', 'ui-sans-serif', 'system-ui', 'sans-serif'],
        mono: ['JetBrains Mono', 'ui-monospace', 'SFMono-Regular', 'monospace'],
      },
    },
  },
  plugins: [require('daisyui')],
  daisyui: {
    themes: ['light', 'dark'],
    darkTheme: 'dark',
    base: true,
    styled: true,
    utils: true,
  },
};
