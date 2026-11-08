"""打包后自检：确认 APK 里没有个人信息 / 登录凭证。

用法：
    python tools/audit_apk.py [apk路径]
    python tools/audit_apk.py --config ../web-app/config.json   # 用真实账号值做比对

检查项：
  1) 手机号、UUID/session、32 位十六进制串
  2) 学校相关字样（大学 / 学院 / 校区）
  3) 可选：配置里的真实值（姓名、学号、学校、token）逐字比对
  4) 权限、组件数量、allowBackup
"""
import json
import os
import re
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
DEFAULT_APK = os.path.join(ROOT, 'build', 'bath.apk')

PHONE = re.compile(r'1[3-9]\d{9}')
UUID = re.compile(r'[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}', re.I)
HEX32 = re.compile(r'\b[0-9a-f]{32}\b', re.I)
SCHOOL = re.compile(r'[\u4e00-\u9fff]{2,10}(?:大学|学院|校区)')

args = sys.argv[1:]
apk = DEFAULT_APK
cfg_path = None
i = 0
while i < len(args):
    if args[i] == '--config' and i + 1 < len(args):
        cfg_path = args[i + 1]
        i += 2
    else:
        apk = args[i]
        i += 1

if not os.path.exists(apk):
    print('找不到 APK:', apk)
    sys.exit(2)

secrets = {}
if cfg_path and os.path.exists(cfg_path):
    try:
        cfg = json.load(open(cfg_path, encoding='utf-8'))
        for k in ('name', 'outid', 'token', 'school', 'customerid', 'customerCode', 'mobile'):
            v = str(cfg.get(k) or '').strip()
            if len(v) >= 4:
                secrets[k] = v
    except Exception as e:
        print('读取配置失败:', e)

z = zipfile.ZipFile(apk)
names = z.namelist()
print('APK: %s (%.1f KB, %d 个条目)' % (apk, os.path.getsize(apk) / 1024, len(names)))
for n in sorted(names):
    print('   ', n)
print()

problems = []

for n in names:
    try:
        raw = z.read(n)
    except Exception:
        continue
    # dex 用 MUTF-8，另一份 utf-16 视图用于兜底
    texts = [raw.decode('utf-8', 'ignore')]
    if n.endswith('.dex'):
        texts.append(raw.decode('utf-16-le', 'ignore'))
    for text in texts:
        for label, pat in (('手机号', PHONE), ('UUID/session', UUID),
                           ('32位十六进制', HEX32), ('学校名', SCHOOL)):
            for m in set(pat.findall(text)):
                problems.append((n, label, m[:60]))
        for k, v in secrets.items():
            if v in text:
                problems.append((n, '配置值:' + k, v[:60]))

print('=== 扫描结果 ===')
if problems:
    for n, label, val in sorted(set(problems)):
        print('  !! %-24s %-14s %s' % (n, label, val))
else:
    print('  PASS：未发现手机号 / 会话凭证 / 学校名等个人信息')

if secrets:
    print('  （已用 %d 个真实值做逐字比对）' % len(secrets))
else:
    print('  （未提供 --config，仅做模式检测；建议补一次真实值比对）')

# 清单层面的检查
try:
    import logging
    logging.disable(logging.CRITICAL)
    from androguard.core.apk import APK
    a = APK(apk)
    perms = a.get_permissions() or []
    print()
    print('=== 清单 ===')
    print('  权限   :', ', '.join(perms) if perms else '（无）')
    print('  Activity:', len(a.get_activities() or []),
          '| Service:', len(a.get_services() or []),
          '| Receiver:', len(a.get_receivers() or []),
          '| Provider:', len(a.get_providers() or []))
    man = a.get_android_manifest_axml().get_xml().decode('utf-8', 'ignore')
    print('  allowBackup:', 'true ⚠' if 'allowBackup="true"' in man else 'false ✓')
    if 'allowBackup="true"' in man:
        problems.append(('AndroidManifest.xml', 'allowBackup', 'true'))
except ImportError:
    print('\n（未安装 androguard，跳过清单检查：pip install androguard）')
except Exception as e:
    print('\n清单解析失败:', e)

print()
print('结论：', 'FAIL' if problems else 'PASS')
sys.exit(1 if problems else 0)
