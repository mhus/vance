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
