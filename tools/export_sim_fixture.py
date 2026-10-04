# -*- coding: utf-8 -*-
"""从设备导出的 DB 中导出真实字幕源，作为 Groq 模拟测试 fixture。"""
import json
import sqlite3
import sys
import pathlib

sys.stdout.reconfigure(encoding="utf-8")

ITEM_ID = "9b01ee90-ddac-4b47-a27d-5aa3d9a4af85"  # RJ01472300 01轨, 437条, HTTP 413 失败
OUT = pathlib.Path(r"d:\My_Project\EaraAsmrPlayer\app\src\test\resources\groq_sim_sources.json")

con = sqlite3.connect(r"d:\My_Project\EaraAsmrPlayer\tools\raw\asmr_player.db")
con.row_factory = sqlite3.Row
rows = con.execute(
    "select sourceIndex, startMs, endMs, text from subtitle_translation_sources "
    "where itemId=? order by sourceIndex",
    (ITEM_ID,),
).fetchall()
sources = [dict(r) for r in rows]
OUT.parent.mkdir(parents=True, exist_ok=True)
OUT.write_text(json.dumps(sources, ensure_ascii=False, indent=1), encoding="utf-8")
print("exported", len(sources), "sources ->", OUT)
chars = sum(len(r["text"]) for r in sources)
print("total chars:", chars)
