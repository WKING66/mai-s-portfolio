import { computed, ref, shallowRef } from 'vue'
import type { BlogImportResource } from '../api/blogs'
import { MARKDOWN_EXTENSION } from '../constants/blogs'

/** 仅维护待导入文件；解析、归档和草稿创建仍属于既有导入 API。 */
export function useBlogImportSelection() {
  const importMode = ref<'file' | 'folder'>('file')
  const markdownFile = shallowRef<File | null>(null)
  const attachmentFiles = shallowRef<File[] | null>(null)
  const folderFiles = shallowRef<File[]>([])
  const markdownPath = ref('')
  const hasConfirmedRemoteRights = ref(false)
  // Remount FileUpload，清除组件内部的原生输入及折叠状态，不写只读 FileList。
  const uploadRevision = ref(0)
  const markdownCandidates = computed(() =>
    folderFiles.value
      .filter((file) => file.name.toLowerCase().endsWith(MARKDOWN_EXTENSION))
      .map((file) => ({ label: file.webkitRelativePath, value: file.webkitRelativePath })),
  )
  const sourceFile = computed(() =>
    importMode.value === 'file'
      ? markdownFile.value
      : (folderFiles.value.find((file) => file.webkitRelativePath === markdownPath.value) ?? null),
  )
  const importResources = computed<BlogImportResource[]>(() => {
    if (importMode.value === 'file') {
      return (attachmentFiles.value ?? []).map((file) => ({ file, path: file.name }))
    }
    if (!sourceFile.value) {
      return []
    }
    // 仅收集所选正文目录内的资源，路径始终相对正文；不改动 File 身份。
    const directory = markdownPath.value.slice(0, markdownPath.value.lastIndexOf('/') + 1)
    return folderFiles.value
      .filter((file) => file !== sourceFile.value && file.webkitRelativePath.startsWith(directory))
      .map((file) => ({ file, path: file.webkitRelativePath.slice(directory.length) }))
  })
  const selectionIssue = computed(() => {
    if (!sourceFile.value) {
      if (
        importMode.value === 'folder' &&
        folderFiles.value.length &&
        !markdownCandidates.value.length
      ) {
        return '文件夹中没有 .md 正文，请重新选择。'
      }
      return importMode.value === 'folder' && markdownCandidates.value.length > 1
        ? '文件夹中有多份 Markdown，请先指定要导入的正文。'
        : ''
    }
    if (!sourceFile.value.name.toLowerCase().endsWith(MARKDOWN_EXTENSION)) {
      return '正文请选择 .md 文件。'
    }
    if (!sourceFile.value.size) {
      return '正文文件为空，请选择有内容的 Markdown 文件。'
    }
    const paths = importResources.value.map((resource) => resource.path)
    return new Set(paths).size !== paths.length
      ? '附件存在同名文件，请移除重复项，或改用文档文件夹保留目录。'
      : ''
  })
  function reset() {
    // 1. 清空两种模式的临时文件和权利确认，避免切换后沿用旧输入。
    markdownFile.value = null
    attachmentFiles.value = null
    folderFiles.value = []
    markdownPath.value = ''
    hasConfirmedRemoteRights.value = false
    // 2. 更新组件 key，使原生文件选择和折叠状态一起重置。
    uploadRevision.value++
  }
  function changeMode(next: unknown) {
    // 先验证真正的模式变化，再重置；无效值和相同模式不会丢失选择。
    if ((next !== 'file' && next !== 'folder') || next === importMode.value) {
      return
    }
    reset()
    importMode.value = next
  }
  function selectFolder(files: File[]) {
    // 取消系统选择器时保留之前选择。
    if (!files.length) {
      return
    }
    // 单正文可直接选定，多正文仍由用户明确指定，不擅自选择第一项。
    folderFiles.value = files
    markdownPath.value =
      markdownCandidates.value.length === 1 ? markdownCandidates.value[0]!.value : ''
  }
  return {
    mode: importMode,
    markdown: markdownFile,
    extraFiles: attachmentFiles,
    folderFiles,
    bodyPath: markdownPath,
    rights: hasConfirmedRemoteRights,
    revision: uploadRevision,
    candidates: markdownCandidates,
    source: sourceFile,
    resources: importResources,
    selectionIssue,
    reset,
    changeMode,
    selectFolder,
  }
}
