# ETF / LOF 折溢价监控站点 MVP

Spring Boot + Vue 3 的内部研究型监控看板原型，覆盖：

- ETF：盘中 `IOPV`、实时溢价率、点差、成交额、研究信号
- LOF：最近净值、净值日期、相对最近净值偏离、申赎状态、研究信号

## 本地启动

后端：

```bash
mvn spring-boot:run
```

前端：

```bash
cd frontend
npm.cmd install
npm.cmd run dev
```

默认访问：

- 前端：`http://localhost:5173`
- 后端：`http://localhost:8080`
- H2 控制台：`http://localhost:8080/h2-console`

## 当前实现说明

- 采集层采用主备 `MockMarketDataProvider`，方便先验证产品流程与界面。
- 调度策略已接入：
  - `5 秒` 刷新行情与估值计算
  - `1 分钟` 落库分钟快照
  - `每日开盘前` 预留基础资料刷新任务
- 后续接真实数据源时，优先替换 `MarketDataProvider` 适配器即可。
