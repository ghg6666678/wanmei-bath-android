# 智水 · Android 版

完美校园「智慧用水 → 洗澡」的精简客户端。一个图标，打开就是洗澡页：无开屏广告、无首页瀑布流、不需要 root、不需要原版 App。

> **关于本项目**：这是一个 AI coding 项目。作者此前没有相关开发经验，从界面、协议到构建、审计与文档，全程都是通过与 AI agent 用自然语言交流完成的。

## 功能

- 手机号 + 短信验证码登录（登录后不再依赖原版 App）
- 余额、当前预约（验证码 + 当日有效的失效倒计时）
- 浴室列表按宿舍号排序；可星标自己住的宿舍楼，列表只显示该楼浴室（A/B 区合并为同一栋，某个区设备坏了可去同楼另一区）
- 一键取码 / 取消预约
- 消费限时、限额设置：滑块 + 文本框联动；金额按浴室实际扣费的 0.05 元一档递增；保存后自动取消已有预约（订单带取码时的数值，不重新取码不生效）
- 最近 6 个月用水记录
- 深色模式自适应
- 下拉刷新：在页面顶部下拉即可手动更新余额与预约

## 目录结构

```
AndroidManifest.xml
src/com/wanmei/bath/
    MainActivity.java   WebView 容器 + JS 桥 + 页面路由
    Api.java            智慧用水协议（AES-ECB 签名、entrance、取码、记录、限时限额）
    Login.java          登录协议（RSA 换密钥 + 3DES + campusSign）
    Store.java          SharedPreferences 读写
assets/index.html       界面（HTML + JS，走原生桥调 Java）
res/                    主题、颜色、图标
tools/
    build.sh            手工构建 APK（不依赖 Gradle）
    audit_apk.py        打包后自检：确认不含个人信息
```

界面就是 `assets/index.html`，改样式/文案直接改它，然后重新构建。里面的请求统一走 `Android.apiAsync(...)` 桥到 Java 层。

> Android 版和网页版的界面是各自维护的（网页版走 `fetch`，这里走原生桥），改动需要分别在两端进行。

## 构建

需要：JDK 8+、Android SDK（build-tools + platform）、Python 3、bash（Windows 用 Git Bash）。

```bash
# 1) 指定 SDK（三种方式任选，具体看你的目录结构）
export ANDROID_SDK=/path/to/android-sdk
# 或分别指定
export ANDROID_BUILD_TOOLS=/path/to/build-tools/34.0.0
export ANDROID_PLATFORM=/path/to/platforms/android-34

# 2) 构建（产物 build/bath.apk）
bash tools/build.sh

# 3) 自检（可选，建议每次都跑）
python tools/audit_apk.py --config ../web-app/config.json
```

首次构建会自动生成签名证书 `keystore/bath.keystore`（已在 .gitignore）。密码默认 `bath123`，可用 `KEYSTORE_PASS` 覆盖。**这个证书不要提交**，丢了就没法覆盖安装。

## 实现要点

- **界面**是本地 HTML（`assets/index.html`），WebView 里跑，通过 `@JavascriptInterface` 调原生网络层
- **网络与协议全在 Java**：不依赖任何第三方库，APK 约 45 KB
- **登录拿到的 session 就是用水接口要的 token**：先 `exchangeSecretkey` 换密钥得到 session，再用同一 session 提交短信验证码，服务端把它升格为账号会话
- 每次请求带三个头：`uuid`、`timestamp`（毫秒）、`encrypt`（把 `uuid-时间戳` 用 AES-ECB + PKCS7 加密后 base64）
- 签名密钥是官方 H5 前端 JS 里的 **16 字节明文常量**（标准 AES-128，见 `SIGN_KEY`）；Java 的 `AES/ECB/PKCS5Padding` 与前端 PKCS7 填充等价，两端都用各自语言的原生实现，不需要 Node.js
- 取码时间用**服务端返回的时间**计算，不用本机时钟——原版 App 的「预约时间不能早于当前时间」报错，根因就是它拿本地时间当默认值又拿本地时间做比较
- 权限只有 `INTERNET` 和 `ACCESS_NETWORK_STATE`，无 Service / Receiver / Provider，`allowBackup=false`

## 隐私

- 安装包里**不含任何个人信息**：只有界面、协议代码和图标
- 登录后 session、姓名、学号、学校存在应用私有目录的 SharedPreferences 里，其他应用和 adb 都读不到
- 不采集设备信息、不埋点、不联网上报

## 已知限制

- **单点登录**：这里登录会让手机上的原版 App 下线（错误码 `100600`），反之亦然
- **充值只给指引**：原版走微信小程序支付，微信对 `appid + 包名 + 签名` 三重校验，第三方调不起，所以页面上只提示「微信搜索小程序：完美校园」
- 只有 Android 端；iOS 需要另做（协议层可复用）
- **跨校适配**：目前只在一所学校实测，其他学校的命名与扣费规则可能不同，见「适用范围与其他学校」

## 适用范围与其他学校

> **目前只在一所使用「完美校园 智慧用水」的学校实测通过。** 其他学校欢迎试用并反馈，但不保证开箱可用。

协议层基本是通用的：三个服务域名（`vwater.59wanmei.com`、`xqh5.17wanxiao.com`、`app.17wanxiao.com`）都是完美校园的公共服务，签名密钥是官方公共 H5 前端里的固定常量（16 字节、标准 AES-128）；`customerCode`、`customerid`、`outid` 等学校/账号标识**不是写死的**，由短信登录返回，并在 `entrance()` 时被服务端下发的参数覆盖。所以别校用户用自己的账号登录，主流程（登录、余额、取码、取消、记录）大概率能直接跑。

但以下三处是按本校实际情况固化的，换学校可能需要调整：

| 项目 | 目前的假设 | 别校可能的表现 |
| --- | --- | --- |
| 浴室命名 | 「一号宿舍a区 / b区」这类名称；`dormNo()` 按「X号」中文或阿拉伯数字排序，`dormKey()` 剥掉末尾 `a区/b区` 合并楼栋 | 「1号楼」「东区浴室」「A栋」等命名会让排序失效、星标把每间浴室当成一栋楼（不会崩溃） |
| 扣费与档位 | 金额以 **0.05 元**为步进、范围 1.00–20.00 元；限时 1–120 分钟；原生端按「分」校验 `% 5 == 0` | 扣费单位若是 0.01 / 0.1 元，或官方档位上限不同，保存设置可能被拒或金额不对 |
| 固定业务参数 | `limittype=1`、`verificationtype=2`、`telephoneInfo=12`；验证码按「当日有效」倒计时 | 客户配置不同可能不生效，需按官方 H5 的实际值调整 |

**反馈请走 [跨校适配反馈模板](https://github.com/ghg6666678/wanmei-bath-android/issues/new/choose)**，按模板提供学校/系统名称、几个浴室名称样例、扣费单位和具体表现。积累两所以上学校的真实数据后，再把这些项外置成配置，避免凭猜测改代码。

> issue 是公开的：**不要粘贴手机号、学号、姓名、短信验证码、token/session 或完整的登录返回内容**。浴室名称可以贴，日志和截图请先打码。

## 参考实现

登录协议（RSA 换密钥 → 3DES 加密提交 → `campusSign` 签名）不是猜出来的，参考了以下公开实现：

- **[ReaJason/17wanxiaoCheckin](https://github.com/ReaJason/17wanxiaoCheckin)** · MIT · 已归档
  其 `login/` 目录（`campus.py`、`des_3.py`、`rsa_encrypt.py`、`sha1.py`）完整描述了登录握手过程，本项目的 Java 实现按这套流程独立重写。
- **[fuqiuluo/wanxiao-campus](https://github.com/fuqiuluo/wanxiao-campus)** · Rust · 未声明许可证
  用于交叉验证较新版本的登录流程与请求加密细节。

用水业务协议（`entrance`、取码、限时限额、用水记录）来自官方 H5 前端 JavaScript 的协议还原，没有对应的开源项目。

若本仓库中存在源自上述项目的代码片段，其版权归原作者所有，按 MIT 许可使用。

## 开发复盘

从最初的构想到 1.0.2 的完整流程、关键决策和踩过的坑，见 [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md)。这是**公开版**，已剥离账号凭据、个人信息、本机环境细节，以及客户端代码保护相关的可复现步骤；含完整细节的原始复盘不对外发布。

## 免责声明

仅供个人学习与自用，请使用自己的账号。因使用本工具产生的一切后果由使用者自行承担。
