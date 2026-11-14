#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""隐私政策：两个源、五处产物。

    site/policy.json          Android 客户端政策（唯一权威，应用热更新拉它）
    site/policy-web.json      网页端政策（唯一权威，网页端应用内模板与它校验）

        ├─ site/privacy.html              分流入口：选 Android / 网页端
        ├─ site/privacy-android.html      Android 政策正文
        ├─ site/privacy-web.html          网页端政策正文
        ├─ assets/index.html              Android 应用内置兜底 + DISC_VER
        └─ web-app/web/index.html         网页端应用内模板（只校验，不改写）

为什么要两个源而不是一个：Android 与网页端的请求链路根本不同（前者登录直连
完美校园，后者全部经 Cloudflare 边缘转发），政策第 6、8 节本来就该各自成文。
参考同类项目的做法，官网用一个入口页把两份完整政策分开，避免用户误读不属于
自己那一端的条款。但「两份」不能变成「两份各改各的」——所以都由本脚本生成，
入口页的版本号与日期也直接读两个源，不留手写的副本。

用法：
    python tools/gen_policy.py            生成全部产物
    python tools/gen_policy.py --check    只检查，过期返回 1（构建/部署前用）
    python tools/gen_policy.py --dump     打印两份政策的解码正文，便于审阅与 diff

指纹是两份源的组合：任一政策变动，产物都会一起过期，必须重新生成。
算法必须与 web-app/cloudflare/build-assets.mjs 的 policyHash() 逐字节一致。
"""

import hashlib
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SITE = ROOT / "site"
POLICY = SITE / "policy.json"            # Android
POLICY_WEB = SITE / "policy-web.json"    # 网页端
PAGE_INDEX = SITE / "privacy.html"
PAGE_ANDROID = SITE / "privacy-android.html"
PAGE_WEB = SITE / "privacy-web.html"
APP = ROOT / "assets" / "index.html"
WEB_APP = ROOT.parent / "web-app" / "web" / "index.html"

BODY_BEGIN = re.compile(r"<!-- POLICY:BEGIN.*?-->", re.S)
IDX_BEGIN = re.compile(r"<!-- POLICY:INDEX:BEGIN.*?-->", re.S)
BODY_END = "<!-- POLICY:END -->"
IDX_END = "<!-- POLICY:INDEX:END -->"
DISC_RE = re.compile(r"const DISC_VER = '\d+';[^\n]*")


def fingerprint(a, w):
    raw = "\n".join([
        str(a.get("ver", "")), str(a.get("date", "")), str(a.get("applies", "")), str(a.get("html", "")),
        str(w.get("ver", "")), str(w.get("date", "")), str(w.get("applies", "")), str(w.get("html", "")),
    ])
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()[:12]


def mark(fp, kind="BODY"):
    if kind == "INDEX":
        return "<!-- POLICY:INDEX:BEGIN 本区由 tools/gen_policy.py 生成，勿手改 sha256=%s -->" % fp
    return "<!-- POLICY:BEGIN 本区由 tools/gen_policy.py 生成，勿手改 sha256=%s -->" % fp


def plain(s):
    return re.sub(r"\s+", " ", re.sub(r"<[^>]+>", "", s)).strip()


def indent(text, n):
    pad = " " * n
    return "\n".join((pad + ln) if ln.strip() else ln for ln in text.split("\n"))


# ---------------------------------------------------------------- 正文转换


def convert(html):
    """政策正文 → (引言, 目录, 章节)。

    两份源结构略有差别：Android 正文外层有 <div class="policy">、标题是纯
    <h4>；网页端正文就是应用内 <template> 的内容、标题带内联 style。带属性的
    h4 正则把两种都吃掉，其余转换一致。
    """
    body = html.strip()
    m = re.match(r'<div class="policy">\s*(.*?)\s*</div>\s*$', body, re.S)
    if m:
        body = m.group(1)

    # 顶部版本行由字段生成，先摘掉正文里的手写副本（只摘第一处）
    body = re.sub(r'^\s*<p class="pmeta">.*?</p>\s*', "", body, count=1, flags=re.S)
    body = body.replace('class="warn-red"', 'class="red"')

    toc = []

    def heading(mt):
        text = mt.group(1)
        num = re.match(r"(\d+)\s*[.、]", plain(text))
        hid = "s" + (num.group(1) if num else str(len(toc) + 1))
        toc.append((hid, plain(text)))
        return '<h2 id="%s">%s</h2>' % (hid, text)

    body = re.sub(r"<h4[^>]*>(.*?)</h4>", heading, body, flags=re.S)

    idx = body.find("<h2 ")
    head, sections = (body[:idx], body[idx:]) if idx >= 0 else ("", body)

    if toc:
        items = "\n".join('      <li><a href="#%s">%s</a></li>' % (h, t) for h, t in toc)
        nav = '<div class="toc">\n    <strong>目录</strong>\n    <ol>\n%s\n    </ol>\n  </div>' % items
    else:
        nav = ""

    return head.strip(), nav, sections.strip()


def render_site(d, fp):
    """生成一份政策正文页的标记区。"""
    parts = [
        '<p class="pmeta">政策版本 <strong>v%s</strong> · 生效日期 <strong>%s</strong> · 适用版本 <strong>%s</strong></p>'
        % (d.get("ver", ""), d.get("date", ""), d.get("applies", ""))
    ]
    if str(d.get("note") or "").strip():
        parts.append('<p class="pmeta">%s</p>' % d["note"].strip())

    head, nav, sections = convert(str(d.get("html") or ""))
    for chunk in (head, nav, sections):
        if chunk:
            parts.append(chunk)

    frag = "\n\n  ".join(parts)
    frag = re.sub(r"(?m)^ {4}", "  ", frag)
    return mark(fp) + "\n  " + frag.strip() + "\n  " + BODY_END


def render_index(a, w, fp):
    """入口页：两张卡片，版本号 / 日期全部读源，不手写。"""
    cards = '''<div class="policy-cards">
    <a class="policy-card" href="privacy-android.html">
      <b>Android 客户端</b>
      <span>%s · 当前版本。登录与取码请求由设备直连完美校园，不经过本项目的服务器；官网、版本清单、安装包与政策更新会经 Cloudflare 边缘分发。</span>
      <em>政策 v%s · %s 生效 →</em>
    </a>
    <a class="policy-card" href="privacy-web.html">
      <b>网页端</b>
      <span>%s · 页面与所有业务请求都经 Cloudflare 边缘转发到境内上游，登录与业务数据存在跨境传输，详见该政策第 8 节。</span>
      <em>政策 v%s · %s 生效 →</em>
    </a>
  </div>''' % (
        a.get("applies", ""), a.get("ver", ""), a.get("date", ""),
        w.get("applies", ""), w.get("ver", ""), w.get("date", ""),
    )
    return mark(fp, "INDEX") + "\n  " + cards + "\n  " + IDX_END


def render_app(a, fp):
    """Android 应用内置兜底：与远程 policy.json 的 html 完全同构。"""
    body = re.sub(r"(?m)^ {4}", "  ", str(a.get("html") or "").strip())
    return mark(fp) + "\n" + indent(body, 2) + "\n  " + BODY_END


# ---------------------------------------------------------------- 一致性


def android_problems(d):
    """Android 侧自洽性：字段与正文手写副本、应用清洗白名单都要对得上。

    应用侧会清洗远程 HTML，正文里出现 script/style/id/自定义 class 会静默
    丢掉——官网上正常、手机上却没有，这种错最难发现。
    """
    out = []
    html = str(d.get("html") or "")
    m = re.search(r'<p class="pmeta">(.*?)</p>', html, re.S)
    if not m:
        out.append('policy.json: html 开头缺少版本行 <p class="pmeta">…</p>')
        return out
    line = plain(m.group(1))
    for label, val in (("版本", "v" + str(d.get("ver", ""))), ("生效日期", str(d.get("date", "")))):
        if val and val not in line:
            out.append("policy.json: 版本行与字段不一致——%s 应为 %s，正文里是「%s」" % (label, val, line))
    if re.search(r"<script|<style|style=|\sid=", html, re.I):
        out.append("policy.json: html 里出现 script/style/style=/id，应用侧清洗会丢掉")
    bad = sorted({c for x in re.findall(r'class="([^"]+)"', html) for c in x.split()
                  if c not in ("policy", "pmeta", "warn-red")})
    if bad:
        out.append("policy.json: html 用了应用侧不认的样式类 %s" % bad)
    return out


def web_app_problems(w):
    """网页端：应用内 <template> 必须与 policy-web.json 逐字一致，POLICY_VER 同步。

    网页端不走 sanitizePolicy（文本本来就内置在页面里），所以不能套 Android
    那套白名单；但「官网展示一份、应用内弹另一份」绝不能出现，故直接比对。
    """
    out = []
    if not WEB_APP.exists():
        return []
    src = WEB_APP.read_bytes().decode("utf-8").replace("\r\n", "\n")
    m = re.search(r'<template id="policyTpl">\n(.*?)\n</template>', src, re.S)
    if not m:
        out.append('web-app/web/index.html: 找不到 <template id="policyTpl">')
        return out
    live = m.group(1).strip("\n")
    want = str(w.get("html") or "").strip("\n")
    if live != want:
        out.append("web-app/web/index.html: policyTpl 正文与 site/policy-web.json 不一致（两边不能各改各的）")
    pv = re.search(r"const POLICY_VER = '(\d+)';", src)
    if not pv or pv.group(1) != str(w.get("ver", "")):
        out.append("web-app/web/index.html: POLICY_VER 应等于 policy-web.json 的 ver=%s" % w.get("ver", ""))
    return out


# ---------------------------------------------------------------- 文件读写


def replace_region(text, new_block, begin_re, end_mark, where):
    begin = begin_re.search(text)
    end = text.find(end_mark)
    if not begin or end < 0 or end < begin.end():
        return None, "%s: 缺少 %s 标记" % (where, end_mark)
    return text[: begin.start()] + new_block + text[end + len(end_mark):], None


def plan(a, w):
    fp = fingerprint(a, w)
    jobs, errors = [], []

    for path, data in ((PAGE_ANDROID, a), (PAGE_WEB, w)):
        text = path.read_text(encoding="utf-8")
        new, err = replace_region(text, render_site(data, fp), BODY_BEGIN, BODY_END, path.name)
        if err:
            errors.append(err)
        else:
            jobs.append((path, new, "正文区"))

    if PAGE_INDEX.exists():
        text = PAGE_INDEX.read_text(encoding="utf-8")
        new, err = replace_region(text, render_index(a, w, fp), IDX_BEGIN, IDX_END, PAGE_INDEX.name)
        if err:
            errors.append(err)
        else:
            jobs.append((PAGE_INDEX, new, "版本选择卡片"))

    # Android 应用内置：template 内的一对标记 + DISC_VER
    app = APP.read_text(encoding="utf-8")
    m = re.search(r'(<template id="policyTpl">)(.*?)(</template>)', app, re.S)
    if not m:
        errors.append('assets/index.html: 找不到 <template id="policyTpl">')
    else:
        inner = m.group(2)
        b = BODY_BEGIN.search(inner)
        e = inner.find(BODY_END)
        head = inner[: b.start()] if b else ""
        tail = inner[e + len(BODY_END):] if e >= 0 else ""
        if not head.strip():
            head = "\n  "
        if not tail.strip():
            tail = "\n"
        inner_new = head + render_app(a, fp) + tail
        new_app = app[: m.start(2)] + inner_new + app[m.end(2):]
        new_app = DISC_RE.sub(
            "const DISC_VER = '%s';   /* 由 tools/gen_policy.py 从 site/policy.json 同步，勿手改 */" % a.get("ver", ""),
            new_app, count=1)
        jobs.append((APP, new_app, "应用内置文本 + DISC_VER"))

    return jobs, errors, fp


def main(argv):
    for p in (POLICY, POLICY_WEB):
        if not p.exists():
            print("找不到 %s" % p, file=sys.stderr)
            return 1
    try:
        a = json.loads(POLICY.read_text(encoding="utf-8"))
        w = json.loads(POLICY_WEB.read_text(encoding="utf-8"))
    except Exception as e:
        print("政策 JSON 解析失败：%s" % e, file=sys.stderr)
        return 1

    if "--dump" in argv:
        print("===== Android (v%s) =====\n%s" % (a.get("ver"), a.get("html", "")))
        print("\n===== 网页端 (v%s) =====\n%s" % (w.get("ver"), w.get("html", "")))
        return 0

    problems = android_problems(a) + web_app_problems(w)
    jobs, errors, fp = plan(a, w)
    stale = [(p, c, what) for p, c, what in jobs if c != p.read_text(encoding="utf-8")]

    if "--check" in argv:
        msgs = problems + errors
        msgs += ["%s 未同步（%s）" % (p.relative_to(ROOT), what) for p, _, what in stale]
        if msgs:
            for m in msgs:
                print("✗ " + m, file=sys.stderr)
            print("\n修：python tools/gen_policy.py", file=sys.stderr)
            return 1
        print("✓ 隐私政策产物一致（Android v%s / 网页端 v%s，sha256=%s）"
              % (a.get("ver"), w.get("ver"), fp))
        return 0

    for p in problems:
        print("! " + p, file=sys.stderr)
    for e in errors:
        print("✗ " + e, file=sys.stderr)

    for path, content, what in stale:
        path.write_text(content, encoding="utf-8")
        print("已更新 %-28s %s" % (path.relative_to(ROOT).as_posix(), what))
    if not stale and not errors:
        print("已是最新（Android v%s / 网页端 v%s，sha256=%s）" % (a.get("ver"), w.get("ver"), fp))

    return 1 if (problems or errors) else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
