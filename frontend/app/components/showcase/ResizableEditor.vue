<script setup lang="ts">
import { SplitterGroup, SplitterPanel, SplitterResizeHandle } from 'reka-ui'
const primary = useTemplateRef<InstanceType<typeof SplitterPanel>>('primary')
const view = ref('edit')
const tabs = [{ label: '编辑', value: 'edit' }, { label: '实时预览', value: 'preview' }]
</script>

<template>
  <div class="showcase-workspace" :data-mobile-view="view">
    <UTabs v-model="view" :items="tabs" :content="false" class="mb-4 lg:hidden" aria-label="移动端写作视图" />
    <div class="showcase-splitter-frame">
      <SplitterGroup direction="horizontal" :keyboard-resize-by="3" class="rounded-xl border border-default bg-default">
        <SplitterPanel ref="primary" :default-size="65" :min-size="45" :max-size="75" class="showcase-edit-panel min-w-0" data-editor-panel>
          <slot name="editor" />
        </SplitterPanel>
        <SplitterResizeHandle class="showcase-resize-handle" aria-label="调整编辑与预览宽度" @dblclick="primary?.resize(65)">
          <span class="h-10 w-1 rounded-full bg-accented" />
        </SplitterResizeHandle>
        <SplitterPanel :default-size="35" :min-size="25" class="showcase-preview-panel min-w-0" data-preview-panel>
          <slot name="preview" />
        </SplitterPanel>
      </SplitterGroup>
    </div>
    <p class="mt-3 hidden text-xs text-muted lg:block">拖动中间分隔条调整宽度；方向键微调，双击恢复默认。</p>
  </div>
</template>
