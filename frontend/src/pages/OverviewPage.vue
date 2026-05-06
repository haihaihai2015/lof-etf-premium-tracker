<template>
  <div class="stack compact-stack">
    <FilterToolbar
      v-if="metaReady"
      :active-type="activeType"
      :filters="filters"
      :defaults="defaultFilters"
      :arb-config="arbConfig"
      :refreshing="refreshing"
      :syncing="syncing"
      :total="total"
      :market-phase="marketPhase"
      :countdown="countdown"
      @apply="applyFilters"
      @refresh="handleRefresh"
      @type-change="handleTabChange"
      @update-arb-config="updateArbConfig"
    />

    <div v-if="rows.length" class="summary-grid summary-compact">
      <div class="summary-card">
        <span>溢价标的</span>
        <strong class="value-up">{{ stats.premiumCount }}</strong>
      </div>
      <div class="summary-card">
        <span>折价标的</span>
        <strong class="value-down">{{ stats.discountCount }}</strong>
      </div>
      <div class="summary-card summary-card-clickable" @click="toggleArbFilter">
        <span>套利机会 {{ arbFilterActive ? '✓' : '' }}</span>
        <strong style="color:#f59e0b">{{ stats.arbCount }}<span v-if="stats.arbCount" style="font-size:11px;font-weight:400;color:#68809a"> (溢{{ stats.premiumArbCount }}/折{{ stats.discountArbCount }})</span></strong>
      </div>
    </div>

    <MarketTable
      :rows="displayRows"
      :active-type="activeType"
      :favorites="favorites"
      :sparklines="sparklineData"
      :arb-config="arbConfig"
      :market-phase="marketPhase"
      :total="arbFilterActive ? displayRows.length : total"
      :page="filters.page"
      :page-size="filters.pageSize"
      @sort-change="handleSortChange"
      @page-change="handlePageChange"
      @page-size-change="handlePageSizeChange"
      @toggle-favorite="toggleFavorite"
      @compare="openCompare"
    />

    <el-drawer v-model="compareVisible" :title="`同类对比: ${compareIndex}`" size="680px" direction="rtl">
      <el-table :data="comparePeers" stripe border table-layout="auto" max-height="70vh" style="font-size:13px">
        <el-table-column prop="code" label="代码" width="90">
          <template #default="{ row }">
            <RouterLink :to="`/detail/${row.code}`" class="code-link">{{ row.code }}</RouterLink>
          </template>
        </el-table-column>
        <el-table-column prop="name" label="名称" min-width="120" />
        <el-table-column label="偏离" min-width="80">
          <template #default="{ row }">
            <span :class="peerDevClass(row)">{{ peerDevText(row) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="成交额" min-width="80">
          <template #default="{ row }">{{ formatTurnover(row.turnover) }}</template>
        </el-table-column>
        <el-table-column label="申购" width="55">
          <template #default="{ row }">
            <span :class="row.canSubscribe ? 'status-open' : 'status-closed'">{{ row.canSubscribe ? '开放' : '关闭' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="赎回" width="55">
          <template #default="{ row }">
            <span :class="row.canRedeem ? 'status-open' : 'status-closed'">{{ row.canRedeem ? '开放' : '关闭' }}</span>
          </template>
        </el-table-column>
      </el-table>
    </el-drawer>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import FilterToolbar from '../components/FilterToolbar.vue'
import MarketTable from '../components/MarketTable.vue'
import { fetchInstruments, fetchMeta, fetchSparklines, fetchStats, triggerRefresh } from '../services/api'
import { formatTurnover } from '../utils/formatters'

const FAVORITES_KEY = 'etf-monitor-favorites'

const globalStats = ref({ premiumCount: 0, discountCount: 0, total: 0 })

const localArbStats = computed(() => {
  let premiumArbCount = 0, discountArbCount = 0
  for (const row of rows.value) {
    if (isPremiumArb(row)) premiumArbCount++
    if (isDiscountArb(row)) discountArbCount++
  }
  return { premiumArbCount, discountArbCount, arbCount: premiumArbCount + discountArbCount }
})

const stats = computed(() => ({
  ...globalStats.value,
  ...localArbStats.value
}))

const activeType = ref('LOF')
const rows = ref([])
const total = ref(0)
const metaReady = ref(false)
const marketPhase = ref('--')
const refreshing = ref(false)
const syncing = ref(false)
const favorites = ref(loadFavorites())
const countdown = ref(0)
const sparklineData = ref({})
const arbFilterActive = ref(false)
const ARB_CONFIG_KEY = 'etf-monitor-arb-config'
const arbConfig = reactive(loadArbConfig())
const compareVisible = ref(false)
const compareIndex = ref('')
const comparePeers = ref([])
let pollTimer
let countdownTimer

const HIGH_VOL_KEYWORDS = ['半导体', '芯片', '白酒', '军工', '新能源', '光伏', '锂电', '有色', '煤炭', '钢铁', '证券', '券商']

function defaultArbConfig() {
  return {
    premium: { enabled: true, devPct: 5, maxSubUnit: 100, turnoverWan: 500, positionRatio: 0.3, trendNotDown: true, excludeQDII: true, requireSubscribe: true },
    discount: { enabled: true, devPct: 3, turnoverWan: 500, minHoldDays: 7, indexOnly: true, excludeHighVol: true, requireRedeem: true }
  }
}

function loadArbConfig() {
  try {
    const raw = localStorage.getItem(ARB_CONFIG_KEY)
    if (raw) {
      const parsed = JSON.parse(raw)
      if (parsed.premium && parsed.discount) return parsed
    }
  } catch {}
  return defaultArbConfig()
}

function updateArbConfig(cfg) {
  Object.assign(arbConfig.premium, cfg.premium)
  Object.assign(arbConfig.discount, cfg.discount)
  localStorage.setItem(ARB_CONFIG_KEY, JSON.stringify(arbConfig))
}

function isQDII(row) {
  const ft = (row.fundType || '') + (row.name || '')
  return ft.includes('QDII') || ft.includes('海外') || ft.includes('美元') || ft.includes('美国')
}

function isHighVolSector(row) {
  const txt = (row.name || '') + (row.indexName || '')
  return HIGH_VOL_KEYWORDS.some(kw => txt.includes(kw))
}

function isTrendDown(row) {
  const pts = sparklineData.value[row.code]
  if (!pts || pts.length < 3) return false
  const last3 = pts.slice(-3)
  return last3[2] < last3[1] && last3[1] < last3[0]
}

function isPremiumArb(row) {
  const cfg = arbConfig.premium
  if (!cfg.enabled) return false
  const dev = row.premiumRate ?? row.navRefPremiumRate
  if (dev == null || dev <= 0) return false
  if (dev * 100 < cfg.devPct) return false
  if (cfg.maxSubUnit > 0 && row.minimumSubscriptionUnit && row.minimumSubscriptionUnit > cfg.maxSubUnit) return false
  if (row.turnover < cfg.turnoverWan * 10000) return false
  if (cfg.requireSubscribe && !row.canSubscribe) return false
  if (cfg.excludeQDII && isQDII(row)) return false
  if (cfg.trendNotDown && isTrendDown(row)) return false
  return true
}

function isDiscountArb(row) {
  const cfg = arbConfig.discount
  if (!cfg.enabled) return false
  const dev = row.premiumRate ?? row.navRefPremiumRate
  if (dev == null || dev >= 0) return false
  if (Math.abs(dev) * 100 < cfg.devPct) return false
  if (row.turnover < cfg.turnoverWan * 10000) return false
  if (cfg.requireRedeem && !row.canRedeem) return false
  if (cfg.indexOnly && !row.indexName) return false
  if (cfg.excludeHighVol && isHighVolSector(row)) return false
  return true
}

function isArbRow(row) {
  return isPremiumArb(row) || isDiscountArb(row)
}

const displayRows = computed(() => {
  if (!arbFilterActive.value) return rows.value
  return rows.value.filter(isArbRow)
})

function toggleArbFilter() {
  arbFilterActive.value = !arbFilterActive.value
}

function loadFavorites() {
  try {
    const raw = localStorage.getItem(FAVORITES_KEY)
    return raw ? new Set(JSON.parse(raw)) : new Set()
  } catch {
    return new Set()
  }
}

function saveFavorites() {
  localStorage.setItem(FAVORITES_KEY, JSON.stringify([...favorites.value]))
}

function toggleFavorite(code) {
  const next = new Set(favorites.value)
  if (next.has(code)) {
    next.delete(code)
  } else {
    next.add(code)
  }
  favorites.value = next
  saveFavorites()
  if (filters.favoritesOnly) {
    loadRows()
  }
}

const defaultFilters = reactive({
  keyword: '',
  sortBy: 'premium',
  sortOrder: 'desc',
  premiumThreshold: 0,
  subscribableOnly: false,
  purchasableOnly: false,
  highLiquidityOnly: false,
  favoritesOnly: false,
  page: 1,
  pageSize: 20
})

const filters = reactive({ ...defaultFilters })

async function loadMeta() {
  const meta = await fetchMeta()
  defaultFilters.keyword = meta.defaults.keyword ?? ''
  defaultFilters.pageSize = meta.defaults.pageSize ?? 20
  Object.assign(filters, defaultFilters)
  metaReady.value = true
}

async function loadRows() {
  if (document.hidden) {
    return
  }
  try {
    const params = {
      type: activeType.value,
      keyword: filters.keyword || undefined,
      sortBy: filters.sortBy,
      sortOrder: filters.sortOrder,
      premiumMin: filters.premiumThreshold > 0 ? filters.premiumThreshold / 100 : undefined,
      subscribableOnly: filters.subscribableOnly,
      purchasableOnly: filters.purchasableOnly,
      highLiquidityOnly: filters.highLiquidityOnly,
      page: filters.page,
      pageSize: filters.pageSize
    }
    if (filters.favoritesOnly && favorites.value.size > 0) {
      params.codes = [...favorites.value].join(',')
    }
    const [data, statsData] = await Promise.all([
      fetchInstruments(params),
      fetchStats(activeType.value)
    ])
    rows.value = data.items
    total.value = data.total
    globalStats.value = statsData
    loadSparklines(data.items)
    checkAlerts(data.items)
    const phase = data.items[0]?.marketPhase || '--'
    if (marketPhase.value !== phase) {
      marketPhase.value = phase
      resetPollInterval()
    }
  } catch (e) {
    console.warn('加载数据失败', e)
  }
}

const alertedCodes = new Set()

function checkAlerts(items) {
  if (Notification.permission === 'default') {
    Notification.requestPermission()
  }
  if (Notification.permission !== 'granted') return
  for (const row of items) {
    const dev = row.premiumRate ?? row.navRefPremiumRate
    if (dev == null) continue
    const arb = isArbRow(row)
    if (arb && !alertedCodes.has(row.code)) {
      alertedCodes.add(row.code)
      new Notification(`套利机会: ${row.name}`, {
        body: `${row.code} 偏离 ${(dev * 100).toFixed(2)}% | 成交额 ${(row.turnover / 10000).toFixed(0)}万`,
        tag: row.code
      })
    }
    if (!arb) {
      alertedCodes.delete(row.code)
    }
  }
}

async function loadSparklines(items) {
  if (!items || items.length === 0) return
  try {
    const codes = items.map(r => r.code)
    sparklineData.value = await fetchSparklines(codes)
  } catch (e) {
    console.warn('加载走势数据失败', e)
  }
}

async function openCompare(indexName) {
  compareIndex.value = indexName
  compareVisible.value = true
  try {
    const data = await fetchInstruments({ keyword: indexName, pageSize: 50 })
    comparePeers.value = data.items.filter(r => r.indexName === indexName)
  } catch (e) {
    console.warn('加载同类基金失败', e)
    comparePeers.value = []
  }
}

function peerDevText(row) {
  const dev = row.premiumRate ?? row.navRefPremiumRate
  return dev != null ? (dev * 100).toFixed(2) + '%' : '--'
}

function peerDevClass(row) {
  const dev = row.premiumRate ?? row.navRefPremiumRate
  if (dev == null) return ''
  return dev > 0 ? 'value-up' : dev < 0 ? 'value-down' : ''
}

function applyFilters(nextFilters) {
  Object.assign(filters, nextFilters)
  filters.page = 1
  loadRows()
}

function handleTabChange(type) {
  activeType.value = type
  Object.assign(filters, defaultFilters)
  filters.page = 1
  loadRows()
}

function handleSortChange({ prop, order }) {
  const map = {
    code: 'code',
    name: 'name',
    price: 'price',
    changeRate: 'changeRate',
    deviationSortValue: 'premium',
    spreadRate: 'spread',
    turnover: 'turnover'
  }
  if (!order) {
    filters.sortOrder = filters.sortOrder === 'desc' ? 'asc' : 'desc'
  } else {
    filters.sortBy = map[prop] || 'premium'
    filters.sortOrder = order === 'ascending' ? 'asc' : 'desc'
  }
  filters.page = 1
  loadRows()
}

function handlePageChange(value) {
  filters.page = value
  loadRows()
}

function handlePageSizeChange(value) {
  filters.pageSize = value
  filters.page = 1
  loadRows()
}

async function handleRefresh(scope) {
  const loading = scope === 'all' ? syncing : refreshing
  loading.value = true
  try {
    const result = await triggerRefresh(scope)
    ElMessage.success(result.message)
    setTimeout(() => loadRows(), 2000)
  } catch (error) {
    ElMessage.error('刷新失败，请稍后重试')
  } finally {
    loading.value = false
  }
}

function resetPollInterval() {
  if (pollTimer) window.clearInterval(pollTimer)
  if (countdownTimer) window.clearInterval(countdownTimer)
  const isTrading = ['continuous_trading', 'call_auction'].includes(marketPhase.value)
  const intervalSec = isTrading ? 60 : 300
  countdown.value = intervalSec
  countdownTimer = window.setInterval(() => {
    countdown.value = Math.max(0, countdown.value - 1)
  }, 1000)
  pollTimer = window.setInterval(() => {
    loadRows()
    countdown.value = intervalSec
  }, intervalSec * 1000)
}

onMounted(async () => {
  await loadMeta()
  await loadRows()
  resetPollInterval()
})

onBeforeUnmount(() => {
  if (pollTimer) window.clearInterval(pollTimer)
  if (countdownTimer) window.clearInterval(countdownTimer)
})
</script>
