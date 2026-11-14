#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""隐私政策：一个源，三处产物。

    site/policy.json                唯一权威（应用热更新拉的就是它）
        ├─ site/privacy.html        官网页面（网页格式：h2 + 锚点 + 目录 + .red）
        ├─ assets/index.html        应用内置兜底（<template id="policyTpl">）
        └─ assets/index.html        DISC_VER = ver

为什么要这样
------------
条文本身就是双方举证的依据，「手机上写 A、官网上写 B」不是文案问题。以前
三处各存一份、再加一个 DISC_VER 常量，改条文要同步四个地方，漏一次就漂移；
而漂移的表现是页面看起来完全正常，没有任何报错。现在改条文只改 policy.json：

    python tools/gen_policy.py            # 生成三处
    python tools/gen_policy.py --check    # 只检查，过期返回 1（构建/部署前用）
    python tools/gen_policy.py --dump     # 打印解码后的正文，便于审阅与 diff

转换规则（应用正文 → 官网正文）
  · 去掉外层 <div class="policy">（网页由 article.doc 承载）
  · <h4> → <h2 id="sN">，编号取自标题里的「N.」，于是 #s15 这类锚点可用
  · 按标题自动生成目录（应用里没有目录）
  · 样式类 warn-red → red（官网样式表叫 red，应用里叫 warn-red）
  · 顶部版本行按 ver/date/applies 重写，不让正文里的手写副本与字段打架

policy.json 字段
    ver      仅数字字符串，递增即触发用户重新同意
    date     生效日期
    applies  适用版本
    summary  一句话，应用弹层里跟在「已更新至 vN（日期生效）：」后面
    note     本版相对上一版的说明，只出现在官网（弹层放不下，也不必）
    html     正文。应用与官网共用，故不能出现 id/script/style/自定义类——
             应用侧的清洗白名单只留 div/p/h4/ul/ol/li/br/strong/b/em/i/span/a，
             属性只有 class(限 policy/pmeta/warn-red) 与 href(http(s)/mailto)。
"""

import hashlib
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SITE = ROOT / "site"
POLICY = SITE / "policy.json"
PAGE = SITE / "privacy.html"
APP = ROOT / "assets" / "index.html"

MARK = "<!-- POLICY:BEGIN 本区由 tools/gen_policy.py 从 site/policy.json 生成，勿手改 sha256=%s -->"
END_MARK = "<!-- POLICY:END -->"
BEGIN_RE = re.compile(r"<!-- POLICY:BEGIN.*?-->", re.S)
DISC_RE = re.compile(r"const DISC_VER = '\d+';[^\n]*")

HELP = """用法：
  python tools/gen_policy.py            生成（site/privacy.html + assets/index.html）
  python tools/gen_policy.py --check    只检查是否过期，过期返回 1
  python tools/gen_policy.py --dump     打印解码后的正文
"""


# ---------------------------------------------------------------- 元信息


def fingerprint(d):
    """正文指纹：字段顺序固定、纯字符串拼接，Node 侧同样的算法能算出同一个值。
    让部署脚本一眼看出「产物是不是从当前 policy.json 生成的」。"""
    raw = "\n".join(
        [str(d.get("ver", "")), str(d.get("date", "")), str(d.get("applies", "")), str(d.get("html", ""))]
    )
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()[:12]


def plain(s):
    return re.sub(r"\s+", " ", re.sub(r"<[^>]+>", "", s)).strip()


def indent(text, n):
    pad = " " * n
    return "\n".join((pad + ln) if ln.strip() else ln for ln in text.split("\n"))


# ---------------------------------------------------------------- 官网正文


def convert(html):
    """应用正文 → (引言, 目录, 章节)。"""
    body = html.strip()
    m = re.match(r'<div class="policy">\s*(.*?)\s*</div>\s*$', body, re.S)
    if m:
        body = m.group(1)

    # 顶部版本行由字段生成，先摘掉（只摘第一处，正文里若另有 pmeta 不动）
    body = re.sub(r'^\s*<p class="pmeta">.*?</p>\s*', "", body, count=1, flags=re.S)
    body = body.replace('class="warn-red"', 'class="red"')

    toc = []

    def heading(mt):
        text = mt.group(1)
        num = re.match(r"(\d+)\s*[.、]", plain(text))
        hid = "s" + (num.group(1) if num else str(len(toc) + 1))
        toc.append((hid, plain(text)))
        return '<h2 id="%s">%s</h2>' % (hid, text)

    body = re.sub(r"<h4>(.*?)</h4>", heading, body, flags=re.S)

    idx = body.find("<h2 ")
    head, sections = (body[:idx], body[idx:]) if idx >= 0 else ("", body)

    if toc:
        items = "\n".join('      <li><a href="#%s">%s</a></li>' % (h, t) for h, t in toc)
        nav = '<div class="toc">\n    <strong>目录</strong>\n    <ol>\n%s\n    </ol>\n  </div>' % items
    else:
        nav = ""

    return head.strip(), nav, sections.strip()


def render_site(d):
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
    # 正文缩进基于 policy.json 里的 4 空格，网页里统一成 2 空格
    frag = re.sub(r"(?m)^ {4}", "  ", frag)
    return (MARK + "\n  %s\n  " + END_MARK) % (fingerprint(d), frag.strip())


# ---------------------------------------------------------------- 应用内置


def render_app(d):
    """应用内置兜底：与远程 policy.json 的 html 完全同构，
    这样「离线看到的内置版」和「联网拉到的版」在结构上没有差别。"""
    body = re.sub(r"(?m)^ {4}", "  ", str(d.get("html") or "").strip())
    return (MARK + "\n%s\n  " + END_MARK) % (fingerprint(d), indent(body, 2))


# ---------------------------------------------------------------- 一致性


def consistency_problems(d):
    """policy.json 自身的自洽性：字段与正文里的手写副本必须对得上。

    应用侧不生成版本行，直接用 html 里那句；官网与内置都由字段重写。字段与
    正文不一致时，有的地方对、有的地方错——这种「看起来都正常」的错最难发现。
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
        out.append("policy.json: html 里出现 script/style/style=/id，应用侧清洗会丢掉，别写")
    bad = sorted({c for a in re.findall(r'class="([^"]+)"', html) for c in a.split() if c not in ("policy", "pmeta", "warn-red")})
    if bad:
        out.append("policy.json: html 用了应用侧不认的样式类 %s（只有 policy/pmeta/warn-red 有效）" % bad)
    return out


# ---------------------------------------------------------------- 文件读写


def replace_region(text, new_block, where):
    begin = BEGIN_RE.search(text)
    end = text.find(END_MARK)
    if not begin or end < 0 or end < begin.end():
        return None, "%s: 缺少 <!-- POLICY:BEGIN --> / <!-- POLICY:END --> 标记" % where
    return text[: begin.start()] + new_block + text[end + len(END_MARK):], None


def plan(d):
    """返回 [(路径, 新内容, 说明)]，新内容为 None 表示无法处理。"""
    jobs, errors = [], []

    page = PAGE.read_text(encoding="utf-8")
    new_page, err = replace_region(page, render_site(d), PAGE.name)
    if err:
        errors.append(err)
    else:
        jobs.append((PAGE, new_page, "官网页面"))

    app = APP.read_text(encoding="utf-8")
    m = re.search(r'(<template id="policyTpl">)(.*?)(</template>)', app, re.S)
    if not m:
        errors.append('assets/index.html: 找不到 <template id="policyTpl">…</template>')
    else:
        inner = m.group(2)
        # 模板里除政策正文外不该有别的东西；但万一有人在标记外补了说明，保留它
        body_begin = BEGIN_RE.search(inner)
        body_end = inner.find(END_MARK)
        head = inner[: body_begin.start()] if body_begin else ""
        tail = inner[body_end + len(END_MARK):] if body_end >= 0 else ""
        if not head.strip():
            head = "\n  "
        if not tail.strip():
            tail = "\n"
        inner_new = head + render_app(d) + tail
        new_app = app[: m.start(2)] + inner_new + app[m.end(2):]
        new_app = DISC_RE.sub(
            "const DISC_VER = '%s';   /* 由 tools/gen_policy.py 从 site/policy.json 同步，勿手改 */" % d.get("ver", ""),
            new_app,
            count=1,
        )
        jobs.append((APP, new_app, "应用内置文本 + DISC_VER"))

    return jobs, errors


def main(argv):
    if "--help" in argv or "-h" in argv:
        print(__doc__)
        return 0
    if not POLICY.exists():
        print("找不到 %s" % POLICY, file=sys.stderr)
        return 1
    try:
        d = json.loads(POLICY.read_text(encoding="utf-8"))
    except Exception as e:
        print("policy.json 解析失败：%s" % e, file=sys.stderr)
        return 1

    if "--dump" in argv:
        print(d.get("html", ""))
        return 0

    problems = consistency_problems(d)
    jobs, errors = plan(d)
    stale = [(p, c, w) for p, c, w in jobs if c != p.read_text(encoding="utf-8")]

    if "--check" in argv:
        msgs = list(problems) + errors
        msgs += ["%s 未同步（%s）" % (p.relative_to(ROOT), w) for p, _, w in stale]
        if msgs:
            for m in msgs:
                print("✗ " + m, file=sys.stderr)
            print("\n修：python tools/gen_policy.py", file=sys.stderr)
            return 1
        print("✓ 隐私政策三处一致（v%s，sha256=%s）" % (d.get("ver"), fingerprint(d)))
        return 0

    for p in problems:
        print("! " + p, file=sys.stderr)
    for e in errors:
        print("✗ " + e, file=sys.stderr)

    for path, content, what in stale:
        path.write_text(content, encoding="utf-8")
        print("已更新 %-22s %s" % (path.relative_to(ROOT).as_posix(), what))
    if not stale and not errors:
        print("已是最新（v%s，sha256=%s）" % (d.get("ver"), fingerprint(d)))

    return 1 if (problems or errors) else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
