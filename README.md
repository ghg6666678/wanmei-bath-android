# 智水 · Android 版

完美校园「智慧用水 → 洗澡」的精简客户端。一个图标，打开就是洗澡页：无开屏广告、无首页瀑布流、不需要 root、不需要原版 App。

## 功能

- 手机号 + 短信验证码登录（登录后不再依赖原版 App）
- 余额、当前预约（验证码 + 当日有效的失效倒计时）
- 浴室列表按宿舍号排序；可星标一个宿舍楼，列表只显示该楼浴室（A/B 区合并为同一栋），需要时也能临时切回「全部宿舍楼」
- 一键取码 / 取消预约
- 消费限时、限额设置：滑块 + 文本框联动；金额按浴室实际扣费的 0.05 元一档递增；保存后自动取消已有预约（订单带取码时的数值，不重新取码不生效）
- 最近 6 个月用水记录
- 深色模式自适应

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
- 每次请求带三个头：`uuid`、`timestamp`（毫秒）、`encrypt`（AES-ECB-PKCS7 签名，密钥取自 H5 前端）
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

## 参考实现

登录协议（RSA 换密钥 → 3DES 加密提交 → `campusSign` 签名）不是猜出来的，参考了以下公开实现：

- **[ReaJason/17wanxiaoCheckin](https://github.com/ReaJason/17wanxiaoCheckin)** · MIT · 已归档
  其 `login/` 目录（`campus.py`、`des_3.py`、`rsa_encrypt.py`、`sha1.py`）完整描述了登录握手过程，本项目的 Java 实现按这套流程独立重写。
- **[fuqiuluo/wanxiao-campus](https://github.com/fuqiuluo/wanxiao-campus)** · Rust · 未声明许可证
  用于交叉验证较新版本的登录流程与请求加密细节。

用水业务协议（`entrance`、取码、限时限额、用水记录）来自官方 H5 前端 JavaScript 的协议还原，没有对应的开源项目。

若本仓库中存在源自上述项目的代码片段，其版权归原作者所有，按 MIT 许可使用。

## 免责声明

仅供个人学习与自用，请使用自己的账号。因使用本工具产生的一切后果由使用者自行承担。
