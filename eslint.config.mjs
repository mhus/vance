// Vance client workspace — shared ESLint config (flat config).
//
// Runs from the pnpm workspace root (repos/vance/) so it covers both
// client/packages/* and the addon clients under server/vance-addon-brain-*/client.
// Focus is correctness, not formatting: layout/formatting rules are disabled
// (no Prettier debate here), and generated code is excluded entirely.
//
// Invoke: `pnpm lint` from this directory. Lint failures break `wb build face`.
import js from '@eslint/js'
import { defineConfigWithVueTs, vueTsConfigs } from '@vue/eslint-config-typescript'
import pluginVue from 'eslint-plugin-vue'
import globals from 'globals'

// Tailwind palette utilities (bg-blue-500, text-gray-400, border-white …).
// DaisyUI component classes that @vance/components wraps: a view writing
// `btn` / `badge` / `alert` itself bypasses the CI roles of VButton, VBadge
// and VAlert.
// Local scoped classes like `badge-type` are not matched — only the bare
// class and DaisyUI's own modifiers.
const DAISY_COMPONENT = String.raw`(?:^|\s)(?:btn|badge|alert)(?:-(?:primary|secondary|accent|neutral|info|success|warning|error|ghost|outline|link|soft|dash|xs|sm|md|lg|xl|wide|block|circle|square))?(?=\s|$)`
const PALETTE = String.raw`\b(?:bg|text|border|ring|fill|stroke|outline|divide|from|to|via|accent|caret|decoration|shadow)-(?:slate|gray|zinc|neutral|stone|red|orange|amber|yellow|lime|green|emerald|teal|cyan|sky|blue|indigo|violet|purple|fuchsia|pink|rose)-\d{2,3}\b|\b(?:bg|text|border)-(?:white|black)\b`

export default defineConfigWithVueTs(
    {
        name: 'vance/ignores',
        ignores: [
            '**/dist/**',
            '**/dist-types/**',
            '**/coverage/**',
            '**/release/**',
            '**/build/**',
            '**/target/**',
            // Server-side runtime JS (Bistromath app-libs, guard scripts) —
            // injected globals, not part of the client TS surface.
            'server/**/src/main/resources/**',
            // Native wrapper trees (Capacitor/Electron/Safari): no hand-written
            // client TS to lint — `webkit` etc. are injected native globals.
            '**/ios/**',
            '**/android/**',
            'client/packages/vance-capture/safari/**',
            // Module-federation build temp output.
            '**/.__mf__temp/**',
            // Generated code: DTO output of the generate-java-to-ts-maven-plugin.
            'client/packages/generated/**',
            '**/src/generated/**',
            // vue-tsc declaration output emitted next to the sources (*.vue.d.ts,
            // *.vue.js) — build artifacts, already gitignored.
            '**/*.vue.d.ts',
            '**/*.vue.js',
            '**/*.vue.d.ts.map',
            '**/*.vue.js.map',
            '**/patches/**',
        ],
    },
    js.configs.recommended,
    // Browser globals for plain .js files (TS files have no-undef disabled by
    // typescript-eslint anyway — this closes the gap for the .js minority).
    {
        name: 'vance/globals',
        languageOptions: {
            globals: { ...globals.browser },
        },
    },
    pluginVue.configs['flat/recommended'],
    vueTsConfigs.recommended,
    // Corporate design (specification/public/corporate-design.md, agent/face.md):
    // what the CI forbids in templates is forbidden here too, so it cannot
    // creep back. Emoji / Unicode symbols are not icons (§5 — use VIcon or
    // VIconButton), Tailwind palette colours are not colours (§3 — use the
    // --v-* tokens). User content is data, not template text, and stays free.
    // The first block covers the component library; the second the views
    // (web UI, facelift shell, addons), which additionally may not write the
    // DaisyUI component classes the library wraps.
    {
        name: 'vance/corporate-design',
        files: ['client/packages/components/**/*.vue'],
        rules: {
            'vue/no-restricted-syntax': [
                'error',
                {
                    selector: String.raw`VElement[name='template'] VText[value=/[\u{1F300}-\u{1FAFF}\u{2600}-\u{27BF}\u{2B00}-\u{2BFF}\u{2190}-\u{21FF}\u{25A0}-\u{25FF}]/u]`,
                    message: 'Emoji / Unicode symbols are not icons — use <VIcon> or <VIconButton> (corporate-design.md §5).',
                },
                {
                    selector: String.raw`VAttribute[directive=false][key.name='class'] > VLiteral[value=/${PALETTE}/]`,
                    message: 'Tailwind palette colour — use a --v-* token, e.g. text-(--v-danger) (corporate-design.md §3).',
                },
                {
                    selector: String.raw`VAttribute[directive=true][key.argument.name='class'] Literal[value=/${PALETTE}/]`,
                    message: 'Tailwind palette colour — use a --v-* token, e.g. text-(--v-danger) (corporate-design.md §3).',
                },
            ],
        },
    },
    {
        name: 'vance/corporate-design-components',
        files: ['client/packages/vance-face/**/*.vue', 'client/packages/facelift-bridge/**/*.vue', 'server/**/client/**/*.vue'],
        rules: {
            'vue/no-restricted-syntax': [
                'error',
                {
                    selector: String.raw`VElement[name='template'] VText[value=/[\u{1F300}-\u{1FAFF}\u{2600}-\u{27BF}\u{2B00}-\u{2BFF}\u{2190}-\u{21FF}\u{25A0}-\u{25FF}]/u]`,
                    message: 'Emoji / Unicode symbols are not icons — use <VIcon> or <VIconButton> (corporate-design.md §5).',
                },
                {
                    selector: String.raw`VAttribute[directive=false][key.name='class'] > VLiteral[value=/${PALETTE}/]`,
                    message: 'Tailwind palette colour — use a --v-* token, e.g. text-(--v-danger) (corporate-design.md §3).',
                },
                {
                    selector: String.raw`VAttribute[directive=true][key.argument.name='class'] Literal[value=/${PALETTE}/]`,
                    message: 'Tailwind palette colour — use a --v-* token, e.g. text-(--v-danger) (corporate-design.md §3).',
                },
                {
                    selector: String.raw`VAttribute[directive=false][key.name='class'] > VLiteral[value=/${DAISY_COMPONENT}/]`,
                    message: 'DaisyUI btn/badge/alert class — use <VButton>, <VIconButton>, <VBadge> or <VAlert> (corporate-design.md §10.2).',
                },
                {
                    selector: String.raw`VAttribute[directive=true][key.argument.name='class'] Literal[value=/${DAISY_COMPONENT}/]`,
                    message: 'DaisyUI btn/badge/alert class — use <VButton>, <VIconButton>, <VBadge> or <VAlert> (corporate-design.md §10.2).',
                },
            ],
        },
    },
    {
        name: 'vance/rules',
        rules: {
            // Discards in destructuring are written with a `_` prefix.
            '@typescript-eslint/no-unused-vars': [
                'error',
                {
                    argsIgnorePattern: '^_',
                    varsIgnorePattern: '^_',
                    destructuredArrayIgnorePattern: '^_',
                    caughtErrorsIgnorePattern: '^_',
                },
            ],
            // Optional props without default are idiomatic Vue-3 + TS
            // (undefined is a valid state) — no artificial `default: undefined`.
            'vue/require-default-prop': 'off',
            // XSS guard per agent/face.md: every v-html site must either go
            // through the sanitiser path or carry a justified inline disable.
            // Errors, not warnings — this is a hard boundary.
            'vue/no-v-html': 'error',
            // ------------------------------------------------------------------
            // Template formatting is out of ESLint's scope here (stylistic
            // churn over ~1000 files buys nothing); correctness stays on.
            'vue/max-attributes-per-line': 'off',
            'vue/singleline-html-element-content-newline': 'off',
            'vue/multiline-html-element-content-newline': 'off',
            'vue/html-indent': 'off',
            'vue/html-self-closing': 'off',
            'vue/first-attribute-linebreak': 'off',
            'vue/html-closing-bracket-newline': 'off',
            'vue/attributes-order': 'off',
            'vue/new-line-between-multi-line-expression': 'off',
        },
    },
)
