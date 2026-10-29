import { bundledLanguagesInfo } from 'shiki/langs'

export const CODE_LANGUAGES = [
  { label: '纯文本', value: 'text', aliases: 'plain plaintext txt' },
  ...bundledLanguagesInfo
    .filter((language) => language.id !== 'mermaid')
    .map((language) => ({
      label: language.name,
      value: language.id,
      aliases: (language.aliases ?? []).join(' '),
    }))
    .sort((left, right) => left.label.localeCompare(right.label)),
]
const aliases = new Map(
  CODE_LANGUAGES.flatMap((language) =>
    [language.value, ...language.aliases.split(' ')]
      .filter(Boolean)
      .map((name) => [name.toLowerCase(), language.value]),
  ),
)
export function resolveCodeLanguage(language: string) {
  return aliases.get(language.trim().toLowerCase()) ?? 'text'
}

export function codeLanguageOptions(language: string) {
  const value = language.trim().toLowerCase()
  return !value || aliases.has(value)
    ? CODE_LANGUAGES
    : [...CODE_LANGUAGES, { label: language + '（未识别）', value: language, aliases: '' }]
}
