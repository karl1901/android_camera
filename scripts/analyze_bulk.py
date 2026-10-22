#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""解析 diag.txt 中 HDH bulk transfer 头序列，按 PTS 分组重建帧，定位帧不完整根因。"""
import re, sys, collections

path = sys.argv[1] if len(sys.argv) > 1 else r"C:\Users\karl\AppData\Local\Temp\diag.txt"
pat = re.compile(r"bd s0 n=(\d+) len=(\d+) h=([0-9A-F]+) i=([0-9A-F]+) eof=(\d) fid=(\d) pts=([0-9A-F]+)")
# s0 = HDH bulk

transfers = []
for line in open(path, encoding="utf-8", errors="replace"):
    m = pat.search(line)
    if m:
        n, length, h, info, eof, fid, pts = m.groups()
        transfers.append(dict(n=int(n), length=int(length), h=int(h,16),
                              info=int(info,16), eof=int(eof), fid=int(fid), pts=pts))

# 按 n 顺序（遇到 n 回绕说明流重启，分段）
segments = []
cur = []
for t in transfers:
    if cur and t["n"] <= cur[-1]["n"]:
        segments.append(cur); cur = []
    cur.append(t)
if cur: segments.append(cur)

print(f"bd s0 transfer 总数={len(transfers)}, 段数={len(segments)}")
for si, seg in enumerate(segments[:3]):
    print(f"\n===== 段 {si}: n={seg[0]['n']}..{seg[-1]['n']}, 共{len(seg)}个 transfer =====")
    # 按 PTS 分组
    frames = collections.OrderedDict()
    for t in seg:
        frames.setdefault(t["pts"], []).append(t)
    fno = 0
    for pts, ts in frames.items():
        fno += 1
        total = sum(x["length"] for x in ts)
        hdr = sum(x["h"] for x in ts)
        data = total - hdr
        eofs = [x for x in ts if x["eof"]]
        fids = sorted(set(x["fid"] for x in ts))
        # 检查 EOF 标志数量
        print(f"帧{fno} pts={pts} FID={fids} xfers={len(ts)} 原始={total} 去头={data} "
              f"EOF数={len(eofs)} 首n={ts[0]['n']} 末n={ts[-1]['n']} 末info={ts[-1]['info']:02X}")
        if fno >= 8: break
