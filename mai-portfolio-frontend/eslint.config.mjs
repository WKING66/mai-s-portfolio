import withNuxt from './.nuxt/eslint.config.mjs'
import prettier from 'eslint-config-prettier/flat'
import stylistic from '@stylistic/eslint-plugin'

export default withNuxt(
  {
    name: 'frontend/generated-and-local-files',
    ignores: [
      'node_modules/**',
      '.nuxt/**',
      '.output/**',
      'dist/**',
      'coverage/**',
      '.local-backups/**',
    ],
  },
  prettier,
  {
    name: 'frontend/readable-control-flow',
    files: ['**/*.{js,mjs,cjs,ts,tsx,vue}'],
    plugins: { '@stylistic': stylistic },
    rules: {
      curly: ['error', 'all'],
      'no-nested-ternary': 'error',
      '@stylistic/max-statements-per-line': ['error', { max: 1 }],
      // 仅补充多行块约束；1tbs 与 Prettier 的输出一致，不另设缩进或换行风格。
      '@stylistic/brace-style': ['error', '1tbs', { allowSingleLine: false }],
    },
  },
)
