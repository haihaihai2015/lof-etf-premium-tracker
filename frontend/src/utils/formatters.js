export function formatPercent(value) {
  if (value === null || value === undefined) {
    return '--'
  }
  return `${(value * 100).toFixed(2)}%`
}

export function formatNumber(value, digits = 4) {
  if (value === null || value === undefined) {
    return '--'
  }
  return Number(value).toFixed(digits)
}

export function formatTurnover(value) {
  if (value === null || value === undefined) {
    return '--'
  }
  if (value >= 100000000) {
    return `${(value / 100000000).toFixed(2)}亿`
  }
  if (value >= 10000) {
    return `${(value / 10000).toFixed(2)}万`
  }
  return `${value}`
}

export function formatUnitCount(value) {
  if (value === null || value === undefined) {
    return '--'
  }
  if (value >= 10000) {
    const num = value / 10000
    return `${Number.isInteger(num) ? num.toFixed(0) : num.toFixed(2)}万`
  }
  return `${value}元`
}

export function formatPurchaseLimit(value) {
  if (value === null || value === undefined) return '--'
  if (value === -1) return '不限购'
  if (value === 0) return '暂停'
  if (value >= 100000000) return `${(value / 100000000).toFixed(1)}亿`
  if (value >= 10000) return `${(value / 10000).toFixed(0)}万`
  return `${value}元`
}

export function signalLabel(level) {
  return {
    WATCH: '可关注',
    OBSERVE: '观察',
    IGNORE: '忽略',
    INSUFFICIENT_DATA: '数据不足'
  }[level] || '--'
}

export function valuationLabel(type) {
  return {
    IOPV: 'IOPV',
    NAV_REF: '参考净值',
    NONE: '暂无估值'
  }[type] || '--'
}

export function deviationLabel(type, instrumentType) {
  if (type === 'IOPV') {
    return '实时溢价率'
  }
  if (instrumentType === 'LOF') {
    return '相对最近净值偏离'
  }
  return '相对参考净值偏离'
}

export function formatDateTime(value) {
  if (!value) {
    return '--'
  }
  return value.replace('T', ' ').slice(0, 16)
}

export function signalTagType(level) {
  return {
    WATCH: 'danger',
    OBSERVE: 'warning',
    IGNORE: 'info',
    INSUFFICIENT_DATA: ''
  }[level] || ''
}
