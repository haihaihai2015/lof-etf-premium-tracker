import { createRouter, createWebHistory } from 'vue-router'
import OverviewPage from '../pages/OverviewPage.vue'
import DetailPage from '../pages/DetailPage.vue'
import BacktestPage from '../pages/BacktestPage.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', component: OverviewPage },
    { path: '/detail/:code', component: DetailPage, props: true },
    { path: '/backtest', component: BacktestPage }
  ]
})

export default router
