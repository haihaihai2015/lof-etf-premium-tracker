<template>
  <section class="panel">
    <div class="table-head">
      <h2>{{ title }}</h2>
      <div class="meta-line">
        <span>{{ rows[0]?.source || '--' }}</span>
        <span>{{ formatDateTime(rows[0]?.quoteTs) }}</span>
      </div>
    </div>

    <!-- Mobile card list -->
    <div class="mobile-card-list">
      <div
        v-for="row in rows"
        :key="row.code"
        class="m-card"
        :class="{ 'arb-card': isArbitrageOpportunity(row) }"
      >
        <div class="m-card-top">
          <div class="m-card-title">
            <el-icon
              :class="['fav-star', favorites.has(row.code) ? 'fav-active' : '']"
              @click.stop="$emit('toggle-favorite', row.code)"
              style="font-size:14px"
            >
              <StarFilled v-if="favorites.has(row.code)" />
              <Star v-else />
            </el-icon>
            <RouterLink :to="`/detail/${row.code}`" class="code-link" style="font-size:14px">{{ row.code }}</RouterLink>
            <span class="m-card-name">{{ row.name }}</span>
          </div>
          <el-tag :type="signalTagType(row.signalLevel)" effect="dark" size="small">
            {{ signalLabel(row.signalLevel) }}
          </el-tag>
        </div>
        <div class="m-card-grid">
          <div class="m-metric">
            <span class="m-label">现价</span>
            <span class="m-val">{{ formatNumber(row.price) }}</span>
          </div>
          <div class="m-metric">
            <span class="m-label">涨跌</span>
            <span class="m-val" :class="valueClass(row.changeRate)">{{ formatPercent(row.changeRate) }}</span>
          </div>
          <div v-if="showDeviation" class="m-metric">
            <span class="m-label">偏离</span>
            <span class="m-val" :class="deviationClass(deviationValue(row))">{{ formatPercent(deviationValue(row)) }}</span>
          </div>
          <div class="m-metric">
            <span class="m-label">成交额</span>
            <span class="m-val">{{ formatTurnover(row.turnover) }}</span>
          </div>
          <div v-if="showValuation" class="m-metric">
            <span class="m-label">净值</span>
            <span class="m-val">{{ formatNumber(row.valuationValue) }}</span>
          </div>
          <div class="m-metric">
            <span class="m-label">到账</span>
            <span class="m-val">{{ settlementText(row) }}</span>
          </div>
        </div>
        <div class="m-card-footer">
          <span :class="row.canSubscribe ? 'status-open' : 'status-closed'">申购{{ row.canSubscribe ? '开' : '关' }}</span>
          <span :class="row.canRedeem ? 'status-open' : 'status-closed'">赎回{{ row.canRedeem ? '开' : '关' }}</span>
          <span v-if="activeType === 'ETF' && row.spreadRate != null" style="color:#68809a">点差 {{ formatPercent(row.spreadRate) }}</span>
          <span v-if="row.indexName" class="index-link" @click.stop="$emit('compare', row.indexName)">{{ row.indexName }}</span>
        </div>
      </div>
    </div>

    <!-- Desktop table -->
    <el-table
      ref="tableRef"
      :data="rows"
      stripe
      border
      table-layout="auto"
      height="calc(100vh - 200px)"
      class="market-table"
      :default-sort="{ prop: 'deviationSortValue', order: 'descending' }"
      :row-class-name="rowClassName"
      @sort-change="handleSortChange"
    >
      <el-table-column fixed="left" prop="code" label="代码" width="110" sortable="custom">
        <template #default="{ row }">
          <el-icon
            :class="['fav-star', favorites.has(row.code) ? 'fav-active' : '']"
            @click.stop="$emit('toggle-favorite', row.code)"
          >
            <StarFilled v-if="favorites.has(row.code)" />
            <Star v-else />
          </el-icon>
          <RouterLink :to="`/detail/${row.code}`" class="code-link">{{ row.code }}</RouterLink>
        </template>
      </el-table-column>
      <el-table-column fixed="left" prop="name" label="名称" min-width="140" sortable="custom">
        <template #default="{ row }">
          <div>{{ row.name }}</div>
          <span v-if="row.indexName" class="index-link" @click.stop="$emit('compare', row.indexName)">{{ row.indexName }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="price" label="现价" min-width="80" sortable="custom">
        <template #default="{ row }">{{ formatNumber(row.price) }}</template>
      </el-table-column>
      <el-table-column prop="changeRate" label="涨跌" min-width="80" sortable="custom">
        <template #default="{ row }">
          <span :class="valueClass(row.changeRate)">{{ formatPercent(row.changeRate) }}</span>
        </template>
      </el-table-column>
      <el-table-column v-if="showValuation" prop="valuationValue" :label="valuationColumnLabel" min-width="90">
        <template #default="{ row }">{{ formatNumber(row.valuationValue) }}</template>
      </el-table-column>
      <el-table-column v-if="showDeviation" prop="deviationSortValue" :label="deviationColumnLabel" min-width="110" sortable="custom">
        <template #default="{ row }">
          <el-popover v-if="sparklines[row.code] && sparklines[row.code].length > 1" placement="top" :width="280" trigger="hover" :show-after="300">
            <template #reference>
              <span :class="deviationClass(deviationValue(row))" style="cursor:help;border-bottom:1px dashed currentColor">
                {{ formatPercent(deviationValue(row)) }}
              </span>
            </template>
            <div style="text-align:center">
              <div style="font-size:12px;color:#68809a;margin-bottom:4px">近期溢价率走势</div>
              <svg width="260" height="80">
                <polyline :points="sparkPointsLarge(sparklines[row.code])" fill="none" :stroke="sparkColor(sparklines[row.code])" stroke-width="1.5" />
                <line x1="10" :y1="sparkZeroY(sparklines[row.code])" x2="250" :y2="sparkZeroY(sparklines[row.code])" stroke="#cbd5e1" stroke-width="0.5" stroke-dasharray="3,2" />
              </svg>
              <div style="font-size:11px;color:#94a3b8">{{ sparklines[row.code].length }} 个数据点</div>
            </div>
          </el-popover>
          <span v-else :class="deviationClass(deviationValue(row))">{{ formatPercent(deviationValue(row)) }}</span>
        </template>
      </el-table-column>
      <el-table-column v-if="activeType === 'ETF'" prop="spreadRate" label="点差" min-width="80" sortable="custom">
        <template #default="{ row }">{{ formatPercent(row.spreadRate) }}</template>
      </el-table-column>
      <el-table-column v-if="activeType === 'LOF'" prop="valuationDate" label="净值日期" min-width="100" />
      <el-table-column prop="turnover" label="成交额" min-width="90" sortable="custom">
        <template #default="{ row }">{{ formatTurnover(row.turnover) }}</template>
      </el-table-column>
      <el-table-column prop="dailyPurchaseLimit" label="日限额" min-width="80" sortable="custom">
        <template #default="{ row }">
          <span v-if="row.dailyPurchaseLimit === -1" class="status-open">不限购</span>
          <span v-else-if="row.dailyPurchaseLimit === 0" class="status-closed">暂停</span>
          <span v-else-if="row.dailyPurchaseLimit != null && row.dailyPurchaseLimit > 0">{{ formatPurchaseLimit(row.dailyPurchaseLimit) }}</span>
          <span v-else>--</span>
        </template>
      </el-table-column>
      <el-table-column label="到账" width="55">
        <template #default="{ row }">
          <el-tooltip :content="settlementTip(row)" placement="top">
            <span>{{ settlementText(row) }}</span>
          </el-tooltip>
        </template>
      </el-table-column>
      <el-table-column prop="canSubscribe" label="申购" width="55">
        <template #default="{ row }">
          <span :class="row.canSubscribe ? 'status-open' : 'status-closed'">{{ row.canSubscribe ? '开放' : '关闭' }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="canRedeem" label="赎回" width="55">
        <template #default="{ row }">
          <span :class="row.canRedeem ? 'status-open' : 'status-closed'">{{ row.canRedeem ? '开放' : '关闭' }}</span>
        </template>
      </el-table-column>
      <el-table-column fixed="right" prop="signalLevel" label="信号" width="90">
        <template #default="{ row }">
          <el-tooltip
            v-if="row.signalReason"
            :content="row.signalReason"
            placement="left"
            :show-after="300"
          >
            <el-tag :type="signalTagType(row.signalLevel)" effect="dark" style="cursor:help">
              {{ signalLabel(row.signalLevel) }}
            </el-tag>
          </el-tooltip>
          <el-tag v-else :type="signalTagType(row.signalLevel)" effect="dark">
            {{ signalLabel(row.signalLevel) }}
          </el-tag>
        </template>
      </el-table-column>
    </el-table>

    <div class="pagination-wrap">
      <el-button size="small" class="hide-mobile" @click="exportCsv">导出 CSV</el-button>
      <el-pagination
        background
        :layout="isMobile ? 'prev, pager, next' : 'total, prev, pager, next, sizes'"
        :current-page="page"
        :page-size="pageSize"
        :page-sizes="[20, 50, 100, 200]"
        :total="total"
        :small="isMobile"
        @current-change="(value) => $emit('page-change', value)"
        @size-change="(value) => $emit('page-size-change', value)"
      />
    </div>
  </section>
</template>

<script setup>
import { computed, ref } from 'vue'
import { Star, StarFilled } from '@element-plus/icons-vue'
import {
  deviationLabel,
  formatDateTime,
  formatNumber,
  formatPercent,
  formatPurchaseLimit,
  formatTurnover,
  formatUnitCount,
  signalLabel,
  signalTagType,
  valuationLabel
} from '../utils/formatters'

const props = defineProps({
  rows: { type: Array, required: true },
  activeType: { type: String, required: true },
  favorites: { type: Set, default: () => new Set() },
  sparklines: { type: Object, default: () => ({}) },
  arbConfig: { type: Object, required: true },
  marketPhase: { type: String, default: '--' },
  total: { type: Number, default: 0 },
  page: { type: Number, default: 1 },
  pageSize: { type: Number, default: 100 }
})

const emit = defineEmits(['sort-change', 'page-change', 'page-size-change', 'toggle-favorite', 'compare'])

const tableRef = ref(null)
const isMobile = ref(window.innerWidth <= 768)
if (typeof window !== 'undefined') {
  window.addEventListener('resize', () => { isMobile.value = window.innerWidth <= 768 })
}

const title = computed(() => {
  if (props.activeType === 'MONEY_ETF') {
    return '货币 ETF 列表'
  }
  return props.activeType === 'ETF' ? 'ETF 监控列表' : 'LOF 监控列表'
})

const showValuation = computed(() => props.activeType !== 'MONEY_ETF')
const showDeviation = computed(() => props.activeType !== 'MONEY_ETF')

const valuationColumnLabel = computed(() => {
  if (props.activeType === 'LOF') {
    return '最近净值'
  }
  const type = props.rows.find((row) => row.valuationType && row.valuationType !== 'NONE')?.valuationType
  return valuationLabel(type || 'NAV_REF')
})

const deviationColumnLabel = computed(() => {
  const sample = props.rows.find((row) => row.valuationType) || {}
  return deviationLabel(sample.valuationType, props.activeType)
})

function deviationValue(row) {
  if (props.activeType === 'MONEY_ETF') {
    return null
  }
  return row.premiumRate ?? row.navRefPremiumRate
}

function isCrossBorder(row) {
  const ft = (row.fundType || '')
  return ft.includes('QDII') || ft.includes('海外')
}

function settlementText(row) {
  if (row.instrumentType === 'MONEY_ETF') return 'T+0'
  if (row.instrumentType === 'LOF') return isCrossBorder(row) ? 'T+3' : 'T+2'
  return isCrossBorder(row) ? 'T+2' : 'T+1'
}

function settlementTip(row) {
  const ft = row.fundType || '--'
  if (row.instrumentType === 'MONEY_ETF') return `货币ETF: 申购当日可用 (${ft})`
  if (row.instrumentType === 'LOF') {
    if (isCrossBorder(row)) return `跨境LOF: 场内申购T+3可卖出 (${ft})`
    return `LOF: 场内申购T+2可卖出 (${ft})`
  }
  if (isCrossBorder(row)) return `跨境ETF: 申购T+2到账, T+3可卖 (${ft})`
  return `ETF: 申购T+1到账, T+2可卖 (${ft})`
}

function valueClass(value) {
  if (value === null || value === undefined) {
    return ''
  }
  if (value > 0) {
    return 'value-up'
  }
  if (value < 0) {
    return 'value-down'
  }
  return ''
}

function deviationClass(value) {
  if (value === null || value === undefined) {
    return ''
  }
  const abs = Math.abs(value)
  if (abs >= 0.10) {
    return 'dev-extreme'
  }
  if (abs >= 0.05) {
    return 'dev-high'
  }
  if (abs >= 0.03) {
    return 'dev-medium'
  }
  if (abs > 0) {
    return 'dev-low'
  }
  return ''
}

function isArbitrageOpportunity(row) {
  const dev = deviationValue(row)
  if (dev === null || dev === undefined) return false
  const cfg = props.arbConfig
  if (dev > 0 && cfg.premium?.enabled) {
    const p = cfg.premium
    if (dev * 100 >= p.devPct && row.turnover >= p.turnoverWan * 10000) {
      if (p.requireSubscribe && !row.canSubscribe) return false
      if (p.maxSubUnit > 0 && row.minimumSubscriptionUnit && row.minimumSubscriptionUnit > p.maxSubUnit) return false
      return true
    }
  }
  if (dev < 0 && cfg.discount?.enabled) {
    const d = cfg.discount
    if (Math.abs(dev) * 100 >= d.devPct && row.turnover >= d.turnoverWan * 10000) {
      if (d.requireRedeem && !row.canRedeem) return false
      if (d.indexOnly && !row.indexName) return false
      return true
    }
  }
  return false
}

function rowClassName({ row }) {
  return isArbitrageOpportunity(row) ? 'arb-row' : ''
}

function exportCsv() {
  const headers = ['代码', '名称', '现价', '涨跌%', '估值', '偏离%', '点差%', '成交额', '日限额', '到账', '申购', '赎回', '信号']
  const csvRows = [headers.join(',')]
  for (const row of props.rows) {
    const dev = deviationValue(row)
    csvRows.push([
      row.code,
      `"${row.name}"`,
      row.price,
      dev !== null && dev !== undefined ? (dev * 100).toFixed(2) : '',
      row.valuationValue ?? '',
      row.spreadRate != null ? (row.spreadRate * 100).toFixed(4) : '',
      row.turnover,
      formatPurchaseLimit(row.dailyPurchaseLimit),
      settlementText(row),
      row.canSubscribe ? '开放' : '关闭',
      row.canRedeem ? '开放' : '关闭',
      signalLabel(row.signalLevel)
    ].join(','))
  }
  const bom = '\uFEFF'
  const blob = new Blob([bom + csvRows.join('\n')], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `${props.activeType}_${new Date().toISOString().slice(0, 10)}.csv`
  a.click()
  URL.revokeObjectURL(url)
}

function sparkPoints(values) {
  if (!values || values.length < 2) return ''
  const w = 60, h = 24, pad = 2
  const min = Math.min(...values)
  const max = Math.max(...values)
  const range = max - min || 0.001
  return values.map((v, i) => {
    const x = pad + (i / (values.length - 1)) * (w - 2 * pad)
    const y = pad + (1 - (v - min) / range) * (h - 2 * pad)
    return `${x.toFixed(1)},${y.toFixed(1)}`
  }).join(' ')
}

function sparkPointsLarge(values) {
  if (!values || values.length < 2) return ''
  const w = 260, h = 80, pad = 10
  const min = Math.min(...values)
  const max = Math.max(...values)
  const range = max - min || 0.001
  return values.map((v, i) => {
    const x = pad + (i / (values.length - 1)) * (w - 2 * pad)
    const y = pad + (1 - (v - min) / range) * (h - 2 * pad)
    return `${x.toFixed(1)},${y.toFixed(1)}`
  }).join(' ')
}

function sparkZeroY(values) {
  if (!values || values.length < 2) return 40
  const min = Math.min(...values)
  const max = Math.max(...values)
  const range = max - min || 0.001
  const h = 80, pad = 10
  return pad + (1 - (0 - min) / range) * (h - 2 * pad)
}

function sparkColor(values) {
  if (!values || values.length < 2) return '#9ca3af'
  return values[values.length - 1] >= values[0] ? '#c2410c' : '#0f766e'
}

function handleSortChange({ prop, order }) {
  emit('sort-change', { prop, order })
}
</script>
