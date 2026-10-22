#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""解析 UVC 设备 raw config descriptor，重点处理 bulk-only 设备（VS 描述符在端点之后）。"""
import sys

RAW = "09 02 BE 04 04 01 00 80 FA 08 0B 00 02 0E 03 00 05 09 04 00 00 01 0E 01 00 05 0D 24 01 00 01 6D 00 C0 E1 E4 00 01 01 09 24 03 05 01 01 00 04 00 1D 24 06 03 70 33 F0 28 11 63 2E 4A BA 2C 68 90 EB 33 40 16 20 01 02 04 FF FF FF FE 00 1D 24 06 04 94 73 DF DD 3E 97 27 47 BE D9 04 ED 64 26 DC 67 20 01 03 04 FF FF FF FF 00 12 24 02 01 01 02 00 00 00 00 00 00 00 00 03 0E 00 00 0B 24 05 02 01 00 00 02 7F 17 00 07 05 83 03 10 00 06 05 25 03 80 00 09 04 01 00 01 0E 02 00 00 07 05 85 02 00 02 01 0F 24 01 02 99 03 85 00 05 02 01 01 01 00 00 0B 24 06 01 0B 00 01 00 00 00 00 32 24 07 01 00 80 07 38 04 00 40 E3 09 00 80 53 3B 4D 4A 3F 00 15 16 05 00 06 15 16 05 00 06 15 16 05 00 80 1A 06 00 20 A1 07 00 2A 2C 0A 00 40 42 0F 00 80 84 1E 00 32 24 07 02 00 00 05 C0 03 00 00 DC 05 00 00 28 23 4D 82 25 00 15 16 05 00 06 15 16 05 00 80 1A 06 00 20 A1 07 00 2A 2C 0A 00 40 42 0F 00 80 84 1E 00 32 24 07 03 00 00 05 D0 02 00 00 65 04 00 00 5E 1A 4D 22 1C 00 15 16 05 00 06 15 16 05 00 80 1A 06 00 20 A1 07 00 2A 2C 0A 00 40 42 0F 00 80 84 1E 00 1E 24 07 04 00 00 04 40 02 00 00 E0 10 00 00 E0 10 4D 02 12 00 15 16 05 00 01 15 16 05 00 32 24 07 05 00 20 03 58 02 00 F0 49 02 00 A0 BB 0D 4D A8 0E 00 15 16 05 00 06 15 16 05 00 80 1A 06 00 20 A1 07 00 2A 2C 0A 00 40 42 0F 00 80 84 1E 00 32 24 07 06 00 80 02 E0 01 00 00 77 01 00 00 CA 08 4D 62 09 00 15 16 05 00 06 15 16 05 00 80 1A 06 00 20 A1 07 00 2A 2C 0A 00 40 42 0F 00 80 84 1E 00 1E 24 07 07 00 80 02 68 01 00 40 7E 05 00 40 7E 05 4D 0A 07 00 80 1A 06 00 01 80 1A 06 00 32 24 07 08 00 60 01 20 01 00 C0 7B 00 00 80 E6 02 4D 1A 03 00 15 16 05 00 06 15 16 05 00 80 1A 06 00 20 A1 07 00 2A 2C 0A 00 40 42 0F 00 80 84 1E 00 32 24 07 09 00 40 01 F0 00 00 C0 5D 00 00 80 32 02 4D 5A 02 00 15 16 05 00 06 15 16 05 00 80 1A 06 00 20 A1 07 00 2A 2C 0A 00 40 42 0F 00 80 84 1E 00 32 24 07 0A 00 A0 00 78 00 00 70 17 00 00 A0 8C 00 4D 98 00 00 15 16 05 00 06 15 16 05 00 80 1A 06 00 20 A1 07 00 2A 2C 0A 00 40 42 0F 00 80 84 1E 00 32 24 07 0B 00 80 07 38 04 00 40 E3 09 00 80 53 3B 4D 4A 3F 00 15 16 05 00 06 15 16 05 00 80 1A 06 00 20 A1 07 00 2A 2C 0A 00 40 42 0F 00 80 84 1E 00 22 24 03 00 07 80 07 38 04 00 05 D0 02 20 03 58 02 80 02 E0 01 80 02 68 01 40 01 F0 00 40 01 B4 00 00 1B 24 04 02 0A 59 55 59 32 00 00 10 00 80 00 00 AA 00 38 9B 71 10 01 00 00 00 00 1E 24 05 01 00 80 07 38 04 00 40 E3 09 00 40 E3 09 00 48 3F 00 80 84 1E 00 01 80 84 1E 00 1E 24 05 02 00 00 05 D0 02 00 00 65 04 00 00 65 04 00 20 1C 00 80 84 1E 00 01 80 84 1E 00 1E 24 05 03 00 00 04 40 02 00 00 D0 02 00 00 D0 02 00 12 00 80 84 1E 00 01 80 84 1E 00 1E 24 05 04 00 20 03 58 02 00 C0 27 09 00 C0 27 09 00 A6 0E 00 20 A1 07 00 01 20 A1 07 00 1E 24 05 05 00 80 02 E0 01 00 00 DC 05 00 00 DC 05 00 60 09 00 20 A1 07 00 01 20 A1 07 00 1E 24 05 06 00 80 02 68 01 00 00 65 04 00 00 65 04 00 08 07 00 20 A1 07 00 01 20 A1 07 00 1E 24 05 07 00 60 01 20 01 00 00 EF 01 00 00 EF 01 00 18 03 00 20 A1 07 00 01 20 A1 07 00 1E 24 05 08 00 40 01 F0 00 00 00 77 01 00 00 77 01 00 58 02 00 20 A1 07 00 01 20 A1 07 00 1E 24 05 09 00 A0 00 78 00 00 C0 5D 00 00 C0 5D 00 00 96 00 00 20 A1 07 00 01 20 A1 07 00 1E 24 05 0A 00 80 07 38 04 00 40 E3 09 00 40 E3 09 00 48 3F 00 80 84 1E 00 01 80 84 1E 00 12 24 03 00 03 80 07 38 04 00 05 D0 02 80 02 E0 01 00 06 24 0D 01 01 04 08 0B 02 02 01 00 00 00 09 04 02 00 00 01 01 00 00 09 24 01 00 01 2B 00 01 03 0C 24 02 01 01 02 00 02 00 00 00 00 0D 24 06 02 01 02 01 00 02 00 02 00 00 09 24 03 03 01 01 00 02 00 09 04 03 00 00 01 02 00 00 09 04 03 01 01 01 02 00 00 07 24 01 03 01 01 00 1D 24 02 01 02 02 10 07 40 1F 00 11 2B 00 80 3E 00 22 56 00 C0 5D 00 44 AC 00 80 BB 00 09 05 84 05 C0 00 04 00 00 07 25 01 01 00 00 00"

d = bytes(int(x, 16) for x in RAW.split())

def u16(b, i): return b[i] | (b[i+1] << 8)
def u32(b, i): return b[i] | (b[i+1] << 8) | (b[i+2] << 16) | (b[i+3] << 24)

VS_SUB = {1:"INPUT_HEADER",2:"OUTPUT_HEADER",3:"STILL",4:"FORMAT_UNCOMP",5:"FRAME_UNCOMP",6:"FORMAT_MJPEG",7:"FRAME_MJPEG",0x0d:"COLORFORMAT"}
VC_SUB = {1:"VC_HEADER",2:"INPUT_TERMINAL",3:"OUTPUT_TERMINAL",4:"SELECTOR_UNIT",5:"PROCESSING_UNIT",6:"EXTENSION_UNIT"}

i = 0
cur_if = None
while i < len(d):
    ln = d[i]; t = d[i+1]
    if ln == 0: break
    if t == 0x02:  # config
        print(f"[{i}] CONFIG total={u16(d,i+2)} nIf={d[i+4]} cfgVal={d[i+5]}")
    elif t == 0x0B:  # IAD
        print(f"[{i}] IAD firstIf={d[i+2]} cnt={d[i+3]} class={d[i+4]:02x} sub={d[i+5]:02x}")
    elif t == 0x04:  # interface
        cur_if = d[i+2]
        print(f"[{i}] INTERFACE if={d[i+2]} alt={d[i+3]} nEp={d[i+4]} class={d[i+5]:02x} sub={d[i+6]:02x}")
    elif t == 0x05:  # endpoint
        print(f"[{i}]   ENDPOINT ep=0x{d[i+2]:02x} attr={d[i+3]} maxPkt={u16(d,i+4)} interval={d[i+6]}")
    elif t == 0x24:  # class-specific
        sub = d[i+2]
        # 判定 VC/VS 依据当前接口 subclass
        # 帧/格式在 VS 段
        if sub == 7 or sub == 5:  # FRAME
            idx = d[i+3]; caps=d[i+4]
            w=u16(d,i+5); h=u16(d,i+7)
            fbs=u32(d,i+17); div=u32(d,i+21); nit=d[i+25]
            print(f"[{i}]   FRAME{'_MJPEG' if sub==7 else '_UNCOMP'} idx={idx} {w}x{h} maxBuf={fbs} defIv={div} nit={nit}")
            print(f"        hex={d[i:i+ln].hex(' ')}")
            if nit==0:
                print(f"        continuous min={u32(d,i+26)} max={u32(d,i+30)} step={u32(d,i+34)}")
            else:
                ivs=[u32(d,i+26+4*k) for k in range(nit)]
                fps=sorted(set(round(10000000/x,1) for x in ivs), reverse=True)
                print(f"        intervals={ivs} fps={fps}")
        elif sub == 6 or sub == 4:  # FORMAT
            fmt = "MJPEG" if sub==6 else "UNCOMP"
            fi = d[i+3]; nf = d[i+4]
            bpp = d[i+21] if ln>=22 else d[i+5]
            print(f"[{i}]   FORMAT_{fmt} idx={fi} nFrames={nf} bpp={bpp} len={ln}")
        elif sub == 1:
            if cur_if is not None and d[i+0] and i < len(d):
                # 区分 VC_HEADER(在VC接口, 字段不同) vs VS_INPUT_HEADER
                pass
            print(f"[{i}]   CS_SUB=0x01 len={ln} bytes={d[i:i+ln].hex(' ')}")
        else:
            print(f"[{i}]   CS_SUB=0x{sub:02x}({VS_SUB.get(sub,VC_SUB.get(sub,'?'))}) len={ln}")
    elif t == 0x25:
        print(f"[{i}]   SS_EP_COMPANION bytes={d[i:i+ln].hex(' ')}")
    else:
        print(f"[{i}] type=0x{t:02x} len={ln}")
    i += ln
