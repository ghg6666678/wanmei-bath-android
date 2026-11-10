# 消费记录：分页、缓存与不等待（实现说明）

> **状态：已实现，未发版。**
> 早期版本的设计稿是围绕「落盘缓存」写的，后来在官方 bundle 里查到原版的做法，方向做了调整——现在**不落盘**，只做内存复用，因此不涉及任何隐私取舍。

---

## 一、原本的两个问题

**① 每月只能看到前 20 条**

```js
api('/api/records?month=' + recMonth)   // 从不传 page
```

这里的 **20 不是原版的数字，也不是服务端的限制**，是我们自己写死的：

| 位置 | 代码 |
| --- | --- |
| `vwater.py` | `def records(s, month=None, page=1, rows=20)` |
| `Api.java` | `extra.put("page_rows", 20)` |
| 原版前端 | `pageSize: 10` |

真正的差别不在页大小（我们 20 比原版 10 还大），而在**原版能一直翻、我们不能翻**。原版首页只出 10 条，往下滚自动要下一页，直到 `recordList.length >= totalcount`；我们一次拿 20 条就结束了，没有下一页。

所以这条的严重程度完全取决于使用频率：

| 使用频率 | 月记录数 | 20 条够不够 |
| --- | --- | --- |
| 两天一次 | ~15 | 够 |
| **一天一次** | **~30** | **只看到前 20 天，最后 10 天不见了** |
| 一天两次 | ~60 | 只看到三分之一 |

本项目的实际使用频率是**一天一次**，正处在会受影响的那一档——所以这条不是理论问题：旧版本真的会少显示约 10 天的记录。

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

也就是说，**原版也不是一次拉整月**：首屏 10 条，滚到底再要 10 条，一直要到总数为止。它快不是因为一次拿完，而是因为首屏只拿 10 条——比我们现在的 20 条更快返回。

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

挂在 `loadAll()` 成功路径的末尾，延迟 250ms 发起，拉一次当前月。

三个理由：

- 会话是 `loadAll` 里换好的，**它完成之后才「热」**；
- 原生桥上的请求是串行的（`Api` 的方法都带 `synchronized`），太早发起会和首页请求排队，反而拖慢首屏；
- 原来写死 `setTimeout 3000`，用户打开 App **1 秒内**切到记录页时预取还没开始，等于白做——实测打开 0.7s 切过去，仍会看到「加载中…」并发一次请求。

失败不算数——把 `rec.month` 退回空，切过去时会重试。

**能力边界**：预取只覆盖「打开 App 之后过一会儿再切记录页」。如果打开就立刻切过去，网络还没回来，该等还是要等——那种情况只有落盘缓存能解决。

### 3.4.1 月份范围（2026-11-10 修）

原来月份选择器只有 6 项，因为代码里写死了：

```js
for (let i = 0; i < 6; i++) { ... }      // ← 纯自己加的，没有任何依据
```

这 6 个月跟接口无关，也没有任何文档支持。原版的真实范围在 bundle 里写得很清楚：

```js
minDate: new Date(2020, 0, 1), maxDate: new Date,
s("van-datetime-picker", {attrs: {"min-date": t.minDate, "max-date": t.maxDate, type: "year-month"}})
```

也就是 **2020 年 1 月 至今**。

### 换成滚轮，而不是网格

第一版把 83 个月排成 4 列网格，能选，但很丑——像一张表格。原版是 `van-datetime-picker`，两列上下滚、中间一条选中带。所以改成同样的形态（`#wheelSheet`）：

```
        取消      选择月份      确定
                ┌──────────┐
        2026年   │  11月    │   ← 选中带
        2025年   │  10月    │
```

实现选的是 **`scroll-snap` + scroll 事件算最近项**，没有自己写手势：

```css
.wheelcol{scroll-snap-type:y mandatory;padding:88px 0}
.wheelcol .it{height:44px;scroll-snap-align:center}
```

`padding: 88px 0` 与 `.it` 的 44px 是配套的：首尾各留 `(220-44)/2`，这样第一项和最后一项都能滚到正中，不会顶边。

三个实现细节，都是踩过才写上的：

- **滑动中立即高亮，停稳后再做重活**。`scroll` 里直接算 `Math.round(scrollTop / 44)` 切 `.on`；重建月份列（年份变了才需要）延后 90ms，否则拖动时每帧都重排。
- **停稳后自己纠一次位置**。部分 WebView 对 `scroll-snap` 支持不完整，`Math.abs(scrollTop - i*44) > 2` 时补一次 `scrollTo`。已经对齐就不动，免得和原生吸附打架。
- **年份变了要夹月份**。从 2024 年 12 月滚回当前年（只有 11 个月）时，`Math.min` 把 12 夹成 11，否则会拼出 `2026-12` 这种不存在的月份。

范围沿用原版，但加了「未来月份不可选」：当前年份只生成到本月。

标签也就不用带年份了——滚轮里年份本身就是一列。

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
| 首页加载后预取 | 只发 1 次请求（隐藏页不补拉；弹层开着时不抢） |
| 切到记录页 | 0 请求，直接渲染 21 行（20 条 + 表头） |
| 触底两次 | 追加到 46 行，共 3 次请求 |
| 到底后继续触底 | 不再请求 |
| 切走再切回 | 0 请求，列表完整 |
| 刷新中 | 旧内容保留，不闪 |
| 刷新后 | 仍是 46 行（不截断），只多 1 次请求 |
| 刷新带出新记录 | 新记录出现在顶部，总数一致 |
| 高视口 + 25 条数据 | 自动补齐到 26 行 |
