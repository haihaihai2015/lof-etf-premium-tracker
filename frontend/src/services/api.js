import axios from 'axios'

const client = axios.create({
  baseURL: '/api',
  timeout: 15000
})

export function fetchMeta() {
  return client.get('/meta/filters').then((response) => response.data)
}

export function fetchInstruments(params) {
  return client.get('/instruments', { params }).then((response) => response.data)
}

export function fetchInstrumentDetail(code) {
  return client.get(`/instruments/${code}`).then((response) => response.data)
}

export function fetchTimeline(code) {
  return client.get(`/instruments/${code}/timeline`).then((response) => response.data)
}

export function fetchSparklines(codes) {
  return client.get('/instruments/sparklines', { params: { codes: codes.join(',') } }).then((response) => response.data)
}

export function fetchStats(type) {
  return client.get('/instruments/stats', { params: { type } }).then((response) => response.data)
}

export function triggerRefresh(scope = 'realtime') {
  return client.post('/instruments/refresh', null, { params: { scope } }).then((response) => response.data)
}

// Backtest APIs
export function syncHistory() {
  return client.post('/backtest/sync-history').then((r) => r.data)
}

export function syncHistoryOne(code) {
  return client.post(`/backtest/sync-history/${code}`).then((r) => r.data)
}

export function getSyncStatus() {
  return client.get('/backtest/sync-status').then((r) => r.data)
}

export function getHistoryRange() {
  return client.get('/backtest/history-range').then((r) => r.data)
}

export function runBacktest(params) {
  return client.post('/backtest/run', params, { timeout: 120000 }).then((r) => r.data)
}
