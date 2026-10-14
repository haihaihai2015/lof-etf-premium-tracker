<template>
  <div class="shell" :class="{ dark: isDark }">
    <header class="topbar">
      <div style="display:flex;align-items:center;gap:20px">
        <div>
          <p class="eyebrow">Internal Research Dashboard</p>
          <h1>ETF / LOF 折溢价监控看板</h1>
        </div>
        <nav class="topbar-nav">
          <router-link to="/" class="nav-link" :class="{ active: $route.path === '/' }">监控</router-link>
          <router-link to="/backtest" class="nav-link" :class="{ active: $route.path === '/backtest' }">回测</router-link>
        </nav>
      </div>
      <div style="display:flex;align-items:center;gap:16px">
        <p class="hint">
          ETF 的盘中偏离仅供研究观察，最终净值、申赎成本和流动性会影响实际交易结果。
        </p>
        <el-switch v-model="isDark" active-text="🌙" inactive-text="☀️" @change="persistTheme" />
      </div>
    </header>
    <main class="content">
      <RouterView />
    </main>
  </div>
</template>

<script setup>
import { ref } from 'vue'

const THEME_KEY = 'etf-monitor-theme'
const isDark = ref(localStorage.getItem(THEME_KEY) === 'dark')
if (isDark.value) document.documentElement.classList.add('dark')

function persistTheme(val) {
  localStorage.setItem(THEME_KEY, val ? 'dark' : 'light')
  document.documentElement.classList.toggle('dark', val)
}
</script>
