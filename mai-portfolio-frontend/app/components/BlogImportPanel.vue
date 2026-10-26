<script setup lang="ts">
import { useBlogApi } from '../api/blogs'
import { useBlogImportSelection } from '../composables/useBlogImportSelection'
import { MARKDOWN_EXTENSION } from '../constants/blogs'
import { ADMIN_PATHS } from '../constants/admin'

const blogApi = useBlogApi()
const feedback = useFeedback()
const importSelection = useBlogImportSelection()
const {
  mode: importMode,
  markdown: markdownFile,
  extraFiles: attachmentFiles,
  folderFiles: selectedFolderFiles,
  bodyPath: selectedBodyPath,
  rights: areRemoteRightsConfirmed,
  revision: selectionRevision,
  candidates: markdownCandidates,
  source: selectedMarkdown,
  resources: selectedResources,
  selectionIssue,
} = importSelection
const importModeOptions = [
  { value: 'file', label: 'Markdown 文件' },
  { value: 'folder', label: '文档文件夹' },
]
const folderInput = ref<HTMLInputElement | null>(null)
const isImporting = ref(false)
let importRevision = 0
let pendingImport: { revision: number; requestId: string } | undefined
watch(
  [selectedMarkdown, selectedResources, areRemoteRightsConfirmed],
  () => {
    pendingImport = undefined
  },
  { flush: 'sync' },
)
const fileUploadUi = {
  root: 'min-w-0',
  base: 'min-w-0',
  label: 'break-words',
  description: 'break-words',
  file: 'min-w-0',
  fileWrapper: 'min-w-0',
  fileName: 'truncate',
}
const canImport = computed(
  () => Boolean(selectedMarkdown.value) && !selectionIssue.value && !isImporting.value,
)

// 1. 导入请求执行期间锁定来源，避免已经创建的草稿与界面所选文件不一致。
function clearSelection() {
  if (isImporting.value) {
    return
  }
  if (folderInput.value) {
    folderInput.value.value = ''
  }
  importSelection.reset()
}
function changeMode(next: unknown) {
  // POST 可创建持久化草稿，不用取消网络请求假装撤销创建。
  if (isImporting.value) {
    return
  }
  if (folderInput.value) {
    folderInput.value.value = ''
  }
  importSelection.changeMode(next)
}
function selectFolder(event: Event) {
  const input = event.currentTarget as HTMLInputElement
  const files = Array.from(input.files ?? [])
  // 取消选择保留之前的文档，不把有效选择清空。
  if (!files.length) {
    return
  }
  importSelection.selectFolder(files)
  // 允许再次选择同一文件夹，以读取用户在本地更新后的内容。
  input.value = ''
}
// 2. 按当前正文、相对资源路径和权利选项创建草稿，响应只更新当前导入操作。
async function importDocument() {
  if (!canImport.value || !selectedMarkdown.value) {
    return
  }
  if (!pendingImport || pendingImport.revision !== selectionRevision.value) {
    pendingImport = { revision: selectionRevision.value, requestId: crypto.randomUUID() }
  }
  isImporting.value = true
  const currentImportRevision = ++importRevision
  try {
    const importedBlogPost = await blogApi.importDocument(
      selectedMarkdown.value,
      selectedResources.value,
      areRemoteRightsConfirmed.value,
      pendingImport.requestId,
    )
    if (currentImportRevision !== importRevision) {
      return
    }
    pendingImport = undefined
    // 3. 草稿创建成功与资源保存成功分别提示，再进入草稿编辑页继续处理。
    const unresolvedResourceCount = importedBlogPost.resources.filter(
      (resource) => resource.status !== 'READY',
    ).length
    if (unresolvedResourceCount) {
      feedback.info(`草稿已导入，${unresolvedResourceCount} 个资源待修复。请在编辑页检查。`)
    } else {
      feedback.success('已导入为草稿，请检查标题与文章标识后保存、发布。')
    }
    await navigateTo(`${ADMIN_PATHS.blogs}/${importedBlogPost.id}`)
  } catch (error) {
    if (currentImportRevision === importRevision) {
      feedback.error(error)
    }
  } finally {
    if (currentImportRevision === importRevision) {
      isImporting.value = false
    }
  }
}
// 4. 卸载使迟到的响应失效并清空选择，不能以取消界面操作假装撤销已创建草稿。
onBeforeUnmount(() => {
  importRevision++
  importSelection.reset()
})
</script>

<template>
  <UCard class="blog-import-panel min-w-0" :aria-busy="isImporting" aria-label="文档导入面板">
    <template #header>
      <div class="flex flex-wrap items-center gap-2">
        <UIcon name="i-lucide-file-up" class="size-5 shrink-0 text-primary" aria-hidden="true" />
        <h2 class="font-semibold">导入文档</h2>
        <UBadge color="neutral" variant="subtle" class="ml-auto">Markdown</UBadge>
      </div>
      <p class="mt-2 text-sm leading-6 text-muted">选择正文，导入为草稿。检查后再发布。</p>
    </template>
    <div class="grid min-w-0 gap-5">
      <URadioGroup
        :model-value="importMode"
        :items="importModeOptions"
        orientation="horizontal"
        variant="card"
        :disabled="isImporting"
        legend="导入方式"
        :ui="{
          fieldset: 'flex flex-wrap gap-2',
          item: 'min-w-0 flex-1 basis-40',
          wrapper: 'min-w-0',
          label: 'whitespace-normal break-words',
        }"
        @update:model-value="changeMode"
      />
      <p class="text-xs leading-5 text-muted">
        切换方式会清空当前文件、附件和导入选项，不影响已保存草稿。导入过程中暂不可切换。
      </p>
      <div v-if="importMode === 'file'" :key="selectionRevision" class="grid min-w-0 gap-4">
        <UFileUpload
          v-model="markdownFile"
          :accept="MARKDOWN_EXTENSION"
          :disabled="isImporting"
          :file-image="false"
          :reset="true"
          layout="list"
          icon="i-lucide-file-text"
          label="选择 Markdown 正文"
          description="点击选择或拖入一个 UTF-8 .md 文件"
          :ui="fileUploadUi"
        >
          <template #file-name="{ file }"
            ><span :title="file.name">{{ file.name }}</span></template
          >
          <template #file-trailing="{ removeFile }">
            <UButton
              color="neutral"
              variant="ghost"
              icon="i-lucide-x"
              aria-label="移除正文"
              :disabled="isImporting"
              @click="removeFile()"
            />
          </template>
        </UFileUpload>
        <UCollapsible>
          <UButton
            color="neutral"
            variant="link"
            icon="i-lucide-paperclip"
            class="max-w-full whitespace-normal px-0 text-left"
            :disabled="isImporting"
            >添加本地图片或附件（可选）</UButton
          >
          <template #content>
            <div class="grid min-w-0 gap-3 pt-3">
              <p class="text-xs leading-5 text-muted">
                适合与正文放在同一层的文件；有子目录时，请使用文档文件夹。
              </p>
              <UFileUpload
                v-model="attachmentFiles"
                multiple
                :reset="true"
                :disabled="isImporting"
                :file-image="false"
                layout="list"
                label="选择图片或附件"
                description="可多选，文件名应与正文引用一致"
                :ui="fileUploadUi"
              >
                <template #file-name="{ file }"
                  ><span :title="file.name">{{ file.name }}</span></template
                >
                <template #file-trailing="{ index, removeFile }">
                  <UButton
                    color="neutral"
                    variant="ghost"
                    icon="i-lucide-x"
                    aria-label="移除附件"
                    :disabled="isImporting"
                    @click="removeFile(index)"
                  />
                </template>
              </UFileUpload>
            </div>
          </template>
        </UCollapsible>
      </div>
      <div v-else :key="selectionRevision" class="grid min-w-0 gap-4">
        <!-- Nuxt UI 4 的 FileUpload 不支持目录选择。原生目录输入只做文件选择，按钮和表单仍使用组件库。 -->
        <input
          ref="folderInput"
          type="file"
          webkitdirectory
          multiple
          hidden
          :disabled="isImporting"
          aria-label="选择文档文件夹"
          @change="selectFolder"
        />
        <div
          class="grid min-w-0 justify-items-center gap-3 rounded-lg border border-dashed border-default p-4 text-center"
        >
          <UIcon name="i-lucide-folder-open" class="size-8 text-muted" aria-hidden="true" />
          <p class="text-sm text-muted">选择包含正文和资源的文件夹</p>
          <p class="text-xs leading-5 text-muted">图片、附件应放在正文所在目录或其子目录。</p>
          <UButton
            color="neutral"
            variant="outline"
            :disabled="isImporting"
            @click="folderInput?.click()"
          >
            {{ selectedFolderFiles.length ? '重新选择文件夹' : '选择文档文件夹' }}
          </UButton>
        </div>
        <UFormField v-if="markdownCandidates.length > 1" label="选择要导入的正文" class="min-w-0">
          <USelect
            v-model="selectedBodyPath"
            :items="markdownCandidates"
            :disabled="isImporting"
            placeholder="请选择一个 Markdown 文件"
            class="w-full"
            :title="selectedBodyPath"
            :ui="{
              base: 'min-w-0',
              value: 'truncate min-w-0',
              content: 'w-[var(--reka-select-trigger-width)] max-w-[calc(100vw-2rem)]',
              itemLabel: 'whitespace-normal break-all',
            }"
          />
        </UFormField>
        <div v-if="selectedMarkdown" class="min-w-0 rounded-lg bg-elevated p-3">
          <p class="break-all text-sm font-medium">{{ selectedBodyPath }}</p>
          <p class="mt-1 break-words text-xs leading-5 text-muted">
            已识别正文 · {{ selectedResources.length }} 个候选资源，保留目录结构
          </p>
        </div>
      </div>
      <UCheckbox
        v-model="areRemoteRightsConfirmed"
        :disabled="isImporting"
        label="允许保存正文中的网络图片"
        description="仅当你拥有使用权时勾选；本地图片无需勾选。"
      />
    </div>
    <template #footer>
      <div class="grid min-w-0 gap-3">
        <p role="status" class="min-h-10 break-all text-xs leading-5 text-muted">
          {{
            selectionIssue ||
            (selectedMarkdown
              ? `已选择 ${selectedMarkdown.name} · ${selectedResources.length} 个候选资源`
              : '选择 Markdown 正文后即可导入。')
          }}
        </p>
        <UButton
          v-if="selectedMarkdown || selectedFolderFiles.length || attachmentFiles?.length"
          color="neutral"
          variant="ghost"
          size="sm"
          :disabled="isImporting"
          @click="clearSelection"
          >清空选择</UButton
        >
        <UButton
          block
          icon="i-lucide-arrow-right"
          :disabled="!canImport"
          :loading="isImporting"
          @click="importDocument"
        >
          {{ isImporting ? '正在导入文档…' : '导入为草稿' }}
        </UButton>
      </div>
    </template>
  </UCard>
</template>
