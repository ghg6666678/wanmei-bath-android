# 接口文档

这个客户端一共跟三类服务器打交道，下面把它们分开列清楚：

| 分类 | 对方 | 用在哪 | 性质 |
| --- | --- | --- | --- |
| **A. 登录接口** | `app.17wanxiao.com` / `server.17wanxiao.com` | App 与网页端 | 官方 App 接口，逆向还原 |
| **B. 智慧用水接口** | `vwater.59wanmei.com` | App 与网页端 | 官方 H5 接口，逆向还原 |
| **C. 本机服务接口** | 自己跑的 `server.py` | 仅网页端 | 本项目自建 |

App 端（Android）把 A、B 两类接口用 Java 重写了（`Login.java` / `Api.java`），不经过 C；网页端则把 A、B 放在 Python 里（`wxlogin.py` / `vwater.py`），浏览器只跟 C 通信。

> 文档里的密钥、token、学号等一律用占位符。真实的签名密钥在源码 `Api.java` / `vwater.py` 的 `SIGN_KEY` 常量里，本文不复述。

---

# A. 登录接口

官方 App 的登录。协议来源是社区开源实现（`zhongbr` 的 2020 版、`fuqiuluo/wanxiao-campus` 的 2024 版），本项目按其流程用 Java 与 Python 各实现了一遍。

## A.0 公共约定

| 项 | 值 |
| --- | --- |
| 域名 | `https://app.17wanxiao.com:443`（新）、`https://server.17wanxiao.com`（老） |
| User-Agent | `Dalvik/2.1.0 (Linux; U; Android 5.1.1; HUAWEI MLA-AL10 Build/HUAWEIMLA-AL10)` |
| Content-Type | `application/text;charset=UTF-8`（是 `text`，不是 `application/json`） |
| 请求体 | **紧凑 JSON**（`{"a":1,"b":2}`，无空格、无换行）。签名算的就是这串字节，多一个空格签名就错 |
| 客户端版本号 | `wanxiaoVersion = 10552101`（写死在请求参数里） |
| 设备号 | `deviceId`，一个随机 UUID，整个会话期间保持不变 |

## A.1 交换密钥

登录前必须先换一把会话密钥，后续所有业务参数都用它加密。

```
POST /campus/cam_iface46/exchangeSecretkey.action
```

**请求**（不需要 `campusSign`）

```json
{"key":"<本地生成的 RSA-1024 公钥，Base64>"}
```

- 公钥格式必须是 **X.509 SubjectPublicKeyInfo**（Java 的 `RSAPublicKey.getEncoded()`、PyCryptodome 的 `export_key(format='DER')`，实测 162 字节）
- 手工拼的 PKCS#1 格式（140 字节）会被接受但**返回 200 + 空响应**——这是本项目踩过的一个坑

**响应**

不是 JSON，而是一段 Base64 密文（RSA PKCS#1 v1.5 加密）：

```
<base64>
```

用本地私钥解密后得到：

```json
{"session":"<会话 ID>","key":"<appKey，至少 24 字节>"}
```

| 字段 | 用途 |
| --- | --- |
| `session` | 会话 ID。**登录成功后，它就是「智慧用水」要的 token** |
| `key` | 3DES 密钥材料，取**前 24 字节**作为 `DESede` 的 key |

## A.2 发送短信验证码

```
POST /campus/cam_iface46/gainMatrixCaptcha543.action
```

**请求**

```json
{"action":"registAndLogin","confirm":false,"deviceId":"<uuid>","mobile":"<11 位手机号>","requestMethod":"cam_iface46/gainMatrixCaptcha543.action","type":"sms"}
```

**响应**

```json
{"code_":"0","message_":"...","data":"..."}
```

`code_` 为 `"0"` 表示成功；否则把 `message_` 原样抛给用户。

> 同参数的 `checkBeforeSendRegisterCode.action` 用于发送前检查号码是否已注册，本项目仅在命令行工具里调用，App 内不调。

## A.3 短信验证码登录

```
POST /campus/cam_iface46/registerUsersByTelAndLoginNew.action
```

**请求**

```json
{"appCode":"M002","deviceId":"<uuid>","mobile":"<手机号>","netWork":"wifi","qudao":"tencent","requestMethod":"cam_iface46/registerUsersByTelAndLoginNew.action","shebeixinghao":"<机型>","sms":"<验证码>","systemType":"android","telephoneInfo":"12","telephoneModel":"<机型>","wanxiaoVersion":10552101}
```

**响应**

```json
{"code_":"0","message_":"...","data":"<base64 的 3DES 密文>"}
```

`data` 解密（用 A.1 的 appKey 前 24 字节）后得到：

```json
{"user":{"token":"...","customId":"...","ecardCustomerid":"...","authStuNo":"...","name":"...","customName_":"..."}}
```

| 字段 | 映射到本项目的配置 | 说明 |
| --- | --- | --- |
| `customId` | `customerCode` | 学校/客户编码 |
| `ecardCustomerid` | `customerid` | 一卡通客户号 |
| `authStuNo`（其次 `userName` / `account`） | `outid` | 学号 |
| `name` | `name` | 姓名 |
| `customName_`（其次 `schoolName` / `customerName`） | `school` | 学校名 |

> **关于 token**：本项目写进配置、拿去调用水接口的，是 A.1 交换密钥拿到的 **`session`**，不是这里的 `user.token`。`user.token` 代码里也解析出来了（截前 36 位备用），但实测 H5 认的是 `session`——两处都保留，避免哪天服务端换了口径。

> `outid` / `customerid` / `customerCode` 这三个值在 B.1 的 `entrance` 里还会被服务端重新下发一次，**以服务端下发的为准**。

## A.4 密码登录（本项目未使用）

```
POST /campus/cam_iface46/loginnew.action
```

参数与 A.3 类似，用 `password` 代替 `sms`。密码不是明文，而是**逐字符**加密后组成数组：

```json
{"password":["<第 1 个字符的 3DES 密文 base64>","<第 2 个字符…>"]}
```

其余字段多出 `type:"1"`、`yunyingshang:"07"`。响应结构与 A.3 相同。

## A.5 加密与签名细则

这三个步骤是登录能成功的关键，顺序不能错。

**① 业务参数加密（3DES-CBC）**

```
算法：DESede/CBC/PKCS5Padding（等价 PKCS7）
key ：appKey 的前 24 字节
IV  ：固定字符串 "66666666"
输出：Base64
```

**② 组装信封**

```json
{"session":"<A.1 拿到的 session>","data":"<① 的 Base64>"}
```

**③ 计算签名（campusSign）**

```
campusSign = sha256(紧凑 JSON(信封)).hex()
```

- 算的是**上面这串信封原文**，不是明文参数
- 必须是紧凑 JSON：Java 端是手工字符串拼接，Python 端是 `separators=(',',':')`
- 结果放**请求头** `campusSign`

```
POST /campus/cam_iface46/xxx.action
Content-Type: application/text;charset=UTF-8
campusSign: <上一步的 sha256 十六进制>
User-Agent: <A.0 的 UA>

{"session":"...","data":"..."}
```

> 响应里的 `data` 字段同样用 ① 的方式解密（换密钥接口除外，那个是 RSA）。

---

# B. 智慧用水接口

业务全在官方 H5（`xqh5.17wanxiao.com/water3to1`）里，App 原生层只负责套壳。所有业务调用都汇总到**同一个地址**，靠 `cmd` 字段区分功能。

## B.0 公共约定

| 项 | 值 |
| --- | --- |
| 基地址 | `https://vwater.59wanmei.com/usewater` |
| `Origin` | `https://xqh5.17wanxiao.com` |
| `User-Agent` | 官方 H5 的 UA，末尾带 `wanxiao/5.8.36` |
| 请求体格式 | `application/x-www-form-urlencoded` |
| 时间戳 | `timestamp` 用**毫秒**；业务参数里的 `timestamp` 字段用 `yyyyMMddHHmmss` |

## B.1 入口：换取 uuid

所有业务调用前必须先拿 `uuid`，它由入口跳转下发。

```
GET /api/use-water/entrance?source=wx&tVer=20260918&versionnum=1050836101
    &customerId=<customerCode>&systemType=Android&UAinfo=wanxiao
    &versioncode=10589101&token=<A.3 拿到的 token>
```

**关键点：不能跟随 302 跳转。** 需要的不是跳转后的页面，而是响应头里的 `Location`：

```
302 Location: https://xqh5.17wanxiao.com/water3to1/?uuid=<uuid>&outid=<学号>&customerid=...&customerCode=...
```

| 参数 | 说明 |
| --- | --- |
| `uuid` | 后续所有请求的签名因子与请求头 |
| `outid` / `customerid` / `customerCode` | 服务端下发的权威值，**覆盖登录时拿到的** |

- 解析出的完整 `Location` 会作为后续请求的 `Referer`
- 没有 `Location` 或没有 `uuid` → 说明 token 已失效，需要重新登录
- 本项目在 uuid 超过 **240 秒（App）/ 300 秒（网页端）** 后自动重新 entrance；业务调用失败时也会重置 uuid 重试一次

## B.2 通用调用

```
POST /api/use/water/h5/WXInvokFront/
```

**请求头**

| 头 | 值 |
| --- | --- |
| `uuid` | B.1 拿到的 uuid |
| `timestamp` | 当前毫秒时间戳，字符串 |
| `encrypt` | `Base64(AES-ECB-PKCS7(uuid + "-" + timestamp, SIGN_KEY))` |
| `Referer` | B.1 的 `Location` 完整地址 |

**请求体**（form-urlencoded）

```
param=<紧凑 JSON>&source=wx
```

`param` 的 JSON：

```json
{"cmd":"<功能名>","ver":"2.0","source":"wx", ...功能参数}
```

**响应**

```json
{"body":"<内层 JSON，可能是字符串也可能是对象>"}
```

内层统一结构：

```json
{"result":"0","message":"...","data":{}}
```

- `result` 为 `"0"` 表示成功；非 `0` 时 `message` 通常是失败原因
- 内层 `message` 含「登录」「用户信息」字样 → App 端会当成登录态失效，直接弹登录框；网页端把原文透传给用户

## B.3 签名说明

```
明文   = uuid + "-" + timestamp（毫秒）
算法   = AES/ECB/PKCS5Padding（即 PKCS7）
密钥   = SIGN_KEY，16 字节，标准 AES-128
        （官方 H5 前端 JS 里的明文常量；Java 与 Python 都用各自的原生实现，不需要 Node）
输出   = Base64，放请求头 encrypt
```

> 用的是标准 AES-128：16 字节密钥。曾经误判为「13 字节非标准变体」，核对 H5 后已更正——14 个字母的拼音 + 两位凑足 16 字节，恰好是合法密钥长度。

## B.4 浴室列表 + 当前预约

```json
{"cmd":"zfbgetbathroomlist","outid":"<学号>","ver":"2.0","source":"wx"}
```

**响应 `data`**

| 字段 | 说明 |
| --- | --- |
| `bathroomlist[]` | 浴室列表 |
| `orderbathroomlist[]` | 当前有效的预约（含验证码） |
| `nowdate` | 服务端当前时间，`HHMMSS`，用于校时 |
| `oddfare` | 余额占位值，**实测恒为 `"0.0"`，不能当余额用** |

浴室项（`bathroomlist[]`）：

| 字段 | 说明 |
| --- | --- |
| `bathroomcode` | 浴室编号，预约时要用 |
| `bathroomname` | 名称，如 `一号宿舍a区` |
| `freetermnums` | 空闲设备数 |
| `sumtermnums` | 设备总数 |
| `max_fare_limit` / `max_time_limit` | 该浴室的默认限额（分）/ 限时（分钟） |
| `businessclosetime` | 营业结束时间，`HH:mm:ss` |

预约项（`orderbathroomlist[]`）：

| 字段 | 说明 |
| --- | --- |
| `orderno` | 订单号，取消时用 |
| `bathroomname` | 浴室名 |
| `orderdatetime` | 预约时间 `yyyy-MM-dd HH:mm:ss` |
| `verificationcode` | **验证码**，去设备上输入开阀 |
| `max_fare_limit` / `max_time_limit` | 这单在下单时冻结的限额/限时 |

> 订单里存的是**下单当时**的限额/限时。所以改了设置必须重新下单才生效——这就是「保存限额后自动取消已有预约」的原因。

## B.5 余额

```json
{"cmd":"zfbgetcustoddfare","outid":"<学号>","timestamp":"20261109183000","ver":"2.0","source":"wx"}
```

响应直接给 `oddfare`（单位元，字符串小数）。**必须单独查这个接口**，B.4 里的 `oddfare` 是假的。

## B.6 查询消费限制

```json
{"cmd":"zfbquerylimit","outid":"<学号>","limittype":1,"timestamp":"20261109183000","ver":"2.0","source":"wx"}
```

| `limittype` | 含义 |
| --- | --- |
| `1` | 预约洗澡（本项目用这个） |
| `2` | 打热水 |
| `3` | 宿舍淋浴 |

**响应 `data`**

```json
{"max_fare_limit":"235","max_time_limit":"30"}
```

`max_fare_limit` 单位是**分**。

## B.7 保存消费限制

```json
{"cmd":"zfbsavelimit","outid":"<学号>","timestamp":"20261109183000","limittype":1,"max_fare_limit":235,"max_time_limit":30,"ver":"2.0","source":"wx"}
```

| 参数 | 约束 |
| --- | --- |
| `max_fare_limit` | 单位**分**。本项目实测范围 100–2000（1–20 元），且必须是 **5 的整数倍**（浴室按 0.05 元扣费） |
| `max_time_limit` | 分钟，1–120 |
| 不限额 | `max_fare_limit = 500000`（即 5000 元）是官方的「不限」标记 |

`result == "0"` 为成功。

> 服务端不做这些范围校验，非法值也会返回成功。范围与步进是本项目在客户端与本地服务里把关的，避免写出一个「看着存上了、实际扣费异常」的配置。

## B.8 预约（生成验证码）

```json
{
  "cmd":"zfbskorderbathroom",
  "orderno":"",
  "bathroomcode":"<浴室编号>",
  "outid":"<学号>",
  "orderdatetime":"2026-11-09 18:30:00",
  "verificationtype":"2",
  "timetype":"0",
  "ecard_customerid":"<一卡通客户号>",
  "verificationtelephone":"<学号>",
  "max_fare_limit":235,
  "max_time_limit":30,
  "timestamp":"20261109183000",
  "ordertype":0,
  "ver":"2.0","source":"wx"
}
```

| 参数 | 说明 |
| --- | --- |
| `orderno` | 新建订单传空串 |
| `orderdatetime` | `yyyy-MM-dd HH:mm:ss`，**秒固定为 00**；取服务端时间，不用本机时钟 |
| `verificationtype` | 固定 `"2"`；此时 `verificationtelephone` 传 `outid`（学号），换成手机号则传 `mobile` |
| `max_fare_limit` / `max_time_limit` | 这次订单要冻结的限额/限时（分 / 分钟） |

**响应**

```json
{"result":"0","message":"...","data":{"verificationcode":"123456","orderdatetime":"2026-11-09 18:30:00"}}
```

> 「预约时间不能早于当前时间」这个报错**是官方前端自己抛的**，服务端并不校验。但本项目仍然在客户端拦截，避免生成过期订单——不用本地时钟算时间，正是为了绕开这个报错的根因。

## B.9 取消预约

```json
{"cmd":"zfbrevokeorderbathroom","outid":"<学号>","closetype":0,"orderno":"<订单号>","timestamp":"20261109183000","ver":"2.0","source":"wx"}
```

`result == "0"` 为成功。取消后验证码立即失效。

## B.10 用水记录

```json
{"cmd":"getwaterconsume","outid":"<学号>","page_index":1,"page_rows":20,"timestamp":"20261109183000","month":"2026-11","ver":"2.0","source":"wx"}
```

| 参数 | 说明 |
| --- | --- |
| `month` | 可选，`yyyy-MM`；不传则返回最近记录 |
| `page_index` / `page_rows` | 分页，本项目固定第 1 页、每页 20 条 |

**响应 `data`**

| 字段 | 说明 |
| --- | --- |
| `totalfare` | 本月合计（元） |
| `data[]` | 明细 |

明细项：

| 字段 | 说明 |
| --- | --- |
| `termname` | 设备名（浴室） |
| `termid` | 设备编号 |
| `end_opdt` | 结束时间 |
| `opfare` | 本次扣费（元） |
| `status` | `"1"` 已扣费，其他为未扣费 |

---

# C. 本机服务接口（仅网页端）

网页端由 `server.py` 提供，浏览器只跟它通信；它再拿 A、B 两套协议去请求官方服务器。零第三方依赖，只用标准库。

## C.0 约定

| 项 | 值 |
| --- | --- |
| 默认端口 | `8770` |
| 返回 | JSON，`Content-Type: application/json; charset=utf-8`，`Cache-Control: no-store` |
| 成功 | `{"ok":true, ...}` |
| 失败 | `{"ok":false,"error":"<人类可读的原因>"}` |
| 未登录 | `{"ok":false,"needLogin":true,"error":"..."}` |

## C.1 接口一览

| 方法 | 路径 | 作用 |
| --- | --- | --- |
| GET | `/api/state` | 一次性拿 配置 + 余额 + 预约 + 浴室列表 + 服务端时间 + 当前限制 |
| GET | `/api/config` | 只拿账号配置（不含 token） |
| GET | `/api/time` | 服务端时间与本地时间，用于校时 |
| GET | `/api/settings` | 当前消费限时 / 限额 |
| GET | `/api/records?month=YYYY-MM` | 用水记录 |
| POST | `/api/book` | 预约 |
| POST | `/api/cancel` | 取消预约 |
| POST | `/api/settings` | 保存限时 / 限额 |
| POST | `/api/config` | 覆盖写入配置（调试用） |
| POST | `/api/native` | 浏览器端模拟 App 原生调用（登录 / 退出） |
| GET | `/api/version` | 检查更新 |

## C.2 `/api/state`

`GET`，无参数。内部依次调用 B.4、B.5、B.6。

```json
{
  "ok": true,
  "config": {"name":"...","phone":"...","outid":"...","school":"...","hasToken":true},
  "balance": 12.34,
  "orders": [ ... ],
  "bathrooms": [ ... ],
  "serverTime": "183000",
  "settings": {"max_fare_limit":235,"max_time_limit":30},
  "limits": { ... }
}
```

`orders` / `bathrooms` 的结构同 B.4；`balance` 查询失败时为 `null`。

## C.3 `/api/book`

`POST`

```json
{"bathroomcode":"<编号>","datetime":"2026-11-09 18:30:00","verificationtype":"2"}
```

服务端的把关（官方服务端不管这些）：

1. `datetime` 必须能解析，且**不早于服务端当前时间 2 分钟以上**，否则报错并给出服务端时间
2. 提交时间不得晚于该浴室的 `businessclosetime`
3. 限额/限时取用户在「我的」里设置的，而不是浴室默认值

成功响应：

```json
{"ok":true,"message":"预约成功","verificationcode":"123456","orderdatetime":"2026-11-09 18:30:00"}
```

## C.4 `/api/cancel`

`POST`

```json
{"orderno":"<订单号>"}
```

```json
{"ok":true,"message":"已取消预约"}
```

## C.5 `/api/settings`

`POST`

```json
{"time":30,"fare":2.35}
```

`fare` 单位是**元**（服务端内部换算成分）。校验规则同 B.7：`time` 1–120；`fare` 1.00–20.00 且必须是 0.05 的整数倍，或传 5000 表示不限。

## C.6 `/api/native`

`POST`，用于让网页端也能像 App 一样登录/退出（浏览器里没有 `Android` 对象）。

```json
{"name":"doLogin","args":["<手机号>","<验证码>"]}
```

| `name` | `args` | 说明 |
| --- | --- | --- |
| `sendCode` | `[手机号]` | 发送验证码 |
| `doLogin` | `[手机号, 验证码]` | 登录，成功后写入 `config.json` |
| `logout` | `[]` | 清除票据 |

响应统一为 `{"ok":true,"data":{}}` 或 `{"ok":false,"error":"..."}`。

## C.7 `/api/version`

`GET`，可带 `?force=1` 跳过缓存。服务端代查 GitHub（用户所在网络经常打不开），多通道并行探测，失败时退回随包发布的 `web/latest.json`，再退回磁盘缓存。

```json
{
  "ok": true,
  "current": {"version":"1.0.3","versionCode":0},
  "latest": {
    "version":"1.0.3","publishedAt":"2026-11-09T11:38:15Z",
    "notes":"...","apkName":"zhishui-1.0.3.apk",
    "apkUrl":"https://github.com/.../zhishui-1.0.3.apk",
    "sha256":"3e8e1008...","mirrors":["https://ghfast.top/...","https://ghproxy.cn/..."],
    "releaseUrl":"https://github.com/.../releases/tag/v1.0.3"
  },
  "source": "GitHub 官方接口",
  "stale": false,
  "error": "",
  "tried": ["GitHub 官方接口 成功","GitHub 代理镜像 失败","..."]
}
```

- `stale: true` 表示网络不通、数据来自本地清单或缓存
- 缓存 30 分钟；`stale` 结果只缓存 5 分钟，网络恢复后尽快重探

## C.8 静态资源

| 路径 | 内容 |
| --- | --- |
| `/` | `web/index.html` |
| `/latest.json` | 版本清单（断网兜底） |
| `/manifest.webmanifest`、`/icon.svg` | PWA 用 |

---

# D. 版本检查用到的外部接口

App 与网页端都会查 GitHub，通道与降级顺序见 [DEVELOPMENT.md 8.4](DEVELOPMENT.md#84-103检查更新)。这里只列地址：

| 用途 | 地址 |
| --- | --- |
| 最新 Release（权威） | `https://api.github.com/repos/ghg6666678/wanmei-bath-android/releases/latest` |
| 同上，代理镜像 | `https://ghproxy.cn/https://api.github.com/repos/.../releases/latest` |
| 版本清单 | `https://cdn.jsdelivr.net/gh/ghg6666678/wanmei-bath-android@main/latest.json` |
| 同上，备用节点 | `https://gcore.jsdelivr.net/gh/...`、`https://jsd.onmicrosoft.cn/gh/...` |
| 同上，raw | `https://raw.githubusercontent.com/ghg6666678/wanmei-bath-android/main/latest.json` |
| APK 下载 | `https://github.com/ghg6666678/wanmei-bath-android/releases/download/v<版本>/zhishui-<版本>.apk` |
| APK 镜像 | `https://ghfast.top/<上面的直链>`、`https://ghproxy.cn/<上面的直链>` |

---

# E. 报错对照表

| 现象 | 通常原因 | 处理 |
| --- | --- | --- |
| 登录接口返回空响应 | 公钥不是 X.509 格式 | 用 `getEncoded()` / `export_key(format='DER')` |
| 登录返回「签名错误」 | 信封 JSON 不紧凑，或签名算错了对象 | 签的是信封，不是明文参数 |
| 换密钥后所有接口 401/失败 | `appKey` 取的字节数不对 | 取前 24 字节 |
| 用水接口一直提示登录失效 | token 用错来源 | 用 A.1 的 `session`，不是 `user.token` |
| `entrance` 拿不到 Location | token 已过期，或跟随了 302 | 关掉重定向再取响应头 |
| 业务调用无响应（HTTP 000 / 空 body） | uuid 过期 | 重置 uuid，重新 entrance 后重试一次 |
| 余额显示 0。00 | 用了浴室列表里的 `oddfare` | 单独调 `zfbgetcustoddfare` |
| 改了限额但没生效 | 订单里冻的是下单时的值 | 重新预约 |
| 预约报「时间不能早于当前」 | 用了本机时钟 | 用 `nowdate`（服务端时间）算 |

---

# F. 已知限制

- **单点登录**：完美校园一个账号只允许一个会话。在这里登录会让官方 App 下线，反之亦然。
- **接口非公开**：全部来自官方 H5 与 App 的逆向还原，官方随时可能改参数或加校验，届时需要重新对一遍。
- **只在一所学校实测**：`customerCode` / `customerid` / `outid` 不是写死的，由登录返回；但浴室命名、扣费单位、限额档位可能因校而异。
- **不要高频请求**：会话与设备会被风控，实测高频调用会触发保护。本项目的刷新间隔是 3 分钟，另有 30 秒防抖。
- 本文档只描述协议，**不含任何账号、票据或个人信息的真实值**。
