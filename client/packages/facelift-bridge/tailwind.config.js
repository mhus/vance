/** @type {import('tailwindcss').Config} */
// The shell renders @vance/components, so their sources are scanned too.
export default {
  content: ['./index.html', './src/**/*.{vue,ts}', '../components/src/**/*.{vue,ts}'],
  theme: {
    extend: {},
  },
  plugins: [],
};
