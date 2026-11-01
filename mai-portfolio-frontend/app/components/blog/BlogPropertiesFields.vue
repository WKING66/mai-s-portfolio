<script setup lang="ts">
import type { BlogTag } from '../../api/blogs'
import type { BlogProperties } from '../../utils/blog/properties'
import { BLOG_INPUT_LIMITS } from '../../constants/blogs'
const fields = defineModel<BlogProperties>({ required: true })
defineProps<{ tags: BlogTag[]; slugLocked?: boolean; disabled?: boolean }>()
</script>
<template>
  <fieldset :disabled="disabled" class="grid gap-5">
    <UFormField
      name="title"
      label="文章标题"
      :hint="fields.title.length + '/' + BLOG_INPUT_LIMITS.title"
    >
      <UInput
        :model-value="fields.title"
        :maxlength="BLOG_INPUT_LIMITS.title"
        class="w-full"
        @update:model-value="fields = { ...fields, title: $event }"
      />
    </UFormField>
    <UFormField
      name="slug"
      label="文章地址（Slug）"
      :help="
        slugLocked
          ? '文章已有正式版本，地址不可修改。'
          : '使用小写字母、数字和短横线，不随标题自动改变。'
      "
    >
      <UInput
        :model-value="fields.slug"
        :readonly="slugLocked"
        :maxlength="BLOG_INPUT_LIMITS.slug"
        class="w-full"
        @update:model-value="fields = { ...fields, slug: $event }"
      />
    </UFormField>
    <UFormField
      name="summary"
      label="文章摘要"
      :hint="fields.summary.length + '/' + BLOG_INPUT_LIMITS.summary"
    >
      <UTextarea
        :model-value="fields.summary"
        :maxlength="BLOG_INPUT_LIMITS.summary"
        :rows="4"
        class="w-full"
        @update:model-value="fields = { ...fields, summary: $event }"
      />
    </UFormField>
    <UFormField name="tagIds" label="文章标签">
      <USelectMenu
        :model-value="fields.tagIds"
        multiple
        value-key="value"
        :items="tags.map((tag) => ({ label: tag.name, value: tag.id }))"
        class="w-full"
        placeholder="选择已有标签"
        :search-input="{ placeholder: '搜索标签…' }"
        @update:model-value="fields = { ...fields, tagIds: $event }"
      />
    </UFormField>
    <USwitch
      :model-value="fields.allowDownload"
      label="允许下载 Markdown"
      description="保存属性后生效。"
      @update:model-value="fields = { ...fields, allowDownload: $event }"
    />
  </fieldset>
</template>
