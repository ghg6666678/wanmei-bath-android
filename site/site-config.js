/* 站点配置：发版时只改这一个文件。
   index.html 里的版本号、体积、下载地址都从这里读，避免页面上手写导致不一致。 */
window.SITE = {
  name: '约澡',
  repo: 'ghg6666678/wanmei-bath-android',
  /* 国内直下：国内服务器（WorkBuddy 托管，应用内更新镜像 #1 的同一通道）上的安装包，
     国内网络不绕出境、通常最快。链接必须带分享码，整条转发给别人也能打开。
     域名或分享码一旦轮换，这两项要和安卓客户端 Update.java 的
     SITE_CN / SITE_CN_QUERY 一起改，否则按钮 403。
     分享码随本文件公开在官网页面里：它本来就写在客户端 APK 中，不作密钥用，只挡随手扫描。 */
  cnBase: 'https://zhishui-site.app.workbuddy.host',
  cnQuery: '?sharecode=KLDDLXOQRD',
  latest: {
    version: '1.2.0',
    versionCode: 59,
    sizeBytes: 127891,
    date: '2026-11-18',
    apkName: 'yuezao-1.2.0.apk',
    tag: 'v1.2.0',
    /* 本站直下：构建时把 APK 复制到 site/ 根目录，下载按钮直接用相对路径。
       与页面同一域名（yuezao.org，公开直链），作国内直下的备用。 */
    localPath: 'yuezao-1.2.0.apk'
  },
  /* 政策版本不在这里：它只有一份，在 site/policy.json 的 ver/date 里。
     官网 privacy-android.html 与应用内文本都由 tools/gen_policy.py 从那里生成，
     这里再存一遍就等于给自己留一个「改了这处忘那处」的口子。 */
  contact: 'https://github.com/ghg6666678/wanmei-bath-android/issues'
};
