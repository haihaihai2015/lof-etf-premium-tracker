<template>
  <div ref="chartRef" class="chart"></div>
</template>

<script setup>
import * as echarts from 'echarts'
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'

const props = defineProps({
  title: { type: String, required: true },
  xData: { type: Array, required: true },
  series: { type: Array, required: true }
})

const chartRef = ref()
let chartInstance

function render() {
  if (!chartRef.value) {
    return
  }
  if (!chartInstance) {
    chartInstance = echarts.init(chartRef.value)
  }
  chartInstance.setOption({
    backgroundColor: 'transparent',
    color: ['#0050b3', '#0f766e', '#c2410c'],
    title: {
      text: props.title,
      left: 12,
      top: 8,
      textStyle: { fontSize: 14, fontWeight: 600, color: '#17324d' }
    },
    tooltip: { trigger: 'axis' },
    legend: { top: 8, right: 12 },
    grid: { left: 48, right: 24, top: 50, bottom: 32 },
    xAxis: {
      type: 'category',
      data: props.xData,
      boundaryGap: false,
      axisLabel: { color: '#4c627a' }
    },
    yAxis: {
      type: 'value',
      axisLabel: { color: '#4c627a' },
      splitLine: { lineStyle: { color: '#d8e7f7' } }
    },
    series: props.series.map((item) => ({
      type: 'line',
      smooth: true,
      showSymbol: false,
      ...item
    }))
  })
}

onMounted(() => {
  render()
  window.addEventListener('resize', render)
})

watch(() => [props.xData, props.series], render, { deep: true })

onBeforeUnmount(() => {
  window.removeEventListener('resize', render)
  if (chartInstance) {
    chartInstance.dispose()
  }
})
</script>
