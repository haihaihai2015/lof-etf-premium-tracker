<template>
  <div class="backtest-page">
    <h2 class="page-title">套利回测</h2>

    <!-- Config Panel -->
    <section class="panel bt-config">
      <div class="bt-row">
        <div class="bt-field">
          <label>回测区间</label>
          <el-date-picker v-model="dateRange" type="daterange" range-separator="至"
            start-placeholder="开始日期" end-placeholder="结束日期" value-format="YYYY-MM-DD"
            :disabled-date="disabledDate" size="default" style="width:280px" />
        </div>
        <div class="bt-field">
          <label>初始资金</label>
          <el-input-number v-model="capital" :min="1000" :max="10000000" :step="10000" size="default" style="width:160px" />
          <span class="bt-unit">元</span>
        </div>
        <div class="bt-field">
          <label>单笔金额</label>
          <el-input-number v-model="perTradeAmount" :min="0" :max="1000000" :step="1000" size="default" style="width:160px" />
          <span class="bt-unit">元 (0=自动20%)</span>
        </div>
        <div class="bt-field">
          <label>策略</label>
          <el-radio-group v-model="strategy" size="default">
            <el-radio-button label="BOTH">双向</el-radio-button>
            <el-radio-button label="PREMIUM">溢价</el-radio-button>
            <el-radio-button label="DISCOUNT">折价</el-radio-button>
          </el-radio-group>
        </div>
      </div>

      <el-collapse v-model="expandedSections">
        <el-collapse-item title="溢价套利参数" name="premium" v-if="strategy !== 'DISCOUNT'">
          <div class="bt-params">
            <div class="bt-param"><span>溢价 ≥</span><el-input-number v-model="premiumConfig.devPct" :min="0" :max="50" :step="0.5" :precision="1" size="small" style="width:100px" /><span>%</span></div>
            <div class="bt-param"><span>限购 ≤</span><el-input-number v-model="premiumConfig.maxSubUnit" :min="0" :max="100000" :step="100" size="small" style="width:100px" /><span>元 (0=不限)</span></div>
            <div class="bt-param"><span>成交额 ≥</span><el-input-number v-model="premiumConfig.turnoverWan" :min="0" :max="99999" :step="100" size="small" style="width:100px" /><span>万</span></div>
            <div class="bt-param"><span>卖出滑点</span><el-input-number v-model="premiumConfig.slippagePct" :min="0" :max="5" :step="0.1" :precision="2" size="small" style="width:100px" /><span>%</span></div>
            <el-checkbox v-model="premiumConfig.excludeQDII" size="small">排除QDII</el-checkbox>
            <el-checkbox v-model="premiumConfig.requireSubscribe" size="small">需申购开放</el-checkbox>
          </div>
        </el-collapse-item>

        <el-collapse-item title="折价套利参数" name="discount" v-if="strategy !== 'PREMIUM'">
          <div class="bt-params">
            <div class="bt-param"><span>折价 ≥</span><el-input-number v-model="discountConfig.devPct" :min="0" :max="50" :step="0.5" :precision="1" size="small" style="width:100px" /><span>%</span></div>
            <div class="bt-param"><span>成交额 ≥</span><el-input-number v-model="discountConfig.turnoverWan" :min="0" :max="99999" :step="100" size="small" style="width:100px" /><span>万</span></div>
            <div class="bt-param"><span>持有 ≥</span><el-input-number v-model="discountConfig.minHoldDays" :min="1" :max="30" :step="1" size="small" style="width:100px" /><span>天</span></div>
            <el-checkbox v-model="discountConfig.indexOnly" size="small">仅指数型</el-checkbox>
            <el-checkbox v-model="discountConfig.excludeHighVol" size="small">排除高波动行业</el-checkbox>
            <el-checkbox v-model="discountConfig.requireRedeem" size="small">需赎回开放</el-checkbox>
          </div>
        </el-collapse-item>

        <el-collapse-item title="费率配置" name="fees">
          <div class="bt-params">
            <div class="bt-param"><span>申购费</span><el-input-number v-model="feesPct.subscribeRate" :min="0" :max="5" :step="0.01" :precision="3" size="small" style="width:100px" /><span>%</span></div>
            <div class="bt-param"><span>赎回费(≥7天)</span><el-input-number v-model="feesPct.redeemRate7d" :min="0" :max="5" :step="0.1" :precision="2" size="small" style="width:100px" /><span>%</span></div>
            <div class="bt-param"><span>赎回费(&lt;7天)</span><el-input-number v-model="feesPct.redeemRateShort" :min="0" :max="5" :step="0.1" :precision="2" size="small" style="width:100px" /><span>%</span></div>
            <div class="bt-param"><span>佣金</span><el-input-number v-model="feesPct.commission" :min="0" :max="1" :step="0.005" :precision="4" size="small" style="width:100px" /><span>%</span></div>
          </div>
        </el-collapse-item>
      </el-collapse>

      <div class="bt-actions">
        <el-button type="primary" :loading="running" :disabled="!dateRange || dateRange.length < 2" @click="doBacktest">
          开始回测
        </el-button>
        <el-button :loading="syncing" @click="doSync">
          {{ syncing ? syncMsg : '同步历史数据' }}
        </el-button>
        <span v-if="historyRange" class="bt-hint">
          K线: {{ historyRange.minDate || '--' }} ~ {{ historyRange.maxDate || '--' }} ({{ historyRange.totalBars }} 条)
          <template v-if="historyRange.premiumMinDate">
            | 溢价率: {{ historyRange.premiumMinDate }} ~ {{ historyRange.premiumMaxDate }} ({{ historyRange.premiumInstruments }} 只)
          </template>
          <template v-else>
            | <b style="color:#e6a23c">⚠ 无溢价率数据，请先同步历史数据</b>
          </template>
        </span>
      </div>
      <el-alert v-if="premiumRangeWarning" type="warning" :closable="false" style="margin-top:8px">
        {{ premiumRangeWarning }}
      </el-alert>
    </section>

    <!-- Sync Progress -->
    <el-alert v-if="syncing" type="info" :closable="false" style="margin-bottom:16px">
      <template #title>
        <span>{{ syncMsg }} ({{ syncProgress }}/{{ syncTotal }})</span>
      </template>
      <el-progress :percentage="syncTotal > 0 ? Math.round(syncProgress / syncTotal * 100) : 0" :stroke-width="6" />
    </el-alert>

    <!-- Results -->
    <template v-if="result">
      <el-alert v-if="result.message" type="warning" :closable="false" style="margin-bottom:16px" show-icon>
        {{ result.message }}
      </el-alert>
      <!-- Summary Cards -->
      <section class="panel bt-summary">
        <div class="summary-grid bt-cards">
          <div class="summary-card">
            <span>总收益</span>
            <strong :class="result.summary.totalReturn >= 0 ? 'value-up' : 'value-down'">
              {{ result.summary.totalReturn.toFixed(2) }} 元
            </strong>
            <small>{{ result.summary.totalReturnPct.toFixed(2) }}%</small>
          </div>
          <div class="summary-card">
            <span>胜率</span>
            <strong>{{ result.summary.winRate }}%</strong>
            <small>{{ result.summary.winTrades }}胜 / {{ result.summary.lossTrades }}负</small>
          </div>
          <div class="summary-card">
            <span>交易次数</span>
            <strong>{{ result.summary.totalTrades }}</strong>
            <small>平均持有 {{ result.summary.avgHoldDays }} 天</small>
          </div>
          <div class="summary-card">
            <span>最大回撤</span>
            <strong class="value-down">{{ result.summary.maxDrawdown.toFixed(2) }} 元</strong>
            <small>{{ result.summary.maxDrawdownPct.toFixed(2) }}%</small>
          </div>
          <div class="summary-card">
            <span>最大单笔盈利</span>
            <strong class="value-up">{{ result.summary.maxSingleProfit.toFixed(2) }}</strong>
          </div>
          <div class="summary-card">
            <span>最大单笔亏损</span>
            <strong class="value-down">{{ result.summary.maxSingleLoss.toFixed(2) }}</strong>
          </div>
          <div class="summary-card">
            <span>总费用</span>
            <strong>{{ result.summary.totalFees.toFixed(2) }} 元</strong>
          </div>
          <div class="summary-card">
            <span>资金利用率</span>
            <strong>{{ result.summary.capitalUtilization.toFixed(1) }}%</strong>
          </div>
        </div>
      </section>

      <!-- Equity Curve -->
      <section class="panel bt-chart-panel">
        <h3>权益曲线</h3>
        <div ref="chartRef" class="bt-chart"></div>
      </section>

      <!-- Trade Table -->
      <section class="panel bt-trades">
        <h3>交易明细 ({{ result.trades.length }} 笔)</h3>
        <el-table :data="pagedTrades" stripe size="small" max-height="500" style="width:100%"
          :default-sort="{ prop: 'openDate', order: 'descending' }" @sort-change="onTradeSort">
          <el-table-column prop="code" label="代码" width="80" sortable="custom" />
          <el-table-column prop="name" label="名称" width="140" show-overflow-tooltip />
          <el-table-column prop="direction" label="方向" width="70" sortable="custom">
            <template #default="{ row }">
              <el-tag :type="row.direction === 'PREMIUM' ? 'danger' : 'success'" size="small">
                {{ row.direction === 'PREMIUM' ? '溢价' : '折价' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="openDate" label="开仓日" width="110" sortable="custom" />
          <el-table-column prop="closeDate" label="平仓日" width="110" sortable="custom" />
          <el-table-column prop="entryPremium" label="入场偏离%" width="100" sortable="custom">
            <template #default="{ row }">
              <span :class="row.entryPremium > 0 ? 'value-up' : 'value-down'">{{ row.entryPremium.toFixed(2) }}%</span>
            </template>
          </el-table-column>
          <el-table-column prop="amount" label="投入金额" width="100" sortable="custom">
            <template #default="{ row }">{{ row.amount.toFixed(2) }}</template>
          </el-table-column>
          <el-table-column prop="profit" label="盈亏" width="100" sortable="custom">
            <template #default="{ row }">
              <span :class="row.profit >= 0 ? 'value-up' : 'value-down'">{{ row.profit.toFixed(2) }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="profitPct" label="收益率%" width="90" sortable="custom">
            <template #default="{ row }">
              <span :class="row.profitPct >= 0 ? 'value-up' : 'value-down'">{{ row.profitPct.toFixed(2) }}%</span>
            </template>
          </el-table-column>
          <el-table-column prop="fees" label="费用" width="80" sortable="custom">
            <template #default="{ row }">{{ row.fees.toFixed(2) }}</template>
          </el-table-column>
          <el-table-column prop="holdDays" label="持有天数" width="90" sortable="custom" />
        </el-table>
        <div class="bt-pagination" v-if="sortedTrades.length > tradePageSize">
          <el-pagination small layout="prev, pager, next" :total="sortedTrades.length"
            :page-size="tradePageSize" v-model:current-page="tradePage" />
        </div>
      </section>
    </template>
  </div>
</template>

<script setup>
import { ref, reactive, computed, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { ElMessage } from 'element-plus'
import * as echarts from 'echarts'
import { syncHistory, getSyncStatus, getHistoryRange, runBacktest } from '../services/api'

const BT_CONFIG_KEY = 'etf-backtest-config'

const DEFAULTS = {
  dateRange: ['2022-01-01', '2025-04-23'],
  capital: 100000,
  strategy: 'BOTH',
  perTradeAmount: 0,
  premiumConfig: { devPct: 5.0, maxSubUnit: 0, turnoverWan: 500, excludeQDII: true, requireSubscribe: true, slippagePct: 0.3 },
  discountConfig: { devPct: 3.0, turnoverWan: 500, minHoldDays: 7, indexOnly: true, excludeHighVol: true, requireRedeem: true },
  feesPct: { subscribeRate: 0.12, redeemRate7d: 0.5, redeemRateShort: 1.5, commission: 0.025 }
}

const CONFIG_VERSION = 3

function loadSavedConfig() {
  try {
    const raw = localStorage.getItem(BT_CONFIG_KEY)
    if (raw) {
      const parsed = JSON.parse(raw)
      if (parsed._v === CONFIG_VERSION) {
        return { ...DEFAULTS, ...parsed }
      }
      // Version mismatch: discard stale config
      localStorage.removeItem(BT_CONFIG_KEY)
    }
  } catch { /* ignore */ }
  return { ...DEFAULTS }
}

const saved = loadSavedConfig()
const dateRange = ref(saved.dateRange)
const capital = ref(saved.capital)
const perTradeAmount = ref(saved.perTradeAmount || 0)
const strategy = ref(saved.strategy)
const expandedSections = ref(['premium', 'discount'])

const premiumConfig = reactive({ ...DEFAULTS.premiumConfig, ...saved.premiumConfig })
const discountConfig = reactive({ ...DEFAULTS.discountConfig, ...saved.discountConfig })
const feesPct = reactive({ ...DEFAULTS.feesPct, ...saved.feesPct })

function saveConfig() {
  localStorage.setItem(BT_CONFIG_KEY, JSON.stringify({
    _v: CONFIG_VERSION,
    dateRange: dateRange.value,
    capital: capital.value,
    perTradeAmount: perTradeAmount.value,
    strategy: strategy.value,
    premiumConfig: { ...premiumConfig },
    discountConfig: { ...discountConfig },
    feesPct: { ...feesPct }
  }))
}

watch([dateRange, capital, perTradeAmount, strategy], saveConfig, { deep: true })
watch([premiumConfig, discountConfig, feesPct], saveConfig, { deep: true })

const running = ref(false)
const syncing = ref(false)
const syncMsg = ref('')
const syncProgress = ref(0)
const syncTotal = ref(0)
const historyRange = ref(null)
const result = ref(null)
const chartRef = ref(null)
let chartInstance = null
let syncPollTimer = null

// Trade table pagination & sorting
const tradePage = ref(1)
const tradePageSize = 50
const tradeSort = ref({ prop: 'openDate', order: 'descending' })

const sortedTrades = computed(() => {
  if (!result.value) return []
  const list = [...result.value.trades]
  const { prop, order } = tradeSort.value
  if (!prop) return list
  list.sort((a, b) => {
    let va = a[prop], vb = b[prop]
    if (typeof va === 'string') va = va.localeCompare ? va : String(va)
    if (typeof va === 'string') return order === 'ascending' ? va.localeCompare(vb) : vb.localeCompare(va)
    return order === 'ascending' ? va - vb : vb - va
  })
  return list
})

const pagedTrades = computed(() => {
  const start = (tradePage.value - 1) * tradePageSize
  return sortedTrades.value.slice(start, start + tradePageSize)
})

function onTradeSort({ prop, order }) {
  tradeSort.value = { prop: prop || 'openDate', order: order || 'descending' }
  tradePage.value = 1
}

function disabledDate(date) {
  if (!historyRange.value) return false
  const min = historyRange.value.minDate ? new Date(historyRange.value.minDate) : null
  const max = historyRange.value.maxDate ? new Date(historyRange.value.maxDate) : null
  if (min && date < min) return true
  if (max && date > max) return true
  return false
}

const premiumRangeWarning = computed(() => {
  if (!historyRange.value || !historyRange.value.premiumMinDate) return null
  const pm = historyRange.value.premiumMinDate
  const px = historyRange.value.premiumMaxDate
  const [s, e] = dateRange.value || []
  if (!s || !e) return null
  if (e < pm || s > px) {
    return `所选区间 ${s} ~ ${e} 不在溢价率数据范围 ${pm} ~ ${px} 内，回测将无结果。点击下方按钮自动调整。`
  }
  return null
})

async function loadRange() {
  try {
    historyRange.value = await getHistoryRange()
    // Auto-adjust date range to premium data range if current range has no overlap
    if (historyRange.value && historyRange.value.premiumMinDate) {
      const pm = historyRange.value.premiumMinDate
      const px = historyRange.value.premiumMaxDate
      const [s, e] = dateRange.value || []
      if (s && e && (e < pm || s > px)) {
        dateRange.value = [pm, px]
      }
    }
  } catch (e) {
    console.warn('Failed to load history range', e)
  }
}

async function doSync() {
  syncing.value = true
  syncMsg.value = '正在启动全量历史数据同步...'
  try {
    await syncHistory()
    pollSyncStatus()
  } catch (e) {
    ElMessage.error('启动同步失败: ' + e.message)
    syncing.value = false
  }
}

function pollSyncStatus() {
  if (syncPollTimer) clearInterval(syncPollTimer)
  syncPollTimer = setInterval(async () => {
    try {
      const st = await getSyncStatus()
      syncMsg.value = st.message || st.status
      syncProgress.value = st.progress || 0
      syncTotal.value = st.total || 0
      if (st.status === 'done' || st.status === 'error' || st.status === 'idle') {
        clearInterval(syncPollTimer)
        syncPollTimer = null
        syncing.value = false
        if (st.status === 'done') {
          ElMessage.success('历史数据同步完成')
        } else if (st.status === 'error') {
          ElMessage.error('同步出错: ' + st.message)
        }
        loadRange()
      }
    } catch (e) {
      console.warn('Poll sync status failed', e)
    }
  }, 2000)
}

async function doBacktest() {
  if (!dateRange.value || dateRange.value.length < 2) return
  running.value = true
  result.value = null
  try {
    const params = {
      startDate: dateRange.value[0],
      endDate: dateRange.value[1],
      capital: capital.value,
      codes: [],
      strategy: strategy.value,
      perTradeAmount: perTradeAmount.value,
      premiumConfig: strategy.value !== 'DISCOUNT' ? { ...premiumConfig } : null,
      discountConfig: strategy.value !== 'PREMIUM' ? { ...discountConfig } : null,
      feeConfig: {
        subscribeRate: feesPct.subscribeRate / 100,
        redeemRate7d: feesPct.redeemRate7d / 100,
        redeemRateShort: feesPct.redeemRateShort / 100,
        commission: feesPct.commission / 100
      }
    }
    result.value = await runBacktest(params)
    tradePage.value = 1
    await nextTick()
    renderChart()
  } catch (e) {
    ElMessage.error('回测失败: ' + (e.response?.data?.message || e.message))
  } finally {
    running.value = false
  }
}

function renderChart() {
  if (!chartRef.value || !result.value) return
  if (chartInstance) chartInstance.dispose()
  chartInstance = echarts.init(chartRef.value)

  const dates = result.value.equityCurve.map(p => p.date)
  const values = result.value.equityCurve.map(p => p.equity)

  chartInstance.setOption({
    tooltip: {
      trigger: 'axis',
      formatter: params => {
        const p = params[0]
        return `${p.axisValue}<br/>权益: <b>${p.value.toFixed(2)}</b> 元`
      }
    },
    grid: { left: 60, right: 20, top: 20, bottom: 40 },
    xAxis: {
      type: 'category',
      data: dates,
      axisLabel: { fontSize: 11, rotate: 30 }
    },
    yAxis: {
      type: 'value',
      axisLabel: { formatter: v => (v / 10000).toFixed(1) + '万' }
    },
    series: [{
      type: 'line',
      data: values,
      smooth: true,
      lineStyle: { width: 2 },
      areaStyle: {
        color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
          { offset: 0, color: 'rgba(59,130,246,0.3)' },
          { offset: 1, color: 'rgba(59,130,246,0.02)' }
        ])
      },
      itemStyle: { color: '#3b82f6' },
      symbol: 'none'
    }],
    dataZoom: [{ type: 'inside' }, { type: 'slider', height: 20 }]
  })
}

function handleResize() {
  chartInstance?.resize()
}

onMounted(() => {
  loadRange()
  // Check if sync is already running
  getSyncStatus().then(st => {
    if (st.status === 'running') {
      syncing.value = true
      syncMsg.value = st.message
      syncProgress.value = st.progress || 0
      syncTotal.value = st.total || 0
      pollSyncStatus()
    }
  }).catch(() => {})
  window.addEventListener('resize', handleResize)
})

onBeforeUnmount(() => {
  if (syncPollTimer) clearInterval(syncPollTimer)
  if (chartInstance) chartInstance.dispose()
  window.removeEventListener('resize', handleResize)
})
</script>

<style scoped>
.backtest-page {
  max-width: 1200px;
  margin: 0 auto;
  padding: 16px;
}
.page-title {
  font-size: 22px;
  font-weight: 700;
  margin-bottom: 16px;
  color: #17324d;
}
.bt-config {
  margin-bottom: 16px;
}
.bt-row {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  align-items: flex-end;
  margin-bottom: 12px;
}
.bt-field {
  display: flex;
  align-items: center;
  gap: 6px;
}
.bt-field label {
  font-size: 13px;
  color: #475569;
  white-space: nowrap;
}
.bt-unit {
  font-size: 13px;
  color: #68809a;
}
.bt-params {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: center;
}
.bt-param {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 13px;
  color: #475569;
}
.bt-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 12px;
}
.bt-hint {
  font-size: 12px;
  color: #94a3b8;
}
.bt-summary {
  margin-bottom: 16px;
}
.bt-cards {
  grid-template-columns: repeat(4, 1fr) !important;
}
.bt-cards .summary-card small {
  display: block;
  margin-top: 4px;
  font-size: 12px;
  color: #68809a;
}
.bt-chart-panel {
  margin-bottom: 16px;
}
.bt-chart-panel h3,
.bt-trades h3 {
  font-size: 16px;
  font-weight: 600;
  margin-bottom: 12px;
  color: #17324d;
}
.bt-chart {
  width: 100%;
  height: 360px;
}
.bt-trades {
  margin-bottom: 16px;
}
.bt-pagination {
  margin-top: 12px;
  display: flex;
  justify-content: center;
}

@media (max-width: 768px) {
  .bt-row {
    flex-direction: column;
    gap: 10px;
  }
  .bt-cards {
    grid-template-columns: repeat(2, 1fr) !important;
  }
  .bt-chart {
    height: 260px;
  }
}

/* Dark mode overrides */
:global(html.dark) .page-title {
  color: #e2e8f0;
}
:global(html.dark) .bt-field label {
  color: #94a3b8;
}
:global(html.dark) .bt-unit {
  color: #64748b;
}
:global(html.dark) .bt-param {
  color: #94a3b8;
}
:global(html.dark) .bt-chart-panel h3,
:global(html.dark) .bt-trades h3 {
  color: #e2e8f0;
}
:global(html.dark) .bt-cards .summary-card small {
  color: #64748b;
}
</style>
