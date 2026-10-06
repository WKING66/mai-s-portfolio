export default defineAppConfig({
  ui: {
    colors: { primary: 'sky', neutral: 'slate' },
    button: { slots: { base: 'cursor-pointer disabled:cursor-not-allowed rounded-xl' } },
    input: { slots: { base: 'rounded-xl' } },
    textarea: { slots: { base: 'rounded-xl' } },
  },
})
