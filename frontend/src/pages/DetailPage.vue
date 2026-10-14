<template>
  <div class="stack" v-if="detail">
    <section class="panel detail-header">
      <div>
        <p class="eyebrow">{{ detail.instrument.instrumentType }} / {{ detail.instrument.market }}</p>
        <h2>{{ detail.instrument.name }} <span class="code-inline">{{ detail.instrument.code }}</span></h2>
        <p class="subtle">{{ detail.instrument.fundCompany || '--' }} / {{ detail.instrument.indexName || '--' }}</p>
      </div>
      <div class="detail-actions">
        <el-tag :type="signalTagType(detail.instrument.signalLevel)" effect="dark">
          {{ signalLabel(detail.instrument.signalLevel) }}
        </el-tag>
        <RouterLink to="/" class="back-link">返回看板</RouterLink>
      </div>
    </section>

    <section class="detail-grid">
      <div class="panel metrics-grid">
        <div class="metric">
          <span>现价</span>
          <strong>{{ formatNumber(detail.instrument.price) }}</strong>
        </div>
        <div class="metric">
          <span>{{ valuationLabel(detail.instrument.valuationType) }}</span>
          <strong>{{ formatNumber(detail.instrument.valuationValue) }}</strong>
        </div>
        <div class="metric">
          <span>{{ deviationTitle }}</span>
          <strong :class="valueClass(deviationRate)">{{ formatPercent(deviationRate) }}</strong>
        </div>
        <div class="metric">
          <span>点差</span>
          <strong>{{ formatPercent(detail.instrument.spreadRate) }}</strong>
        </div>
        <div class="metric">
          <span>成交额</span>
          <strong>{{ formatTurnover(detail.instrument.turnover) }}</strong>
        </div>
        <div class="metric">
          <span>盘口</span>
          <strong>{{ formatNumber(detail.instrument.bid1) }} / {{ formatNumber(detail.instrument.ask1) }}</strong>
        </div>
      </div>

      <div class="panel detail-meta">
        <ul>
          <li><span>研究信号</span><strong>{{ signalLabel(detail.instrument.signalLevel) }}</strong></li>
          <li><span>原因</span><strong>{{ detail.instrument.signalReason }}</strong></li>
          <li><span>估值类型</span><strong>{{ valuationLabel(detail.instrument.valuationType) }}</strong></li>
          <li><span>净值日期</span><strong>{{ detail.instrument.valuationDate || '--' }}</strong></li>
          <li><span>交易阶段</span><strong>{{ detail.instrument.marketPhase }}</strong></li>
          <li><span>最后刷新</span><strong>{{ formatDateTime(detail.instrument.quoteTs) }}</strong></li>
        </ul>
      </div>
    </section>

    <section class="charts-grid">
      <section class="panel">
        <LineChart :title="'价格 / 估值参考'" :x-data="timelineLabels" :series="priceSeries" />
      </section>
      <section class="panel">
        <LineChart :title="'偏离曲线'" :x-data="timelineLabels" :series="premiumSeries" />
      </section>
    </section>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import LineChart from '../components/LineChart.vue'
import { fetchInstrumentDetail, fetchTimeline } from '../services/api'
import { deviationLabel, formatDateTime, formatNumber, formatPercent, formatTurnover, signalLabel, signalTagType, valuationLabel } from '../utils/formatters'

const route = useRoute()
const detail = ref(null)
const timeline = ref([])
let pollTimer

const deviationRate = computed(() => detail.value?.instrument?.premiumRate ?? detail.value?.instrument?.navRefPremiumRate)
const deviationTitle = computed(() => deviationLabel(detail.value?.instrument?.valuationType, detail.value?.instrument?.instrumentType))
const timelineLabels = computed(() => timeline.value.map((item) => item.ts?.slice(11, 16)))

const priceSeries = computed(() => {
  const valuationName = valuationLabel(detail.value?.instrument?.valuationType)
  const series = [{ name: '现价', data: timeline.value.map((item) => item.price) }]
  if (timeline.value.some((item) => item.valuationValue !== null && item.valuationValue !== undefined)) {
    series.push({ name: valuationName, data: timeline.value.map((item) => item.valuationValue) })
  }
  return series
})

const premiumSeries = computed(() => [
  {
    name: deviationTitle.value,
    data: timeline.value.map((item) => {
      const value = item.premiumRate ?? item.navRefPremiumRate
      return value === null || value === undefined ? null : value * 100
    })
  }
])

async function load() {
  detail.value = await fetchInstrumentDetail(route.params.code)
  timeline.value = await fetchTimeline(route.params.code)
}

function valueClass(value) {
  if (value > 0) {
    return 'value-up'
  }
  if (value < 0) {
    return 'value-down'
  }
  return ''
}

onMounted(async () => {
  await load()
  pollTimer = window.setInterval(load, 10000)
})

onBeforeUnmount(() => {
  window.clearInterval(pollTimer)
})
</script>
