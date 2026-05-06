<template>
  <section class="panel compact-toolbar">
    <div class="toolbar-row">
      <el-select
        :model-value="activeType"
        @change="(val) => $emit('type-change', val)"
        size="large"
        style="width: 120px"
      >
        <el-option label="ETF" value="ETF" />
        <el-option label="LOF" value="LOF" />
        <el-option label="货币ETF" value="MONEY_ETF" />
      </el-select>

      <el-input
        v-model="localFilters.keyword"
        clearable
        placeholder="代码 / 名称"
        size="large"
        style="width: 180px"
        @keyup.enter="emitFilters"
      >
        <template #prefix><el-icon><Search /></el-icon></template>
      </el-input>

      <el-input-number
        v-model="localFilters.premiumThreshold"
        :min="0"
        :max="20"
        :step="0.1"
        :precision="1"
        size="small"
        style="width:130px"
        @change="emitFilters"
      >
        <template #prefix>溢价≥</template>
      </el-input-number>
      <span style="color:#68809a;font-size:13px;margin-left:-6px">%</span>

      <el-checkbox v-model="localFilters.subscribableOnly" @change="emitFilters" size="large" class="toolbar-check">可申赎</el-checkbox>
      <el-checkbox v-model="localFilters.purchasableOnly" @change="emitFilters" size="large" class="toolbar-check">可申购</el-checkbox>
      <el-checkbox v-model="localFilters.highLiquidityOnly" @change="emitFilters" size="large" class="toolbar-check">高流动</el-checkbox>
      <el-checkbox v-model="localFilters.favoritesOnly" @change="emitFilters" size="large" class="toolbar-check fav-check">收藏</el-checkbox>

      <el-button :icon="Setting" circle size="small" title="套利策略配置" @click="arbDrawerVisible = true" />

      <el-button @click="resetFilters">重置</el-button>
      <el-button type="primary" :loading="refreshing" @click="$emit('refresh', 'realtime')">刷新</el-button>
      <el-button :loading="syncing" @click="$emit('refresh', 'all')">同步</el-button>

      <span class="toolbar-meta">共 {{ total }} 条 | {{ marketPhase }} | {{ countdown }}s</span>
    </div>
  </section>

  <el-drawer v-model="arbDrawerVisible" title="套利策略配置" size="380px" direction="rtl" :append-to-body="true" :z-index="2000">
    <div class="arb-cfg">
      <!-- 溢价套利 -->
      <div class="arb-section">
        <div class="arb-section-head">
          <el-switch v-model="localArbConfig.premium.enabled" size="small" />
          <span class="arb-section-title">溢价套利</span>
        </div>
        <div v-show="localArbConfig.premium.enabled" class="arb-fields">
          <div class="arb-config-row"><span class="arb-lbl">溢价 ≥</span><el-input-number v-model="localArbConfig.premium.devPct" :min="0" :max="50" :step="0.5" :precision="1" size="small" style="width:100px" /><span>%</span></div>
          <div class="arb-config-row"><span class="arb-lbl">限购 ≤</span><el-input-number v-model="localArbConfig.premium.maxSubUnit" :min="0" :max="100000" :step="100" size="small" style="width:100px" /><span>元 <em style="font-size:11px;color:#94a3b8">(0=不限)</em></span></div>
          <div class="arb-config-row"><span class="arb-lbl">成交额 ≥</span><el-input-number v-model="localArbConfig.premium.turnoverWan" :min="0" :max="99999" :step="100" size="small" style="width:100px" /><span>万</span></div>
          <div class="arb-config-row"><span class="arb-lbl">仓位上限</span><el-input-number v-model="localArbConfig.premium.positionRatio" :min="0.01" :max="5" :step="0.1" :precision="2" size="small" style="width:100px" /><span>% 日成交 <em style="font-size:11px;color:#f59e0b">ℹ 仅建议</em></span></div>
          <el-checkbox v-model="localArbConfig.premium.trendNotDown" size="small">溢价趋势上升/横盘（排除下降）</el-checkbox>
          <el-checkbox v-model="localArbConfig.premium.excludeQDII" size="small">排除高波动 QDII</el-checkbox>
          <el-checkbox v-model="localArbConfig.premium.requireSubscribe" size="small">需申购开放</el-checkbox>
        </div>
      </div>

      <el-divider />

      <!-- 折价套利 -->
      <div class="arb-section">
        <div class="arb-section-head">
          <el-switch v-model="localArbConfig.discount.enabled" size="small" />
          <span class="arb-section-title">折价套利</span>
        </div>
        <div v-show="localArbConfig.discount.enabled" class="arb-fields">
          <div class="arb-config-row"><span class="arb-lbl">折价 ≥</span><el-input-number v-model="localArbConfig.discount.devPct" :min="0" :max="50" :step="0.5" :precision="1" size="small" style="width:100px" /><span>%</span></div>
          <div class="arb-config-row"><span class="arb-lbl">成交额 ≥</span><el-input-number v-model="localArbConfig.discount.turnoverWan" :min="0" :max="99999" :step="100" size="small" style="width:100px" /><span>万</span></div>
          <div class="arb-config-row"><span class="arb-lbl">持有天数 ≥</span><el-input-number v-model="localArbConfig.discount.minHoldDays" :min="0" :max="30" :step="1" size="small" style="width:100px" /><span>天 <em style="font-size:11px;color:#f59e0b">ℹ 提醒</em></span></div>
          <el-checkbox v-model="localArbConfig.discount.indexOnly" size="small">仅指数型基金</el-checkbox>
          <el-checkbox v-model="localArbConfig.discount.excludeHighVol" size="small">排除高波动行业（半导体/白酒等）</el-checkbox>
          <el-checkbox v-model="localArbConfig.discount.requireRedeem" size="small">需赎回开放</el-checkbox>
        </div>
      </div>

      <div style="margin-top:16px;display:flex;gap:8px;justify-content:flex-end">
        <el-button size="small" @click="resetArbConfig">恢复默认</el-button>
        <el-button size="small" type="primary" @click="saveArbConfig">应用策略</el-button>
      </div>
    </div>
  </el-drawer>
</template>

<script setup>
import { reactive, ref, watch, computed } from 'vue'
import { Search, Setting } from '@element-plus/icons-vue'

const props = defineProps({
  activeType: { type: String, required: true },
  defaults: { type: Object, required: true },
  filters: { type: Object, required: true },
  arbConfig: { type: Object, required: true },
  refreshing: { type: Boolean, default: false },
  syncing: { type: Boolean, default: false },
  total: { type: Number, default: 0 },
  marketPhase: { type: String, default: '--' },
  countdown: { type: Number, default: 0 }
})

const arbDrawerVisible = ref(false)
const localArbConfig = reactive(JSON.parse(JSON.stringify(props.arbConfig)))
watch(() => props.arbConfig, v => {
  Object.assign(localArbConfig.premium, v.premium)
  Object.assign(localArbConfig.discount, v.discount)
}, { deep: true })

function saveArbConfig() {
  emit('update-arb-config', JSON.parse(JSON.stringify(localArbConfig)))
  arbDrawerVisible.value = false
}

function resetArbConfig() {
  const d = defaultArbConfig()
  Object.assign(localArbConfig.premium, d.premium)
  Object.assign(localArbConfig.discount, d.discount)
}

function defaultArbConfig() {
  return {
    premium: { enabled: true, devPct: 5, maxSubUnit: 100, turnoverWan: 500, positionRatio: 0.3, trendNotDown: true, excludeQDII: true, requireSubscribe: true },
    discount: { enabled: true, devPct: 3, turnoverWan: 500, minHoldDays: 7, indexOnly: true, excludeHighVol: true, requireRedeem: true }
  }
}

const emit = defineEmits(['apply', 'refresh', 'type-change', 'arb-filter', 'update-arb-config'])

const localFilters = reactive({ ...props.filters })

watch(
  () => props.filters,
  (value) => {
    Object.assign(localFilters, value)
  },
  { deep: true }
)

function emitFilters() {
  emit('apply', { ...localFilters })
}

function resetFilters() {
  Object.assign(localFilters, props.defaults)
  emitFilters()
}
</script>
