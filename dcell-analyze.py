#!/usr/bin/env python3
"""UAP-2 D-cell reader. Usage: dcell-analyze.py <runs/r8i-x1/D dir> [--asm]

For each leg's perfasm log (JMH stdout, Hottest Region 1, Intel syntax): finds the hot byte loop as the hottest
backward branch in the region (target <= branch), and prints the loop's start address (the branch target) mod 64,
instruction count, cycles share, the [rsp+...] loads (memory source) and stores (memory destination), getfield bytemap
loads, and loads from registers holding the dfa/bytemap/transitions. With --asm, prints the loop body.
For each leg's perfnorm JSON: cycles, instructions and the alias event per op (secondary metrics)."""
import json, pathlib, re, sys

D = pathlib.Path(sys.argv[1])
ASM = "--asm" in sys.argv
ins_re = re.compile(r"^\s*(?:(\d+\.\d+)%)?\s*[│╭╰↗↘ ]*\s*(0x[0-9a-f]+):\s+(\S+)\s*(.*)$")


def region1(text):
    s = text.index("[Hottest Region 1]")
    e = text.index("<total for region 1>", s)
    head = text[s:e].splitlines()
    title = head[1].strip()
    out = []
    for l in head[2:]:
        m = ins_re.match(l)
        if m:
            out.append(dict(pct=float(m.group(1) or 0), addr=int(m.group(2), 16), op=m.group(3), args=m.group(4).split(";")[0].strip(), raw=l))
        elif out and "; - " in l or (out and "*getfield" in l):
            out[-1]["raw"] += "\n" + l
    return title, out


def classify(i):
    a = i["args"]
    if "[rsp" not in a:
        return None
    parts = [p.strip() for p in a.split(",", 1)]
    if i["op"] in ("cmp", "test"):
        return "load"
    if len(parts) == 2 and "[rsp" in parts[0]:
        return "store"
    return "load"


for leg in "ACD":
    log = D / f"{leg}-perfasm.log"
    if not log.exists():
        continue
    title, ins = region1(log.read_text())
    best = None
    for k, i in enumerate(ins):
        if i["op"].startswith("j"):
            m = re.match(r"0x([0-9a-f]+)", i["args"])
            if m:
                t = int(m.group(1), 16)
                if ins[0]["addr"] <= t <= i["addr"]:
                    j = next(n for n, x in enumerate(ins) if x["addr"] == t)
                    body = ins[j:k + 1]
                    share = sum(x["pct"] for x in body)
                    if best is None or share > best[0]:
                        best = (share, body, t)
    share, body, t = best
    loads = [x for x in body if classify(x) == "load"]
    stores = [x for x in body if classify(x) == "store"]
    gf = [x for x in body if "getfield bytemap" in x["raw"]]
    print(f"## {leg}: {title}")
    print(f"- loop {hex(t)}..{hex(body[-1]['addr'])}: start mod 64 = {t % 64} (0x{t % 64:02x}), {len(body)} instructions, "
          f"{body[-1]['addr'] + 6 - t} bytes approx, {share:.1f}% cycles")
    print(f"- [rsp] loads {len(loads)}: " + "; ".join(f"{x['op']} {x['args']}" for x in loads))
    print(f"- [rsp] stores {len(stores)}: " + "; ".join(f"{x['op']} {x['args']}" for x in stores))
    print(f"- getfield bytemap in loop: {len(gf)}" + ("".join(f" ({x['op']} {x['args']})" for x in gf)))
    if ASM:
        for x in body:
            print(f"    {x['pct']:6.2f}%  {hex(x['addr'])}: {x['op']:7s} {x['args']}")
for leg in "ACD":
    f = D / f"{leg}-perfnorm.json"
    if not f.exists():
        continue
    e = json.loads(f.read_text())[0]
    sm = e.get("secondaryMetrics", {})
    vals = {k.split(":", 1)[-1]: v["score"] for k, v in sm.items()}
    print(f"## {leg} perfnorm: score {e['primaryMetric']['score']:.1f} ns/op; " + ", ".join(f"{k} {v:,.0f}" for k, v in vals.items()))
