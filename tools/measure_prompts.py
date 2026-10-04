#!/usr/bin/env python3
# -*- coding: utf-8 -*-
import re, base64, sys

KEY = b"EaraAsmrPlayer.SubtitlePrompt.V1"
path = r"d:\My_Project\EaraAsmrPlayer\app\src\main\java\com\asmr\player\subtitle\TranslationPromptsEncoded.kt"
src = open(path, encoding="utf-8").read()

# 提取所有 Kotlin 字符串字面量（按源码顺序拼接，还原 ENCODED_LINES）
lits = re.findall(r'"((?:[^"\\]|\\.)*)"', src)
def unescape(s):
    return s.encode("utf-8").decode("unicode_escape").encode("latin-1").decode("utf-8", "ignore")
text = "".join(unescape(x) for x in lits)

rows = []
for line in text.split("\n"):
    if "=" not in line:
        continue
    k, enc = line.split("=", 1)
    enc = enc.strip()
    if not enc:
        continue
    try:
        b = base64.b64decode(enc)
    except Exception:
        continue
    d = bytes(bb ^ KEY[i % len(KEY)] for i, bb in enumerate(b))
    try:
        s = d.decode("utf-8")
    except Exception:
        s = d.decode("utf-8", "ignore")
    rows.append((k, len(s), s))

rows.sort(key=lambda r: -r[1])
total = 0
for k, n, s in rows:
    print(f"{n:>8}  {k}")
    total += n
print("-" * 40)
print(f"{total:>8}  TOTAL(all prompts, chars)")
