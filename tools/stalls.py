# Reads `dumpsys gfxinfo <pid> framestats` on stdin; prints freezes (>50, >100 ms), the worst frame and the main
# thread's worst frame (SyncQueued - IntendedVsync). Used by tools/stalls.sh.
import sys
hdr=None; rows=[]
for line in sys.stdin:
    line=line.strip()
    if line.startswith('Flags,'): hdr=line.rstrip(',').split(','); continue
    if hdr and line and line[0].isdigit():
        v=line.rstrip(',').split(',')
        if len(v)>=len(hdr): rows.append(dict(zip(hdr,map(int,v[:len(hdr)]))))
rows=[r for r in rows if r.get('Flags',1)==0]
ui=sorted((r['SyncQueued']-r['IntendedVsync'])/1e6 for r in rows)
tot=[(r['FrameCompleted']-r['IntendedVsync'])/1e6 for r in rows]
print('%s: frames %d | freezes >50ms %d, >100ms %d | worst frame %.0f ms | main-thread worst %.0f ms'%(sys.argv[1],len(rows),sum(t>50 for t in tot),sum(t>100 for t in tot),max(tot) if tot else 0, ui[-1] if ui else 0))
