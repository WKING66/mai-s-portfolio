export default defineAppConfig({
  ui: {
    colors: { primary: 'sky', neutral: 'slate' },
    button: { slots: { base: 'cursor-pointer disabled:cursor-not-allowed rounded-xl' } },
    input: { slots: { base: 'rounded-md' } },
    textarea: { slots: { base: 'rounded-md' } },
  },
})
