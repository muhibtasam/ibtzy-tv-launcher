#!/usr/bin/env bash
# Dev helper: tools/perf.sh "keys" label — resets the home screen's frame stats, presses the keys (adb keycodes,
# "s" = 1 s pause), then prints frame timing from dumpsys gfxinfo framestats: frames, janky share and median / p90 of
# the whole frame, the UI thread's draw (onDraw recording) and the render thread (sync + GPU issue + swap), in ms.
TV=${TV:-192.168.1.69:5555}; A="adb -s $TV"
PID=$($A shell pidof io.freemytv:home | tr -d '\r')
$A shell dumpsys gfxinfo "$PID" reset >/dev/null
for k in $1; do if [ "$k" = s ]; then sleep 1; else $A shell input keyevent "$k"; sleep 0.25; fi; done
sleep 1
$A shell dumpsys gfxinfo "$PID" framestats | python3 -c '
import sys
label=sys.argv[1]; hdr=None; rows=[]
for line in sys.stdin:
    p=line.strip().rstrip(",").split(",")
    if p[0]=="Flags": hdr=p; continue
    if hdr and len(p)==len(hdr) and p[0].lstrip("-").isdigit():
        r=dict(zip(hdr,map(int,p)))
        if r["Flags"]==0: rows.append(r)
def ms(v): return v/1e6
def pct(a,q): a=sorted(a); return a[min(len(a)-1,int(len(a)*q))] if a else 0
tot=[ms(r["FrameCompleted"]-r["IntendedVsync"]) for r in rows]
ui=[ms(r["SyncQueued"]-r["DrawStart"]) for r in rows]
rt=[ms(r["FrameCompleted"]-r["SyncStart"]) for r in rows]
an=[ms(r["PerformTraversalsStart"]-r["AnimationStart"]) for r in rows]
inp=[ms(r["AnimationStart"]-r["HandleInputStart"]) for r in rows]
lat=[ms(r["HandleInputStart"]-r["IntendedVsync"]) for r in rows]
jank=sum(1 for t in tot if t>16.7)
print(f"{label}: frames {len(rows)}  >16.7ms {jank} ({100*jank/max(1,len(rows)):.0f}%)  total p50 {pct(tot,.5):.1f} p90 {pct(tot,.9):.1f}  ui-draw p50 {pct(ui,.5):.1f} p90 {pct(ui,.9):.1f}  render p50 {pct(rt,.5):.1f} p90 {pct(rt,.9):.1f}  anim(doFrame) p50 {pct(an,.5):.1f} p90 {pct(an,.9):.1f}  input p90 {pct(inp,.9):.1f}  start-delay p50 {pct(lat,.5):.1f}")
' "$2"
