# 智水 · Android 版

完美校园「智慧用水 → 洗澡」的精简客户端。官方入口里「淋浴」和「洗澡」是两个页面，加上开屏广告和几层菜单，去洗个澡要戳七八下，切回后台还会重播一遍广告。这个项目只做「洗澡」这一件事——一个图标，打开就是洗澡页：无开屏广告、无首页瀑布流，不需要 root，也不需要原版 App。

> 这是一个 AI coding 项目：作者此前没有相关开发经验，从界面、协议到构建与文档，全程通过与 AI agent 用自然语言交流完成。

**觉得好用？欢迎给仓库点个 [Star](https://github.com/ghg6666678/wanmei-bath-android)。** 如果它帮你省掉了洗澡前那七八次点击，一个 Star 就是最实在的支持，也能让更多被开屏广告折磨的人找到它。另外，[官网](https://zhishui-site.app.workbuddy.host/?sharecode=KLDDLXOQRD#support) 放了一个微信赞赏码：**纯自愿、无对价，不给也照样用，与任何功能无关**。

## 下载

最新版 **1.1.5**（versionCode 58，107,411 B）。GitHub 直链在很多移动网络下打不开，建议用 CDN：

```
https://cdn.jsdelivr.net/gh/ghg6666678/wanmei-bath-android@v1.1.5/release/zhishui-1.1.5.apk
https://gcore.jsdelivr.net/gh/ghg6666678/wanmei-bath-android@v1.1.5/release/zhishui-1.1.5.apk
```

- 也可从 [官网](https://zhishui-site.app.workbuddy.host/?sharecode=KLDDLXOQRD) 直接下载；全部历史版本见 [Releases](https://github.com/ghg6666678/wanmei-bath-android/releases)
- SHA-256 `fd8499b4a0e893cd3c97a8b9c6877693329642118307f2bc4aaf2c1ece21e244`（GitHub 直链与官网均为这一版）；与历史版本同一张证书，可直接覆盖安装，登录态不丢
- 注：jsDelivr 对 `@v1.1.5` 路径的缓存暂时清不掉，可能给到合并前的等价构建（SHA 以 `415b2e44` 开头）；同为 1.1.5 / 58、功能一致，以 GitHub 直链与官网为准
- **1.0.3 已撤回，请勿使用**：其「检查更新」的两个镜像含被劫持域名，会跳转不良网站。已安装的请直接装最新版，别点「镜像 1 / 镜像 2」

## 功能

- 手机号 + 短信验证码登录（登录后不再依赖原版 App）
- 余额、当前预约（验证码 + 当日有效的失效倒计时）
- 浴室列表按宿舍号排序；可星标自己住的楼、只显示该楼浴室（A/B 区合并）——原版只有一个搜索框
- 一键预约 / 取消预约；消费限时、限额设置（保存后自动取消旧预约）
- 最近 6 个月用水记录、下拉刷新、深色模式自适应
- 预约用**服务端返回的时间**提交，避开官方「不能早于当前时间」的报错
- **检查更新**：GitHub 官方接口 → CDN 清单 → 官网清单 → APK 内置清单 → 上次缓存，多通道兜底；下载源按实测延迟挑最快，GitHub 打不开也能拿到版本、地址和 SHA-256

## 隐私与边界

- 安装包不含任何个人信息；登录凭据、姓名、学号、学校只存在应用私有目录，其他应用和 adb 读不到
- 应用不采集设备信息、不埋点、不联网上报；权限只有 `INTERNET` 与 `ACCESS_NETWORK_STATE`，无 Service / Receiver / Provider
- 版本检查与安装包下载会向 GitHub、jsDelivr 和官网暴露 IP、User-Agent 与请求路径；完整说明见 [隐私政策](https://zhishui-site.app.workbuddy.host/privacy.html?sharecode=KLDDLXOQRD)
- 仅供个人学习与自用，请使用自己的账号；不代登录、不批量操作，因使用产生的一切后果由使用者自行承担
- 不收费、无内购、无广告、无第三方统计或 SDK；官网与仓库提供纯自愿、无对价的[微信赞赏码](https://zhishui-site.app.workbuddy.host/?sharecode=KLDDLXOQRD#support)，不影响任何功能。未上架任何应用商店，只在 Releases 与官网发布。源码全部公开，可自行核对

## 已知限制

- **单点登录**：本应用与原版 App 会互相挤下线（错误码 `100600`）
- **充值只给指引**：原版走微信小程序支付，微信校验 `appid + 包名 + 签名`，第三方调不起
- **只有 Android 端**；iOS 需另做（协议层可复用）
- **跨校适配**：目前只在一所学校实测。浴室命名、扣费档位（0.05 元步进）、固定业务参数按本校固化，别校可能需要调整；欢迎按 [适配反馈模板](https://github.com/ghg6666678/wanmei-bath-android/issues/new/choose) 提供信息

> issue 是公开的：**不要粘贴**手机号、学号、姓名、短信验证码、token/session 或完整的登录返回内容；日志和截图请先打码。

## 自己构建

需要 JDK 8+、Android SDK（build-tools + platform）、Python 3、bash（Windows 用 Git Bash）。界面就是 `assets/index.html`，网络与协议全在 `src/com/wanmei/bath/` 的 Java 里，不依赖任何第三方库或 Node.js。

```bash
export ANDROID_SDK=/path/to/android-sdk
bash tools/build.sh                                          # 产物 build/bath.apk
python tools/audit_apk.py --config ../web-app/config.json      # 可选：自检不含个人信息
```

首次构建会生成签名证书 `keystore/bath.keystore`（已 gitignore，默认密码 `bath123`，可用 `KEYSTORE_PASS` 覆盖）。**证书不要提交、也不要弄丢**，否则无法覆盖安装。

> Android 版与网页版界面各自维护（一端走 `fetch`、一端走原生桥），改动需分别在两端进行。

## 更多文档

- [开发复盘](docs/DEVELOPMENT.md)：从构想到当前版本的完整流程、关键决策与踩过的坑（含官网、云服务与许可变更）；协议细节（请求地址、参数、签名规则）不展开，第三方服务条款通常禁止再发布这类资料
- [接口说明](docs/API.md) · [消费记录的分页与缓存策略](docs/RECORDS_CACHE.md) · [贡献说明](CONTRIBUTING.md)
- [更新日志](https://zhishui-site.app.workbuddy.host/changelog.html?sharecode=KLDDLXOQRD) · [隐私政策](https://zhishui-site.app.workbuddy.host/privacy.html?sharecode=KLDDLXOQRD) · [问题反馈](https://zhishui-site.app.workbuddy.host/feedback.html?sharecode=KLDDLXOQRD)（官网，不用 GitHub 账号也能留言）

## 参考实现

- [ReaJason/17wanxiaoCheckin](https://github.com/ReaJason/17wanxiaoCheckin)（MIT，已归档）：登录握手流程，本项目的 Java 实现按此独立重写
- [fuqiuluo/wanxiao-campus](https://github.com/fuqiuluo/wanxiao-campus)（Rust，未声明许可证）：交叉验证较新版本的登录流程与请求加密

用水业务流程来自对前端行为的分析，无对应开源项目。若仓库中存在源自上述项目的代码片段，其版权归原作者所有，按 MIT 许可使用。

## 许可

**本仓库不是 MIT 开源项目**，使用前请读 [LICENSE](LICENSE)。允许阅读、学习、自用、**自己修改**（包括外观与配色）与集成进自己的校园工具，也允许放友情链接互相推荐；底线是**不得把付费变成使用门槛、不得插入严重影响使用体验的广告**，以及不得公开发布修改后的衍生版本、不得替他人登录。想贡献代码请看 [CONTRIBUTING.md](CONTRIBUTING.md)。

想集成到自己的校园工具？只要**每位使用者登录自己的账号、凭据不集中在你手上**，就属于许可明确允许的情形，不必申请；仅做不留存的跨域转发同样可以（转发 ≠ 代登录）。若需要服务器代登录，或涉及付费门槛、影响体验的广告，欢迎开 Issue 谈单独授权：说清项目是什么、大概多少人用、账号怎么来的即可。
