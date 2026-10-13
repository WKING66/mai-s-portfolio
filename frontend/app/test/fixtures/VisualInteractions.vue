<template>
  <!-- 仅由浏览器测试构建注册；通过真实 UApp 验证公共弹层，不进入业务路由。 -->
  <main class="site-container site-page">
    <h1 class="site-page-title">公共交互回归</h1>
    <UModal title="布局稳定性检查" description="保留官方滚动锁、焦点陷阱与焦点恢复。">
      <UButton class="mt-6">打开测试对话框</UButton>
      <template #body><UInput aria-label="弹窗输入" placeholder="使用键盘 Tab 与 Escape" class="w-full" /></template>
    </UModal>
    <div class="mt-8 min-h-[120vh] rounded-xl border border-default bg-default p-6">为传统滚动条保留可滚动的页面。</div>
  </main>
</template>
