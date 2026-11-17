/* 站点配置：发版时只改这一个文件。
   index.html 里的版本号、体积、下载地址都从这里读，避免页面上手写导致不一致。 */
window.SITE = {
  name: '约澡',
  repo: 'ghg6666678/wanmei-bath-android',
  latest: {
    version: '1.2.0',
    versionCode: 59,
    sizeBytes: 127891,
    date: '2026-11-18',
    apkName: 'yuezao-1.2.0.apk',
    tag: 'v1.2.0',
    /* 本站直下：构建时把 APK 复制到 site/ 根目录，下载按钮直接用相对路径。
       对已打开本站的人（已拿到分享码 cookie）是最快的一条路。 */
    localPath: 'yuezao-1.2.0.apk'
  },
  /* 政策版本不在这里：它只有一份，在 site/policy.json 的 ver/date 里。
     官网 privacy-android.html 与应用内文本都由 tools/gen_policy.py 从那里生成，
     这里再存一遍就等于给自己留一个「改了这处忘那处」的口子。 */
  contact: 'https://github.com/ghg6666678/wanmei-bath-android/issues'
};
