# 消费记录：分页、缓存与不等待（实现说明）

> **状态：已实现，未发版。**
> 早期版本的设计稿是围绕「落盘缓存」写的，后来在官方 bundle 里查到原版的做法，方向做了调整——现在**不落盘**，只做内存复用，因此不涉及任何隐私取舍。

---

## 一、原本的两个问题

**① 每月只显示 20 条**

```js
api('/api/records?month=' + recMonth)   // 从不传 page
```

服务端默认 `page=1, rows=20`，前端没有翻页、没有「加载更多」。一天去两次浴室，一个月 60 条，**根本看不全**。

**② 每次进记录页都白屏等几秒**

```js
box.innerHTML = '<div class="empty">加载中…</div>';   // 每次都先清屏
```

请求本身要 1–3 秒（会话过期时还要多一次 `entrance`）。清屏之后用户只能干等，感知上就是「每次都要等好几秒」。

## 二、官方是怎么做的（bundle 里的证据）

在 `app.*.js` 里查到三层：

**① 路由级缓存**

```js
"index"==s||"usingCode"==s||... ? meta:{keepAlive:!1} : meta:{keepAlive:!0}
```

**② 同时有 exclude 列表**

```js
e("keep-alive", { attrs: { exclude: "record,help,success,showerStatus,hotwaterCode,setting,bookBath,my,using" } })
```

`exclude` 优先级更高，所以 `record`、`setting`、`my` **实际不缓存**；主界面的 `bath`（浴室列表）缓存。

**③ 但被缓存的 `bath` 里，`activated` 照样调 `request()`**

```js
activated: function(){ this.searchValue ? this.onSearch() : this.request() },
created:   function(){ this.searchValue ? this.onSearch() : this.request() }
```

**结论：官方不是「不请求」，而是「不清屏」。**

组件实例和 DOM 被 keep-alive 保住了，切回来时旧数据还在屏幕上，请求在后台跑，回来了再替换。用户从头到尾看不到空白——这才是「不用重新加载」的真实体感。

再看它记录页的加载逻辑：

```js
if (this.currentPage == 1) this.recordList = [];          // 只有第一页清空
this.recordList = this.recordList.concat(s.data.data);     // 其余追加
this.recordList.length >= s.data.totalcount                // 用总数判断到底
  ? (this.finished = true, this.currentPage > 1 && (this.finishedText = "暂时没有更多记录了"))
  : (this.finished = false);
```

`pageSize = 10`，触底自动加载。

## 三、现在的实现

### 3.1 状态放在内存，切 tab 复用

```js
const rec = { month, items: [], total: 0, count: 0, page: 1,
              finished, loading, at, error };
```

切到记录页时：

```js
if (rec.month !== m) { recMonth = m; loadRecords('reset'); }     // 换月，重来
else if (Date.now() - rec.at > 60000) loadRecords('refresh');     // 超过 1 分钟，后台刷新
else { renderRec(); }                                             // 直接复用，零请求
```

### 3.2 三种加载模式

| mode | 场景 | 行为 |
| --- | --- | --- |
| `reset` | 首次 / 换月份 | 清空列表，拉第 1 页 |
| `more` | 触底 | 追加下一页 |
| `refresh` | 后台刷新 | **不动界面**，拉第 1 页后合并 |

### 3.3 刷新为什么是「合并」不是「替换」

如果刷新时直接把列表换成第一页，会出现两个问题：

- 用户滚到第 3 页，刷新后列表突然缩到 20 条，滚动位置被弹回顶部
- 内容变短触发一次 scroll 事件，又会误触发「触底加载」

所以已经翻过多页时，用「头部替换 + 后面保留」：

```js
const k = r => [r.end_opdt, r.termid, r.opfare].join('|');
const seen = new Set(rows.map(k));
rec.items = rows.concat(rec.items.filter(r => !seen.has(k(r))));
```

新记录出现在顶部，旧记录不重复、也不消失。

### 3.4 预取

首页加载完（此时 `entrance` 已经发生过、会话是热的），3 秒后后台拉一次当前月。用户切过去时数据已在内存。

失败不算数——把 `rec.month` 退回空，切过去时会重试。

### 3.5 两个边界

**内容不足一屏时触底永远不会触发**，所以加载完要检查一次：

```js
function ensureRecFilled() {
  if (rec.finished || rec.loading || !rec.items.length) return;
  if ($('#pageRec').classList.contains('hidden')) return;     // ← 关键
  if (document.documentElement.scrollHeight <= window.innerHeight + 80) loadRecords('more');
}
```

`hidden` 判断不能省：记录页隐藏时 `document.scrollHeight` 由首页决定，会被误判成「还很空」，于是预取时一口气把整月拉完。

**到底之后不再请求**：`finished` 由 `totalcount` 判断（优先）或「本页不足一页」兜底。

### 3.6 原生侧

`MainActivity` 原来把 page 写死成 `1`：

```java
JSONObject d = api.records(month, 1);
```

改为解析 URL 里的 `page=` 参数，非法值回落 1。`rows` 用服务端默认的 20。

## 四、为什么没有落盘

原本的设计是 localStorage 按月缓存。查到官方做法之后放弃了，理由：

- **用户要的是「不等待」，不是「少请求」。** 不清屏 + 预取已经能完全达到这个体感，而且不需要任何存储。
- **落盘会把记录从「无状态」变成「有状态」**，需要改 README 的隐私措辞、加「清除本地缓存」入口、考虑退出登录时清不清。这些代价换来的只是「冷启动第一次也快」。
- **官方自己都把 `record` 排除在缓存外**，说明它的判断是：消费记录宁可贵一点也要拿最新的。

所以现在的取舍是：**内存复用（分钟级）+ 每次都问服务端**。数据永远是新的，体感不慢。

## 五、如果将来要做持久化

只有当「冷启动第一次进记录页」也被认为太慢时才值得做。届时需要一起处理：

1. **隐私措辞**：README 现在写「不采集、不上传」，本地落盘需要在措辞上讲清楚，并提供「我的 → 清除本地缓存」。
2. **账号隔离**：key 用 `sha256(outid)` 前 16 位，不存明文学号。但要承认这不是强匿名——知道学号的人可以验证。
3. **退出登录时缓存清不清**：建议保留（同设备大概率同一人），但提供手动清除。
4. **无状态模式**：缓存只能放浏览器 localStorage，服务端永不参与，否则直接违背 `BATH_STATELESS=1` 的承诺。
5. **必须整月替换，不能只追加**：`status` 会变、退款会删记录。

## 六、验证

用 fetch 桩注入 45 条数据（每页 20），覆盖 11 项：

| 场景 | 期望 |
| --- | --- |
| 首页加载后预取 | 只发 1 次请求（隐藏页不补拉） |
| 切到记录页 | 0 请求，直接渲染 21 行（20 条 + 表头） |
| 触底两次 | 追加到 46 行，共 3 次请求 |
| 到底后继续触底 | 不再请求 |
| 切走再切回 | 0 请求，列表完整 |
| 刷新中 | 旧内容保留，不闪 |
| 刷新后 | 仍是 46 行（不截断），只多 1 次请求 |
| 刷新带出新记录 | 新记录出现在顶部，总数一致 |
| 高视口 + 25 条数据 | 自动补齐到 26 行 |
