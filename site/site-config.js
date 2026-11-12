/* 站点配置：发版时只改这一个文件。
   index.html 里的版本号、体积、下载地址都从这里读，避免页面上手写导致不一致。 */
window.SITE = {
  name: '智水',
  repo: 'ghg6666678/wanmei-bath-android',
  latest: {
    version: '1.1.4.5',
    versionCode: 55,
    sizeBytes: 103315,
    date: '2026-11-12',
    apkName: 'zhishui-1.1.4.5.apk',
    tag: 'v1.1.4.5',
    /* 本站直下：构建时把 APK 复制到 site/ 根目录，下载按钮直接用相对路径。
       对已打开本站的人（已拿到分享码 cookie）是最快的一条路。 */
    localPath: 'zhishui-1.1.4.5.apk'
  },
  /* 政策版本：官网与应用内是同一份条文，改一次两边一起 +1
     （应用侧对应 assets/index.html 的 DISC_VER）。当前都是 v2。 */
  policyVersion: '2',
  policyDate: '2026-11-12',
  contact: 'https://github.com/ghg6666678/wanmei-bath-android/issues'
};
