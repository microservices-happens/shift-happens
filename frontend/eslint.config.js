import js from '@eslint/js'
import globals from 'globals'
import reactHooks from 'eslint-plugin-react-hooks'
import reactRefresh from 'eslint-plugin-react-refresh'
import tseslint from 'typescript-eslint'

export default tseslint.config(
  { ignores: ['dist', 'node_modules'] },
  {
    files: ['**/*.{ts,tsx}'],
    extends: [js.configs.recommended, ...tseslint.configs.recommended],
    languageOptions: {
      ecmaVersion: 2022,
      globals: globals.browser,
    },
    plugins: {
      'react-hooks': reactHooks,
      'react-refresh': reactRefresh,
    },
    rules: {
      // rules-of-hooks stays an error. exhaustive-deps is a warning in the plugin.
      ...reactHooks.configs.recommended.rules,
      // React Compiler advisory rules (v7): they flag style/perf patterns such as the common
      // "setLoading(true) at the start of a fetch effect", not bugs. Warnings until the pages
      // are refactored (avoids conflicts with in-flight frontend branches).
      'react-hooks/set-state-in-effect': 'warn',
      'react-hooks/preserve-manual-memoization': 'warn',
      // Dev-experience rule only: warning, not a bug.
      'react-refresh/only-export-components': ['warn', { allowConstantExport: true }],
    },
  },
)
