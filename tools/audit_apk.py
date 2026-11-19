"""打包后自检：确认 APK 里没有个人信息 / 登录凭证。

用法：
    python tools/audit_apk.py [apk路径]
    python tools/audit_apk.py --config ../web-app/config.json   # 用真实账号值做比对
    python tools/audit_apk.py --expected-cert <sha256>          # 换正式证书时同步更新

检查项：
  1) 手机号、UUID/session、32 位十六进制串
  2) 学校相关字样（大学 / 学院 / 校区）
  3) 可选：配置里的真实值（姓名、学号、学校、token）逐字比对
  4) 权限、组件数量、allowBackup
  5) 签名证书指纹（默认比对正式证书 3b5ce266…，防止静默换签，见 docs/DEVELOPMENT.md 12.42）
"""
import json
import os
import re
import subprocess
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
DEFAULT_APK = os.path.join(ROOT, 'build', 'bath.apk')

# 正式签名证书的 SHA-256（2026-11-19 定版，见 docs/DEVELOPMENT.md 12.42）。
# 换证书时：改这里（或用 --expected-cert 覆盖），audit 不过不许发版。
EXPECTED_CERT_SHA256 = '3b5ce266ca4a7c8a66646ca962d90da508fb8af15378213f9e04762e556fa2d4'

PHONE = re.compile(r'1[3-9]\d{9}')
UUID = re.compile(r'[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}', re.I)
HEX32 = re.compile(r'\b[0-9a-f]{32}\b', re.I)
SCHOOL = re.compile(r'[\u4e00-\u9fff]{2,10}(?:大学|学院|校区)')

args = sys.argv[1:]
apk = DEFAULT_APK
cfg_path = None
expected_cert = None
i = 0
while i < len(args):
    if args[i] == '--config' and i + 1 < len(args):
        cfg_path = args[i + 1]
        i += 2
    elif args[i] == '--expected-cert' and i + 1 < len(args):
        expected_cert = args[i + 1].strip().lower()
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


# 签名证书指纹（12.42 换签事故后新增：从产物层面兜住「静默换签」）
def signing_cert_sha256(path):
    """返回签名证书 SHA-256（小写十六进制）；取不到返回 None。

    apksigner 优先（环境变量 APKSIGNER，或 ANDROID_BUILD_TOOLS 目录下的
    apksigner(.bat)）；没有再用 androguard。
    """
    exe = os.environ.get('APKSIGNER', '')
    if not exe:
        bt = os.environ.get('ANDROID_BUILD_TOOLS', '')
        if bt:
            cand = os.path.join(bt, 'apksigner.bat' if os.name == 'nt' else 'apksigner')
            if os.path.exists(cand):
                exe = cand
    if exe:
        try:
            r = subprocess.run([exe, 'verify', '--print-certs', path],
                               capture_output=True, text=True, timeout=120)
            m = re.search(r'SHA-256 digest:\s*([0-9a-fA-F]{64})', r.stdout or '')
            if m:
                return m.group(1).lower()
        except Exception:
            pass
    try:
        from androguard.core.apk import APK
        from hashlib import sha256
        a = APK(path)
        ders = list(a.get_certificates_der_v3() or []) + list(a.get_certificates_der_v2() or [])
        if ders:
            return sha256(ders[0]).hexdigest()
    except Exception:
        pass
    return None


print()
print('=== 签名 ===')
cert = signing_cert_sha256(apk)
if cert is None:
    print('  ?? 未能取得签名证书指纹（无 apksigner / androguard），跳过比对——发版前务必人工复核')
else:
    expected = (expected_cert or EXPECTED_CERT_SHA256).lower()
    if cert == expected:
        print('  PASS：签名证书指纹匹配（%s…）' % cert[:16])
    else:
        print('  !! 签名证书指纹不匹配（静默换签？见 docs/DEVELOPMENT.md 12.42）')
        print('     期望: %s' % expected)
        print('     实际: %s' % cert)
        problems.append(('签名', '证书指纹', cert[:32] + '…'))

print()
print('结论：', 'FAIL' if problems else 'PASS')
sys.exit(1 if problems else 0)
