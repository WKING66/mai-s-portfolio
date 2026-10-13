import { computed, ref, shallowRef } from 'vue'
import type { BlogImportResource } from '../api/blogs'
import { MARKDOWN_EXTENSION } from '../constants/blogs'

/** 仅维护待导入文件；解析、归档和草稿创建仍属于既有导入 API。 */
export function useBlogImportSelection() {
  const mode = ref<'file' | 'folder'>('file')
  const markdown = shallowRef<File | null>(null)
  const extraFiles = shallowRef<File[] | null>(null)
  const folderFiles = shallowRef<File[]>([])
  const bodyPath = ref('')
  const rights = ref(false)
  // Remount FileUpload，清除组件内部的原生输入及折叠状态，不写只读 FileList。
  const revision = ref(0)
  const candidates = computed(() => folderFiles.value
    .filter(file => file.name.toLowerCase().endsWith(MARKDOWN_EXTENSION))
    .map(file => ({ label: file.webkitRelativePath, value: file.webkitRelativePath })))
  const source = computed(() => mode.value === 'file' ? markdown.value
    : folderFiles.value.find(file => file.webkitRelativePath === bodyPath.value) ?? null)
  const resources = computed<BlogImportResource[]>(() => {
    if (mode.value === 'file') return (extraFiles.value ?? []).map(file => ({ file, path: file.name }))
    if (!source.value) return []
    const directory = bodyPath.value.slice(0, bodyPath.value.lastIndexOf('/') + 1)
    return folderFiles.value.filter(file => file !== source.value && file.webkitRelativePath.startsWith(directory))
      .map(file => ({ file, path: file.webkitRelativePath.slice(directory.length) }))
  })
  const selectionIssue = computed(() => {
    if (!source.value) {
      if (mode.value === 'folder' && folderFiles.value.length && !candidates.value.length) return '文件夹中没有 .md 正文，请重新选择。'
      return mode.value === 'folder' && candidates.value.length > 1 ? '文件夹中有多份 Markdown，请先指定要导入的正文。' : ''
    }
    if (!source.value.name.toLowerCase().endsWith(MARKDOWN_EXTENSION)) return '正文请选择 .md 文件。'
    if (!source.value.size) return '正文文件为空，请选择有内容的 Markdown 文件。'
    const paths = resources.value.map(resource => resource.path)
    return new Set(paths).size !== paths.length ? '附件存在同名文件，请移除重复项，或改用文档文件夹保留目录。' : ''
  })
  function reset() {
    markdown.value = null
    extraFiles.value = null
    folderFiles.value = []
    bodyPath.value = ''
    rights.value = false
    revision.value++
  }
  function changeMode(next: unknown) {
    if ((next !== 'file' && next !== 'folder') || next === mode.value) return
    reset()
    mode.value = next
  }
  function selectFolder(files: File[]) {
    if (!files.length) return // 取消系统选择器保留之前选择。
    folderFiles.value = files
    bodyPath.value = candidates.value.length === 1 ? candidates.value[0]!.value : ''
  }
  return { mode, markdown, extraFiles, folderFiles, bodyPath, rights, revision, candidates, source, resources, selectionIssue, reset, changeMode, selectFolder }
}
