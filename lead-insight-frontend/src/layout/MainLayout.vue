<script setup lang="ts">
/** 主框架：左侧 Sidebar + 顶部 Topbar + 内容区（业务页均在此 Layout 内） */
import AppSidebar from '@/layout/components/AppSidebar.vue';
import AppHeader from '@/layout/components/AppHeader.vue';
</script>

<template>
  <el-container class="main-layout">
    <el-aside class="main-layout__aside" width="200px">
      <AppSidebar />
    </el-aside>
    <el-container class="main-layout__body">
      <el-header class="main-layout__header" height="56px">
        <AppHeader />
      </el-header>
      <el-main class="main-layout__main">
        <div class="main-layout__content">
          <router-view />
        </div>
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.main-layout {
  height: 100%;
  background: var(--bg);
}

.main-layout__aside {
  background: var(--surface);
  border-right: 1px solid var(--border);
}

.main-layout__body {
  min-width: 0;
}

.main-layout__header {
  display: flex;
  align-items: center;
  padding: 0 var(--content-gutter);
  background: var(--surface);
  border-bottom: 1px solid var(--border);
  z-index: var(--z-sticky);
}

.main-layout__main {
  padding: 0;
  overflow-y: auto;
  background: var(--bg);
}

.main-layout__content {
  max-width: var(--container-max);
  margin: 0 auto;
  padding: var(--space-6) var(--content-gutter) var(--space-10);
}
</style>
