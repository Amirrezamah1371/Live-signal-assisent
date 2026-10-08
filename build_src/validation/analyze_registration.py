"""Usage: python analyze_registration.py <unzipped LiveSignalMemory folder or .zip>
Rebuilds every registration offline from logged traces (72.0.1+) and explains the FAILs."""
import sys, os, json, glob, zipfile, tempfile, collections
import numpy as np
sys.path.insert(0, os.path.dirname(__file__))
from decode_trace import decode
from engine import register_legacy as register
src = sys.argv[1]
if src.endswith('.zip'):
    d = tempfile.mkdtemp(); zipfile.ZipFile(src).extractall(d); src = d
rows = []
for tl in sorted(glob.glob(os.path.join(src, 'sessions', '*', 'timeline.jsonl'))):
    sid = tl.split(os.sep)[-2]
    for line in open(tl):
        e = json.loads(line)
        if e['type'] != 'BOT_OBSERVATION': continue
        dg = e['diagnostics']; tr = decode(dg.get('trace_b64'))
        rows.append(dict(sid=sid, cycle=e['cycle'], t=dg.get('reg_t'), prev_t=dg.get('reg_prev_t'), status=dg.get('reg_status'),
                         reason=dg.get('reg_reason'), score=dg.get('reg_score'), res=dg.get('reg_resid_rel'), shift=dg.get('reg_best_shift'),
                         a_raw=dg.get('reg_a_raw'), clamped=dg.get('reg_a_clamped'), olen=dg.get('reg_overlap_len'), tq=dg.get('trace_q'),
                         old_n=dg.get('reg_old_n'), new_n=dg.get('reg_new_n'), path=None if tr is None else -np.array(tr[0], float),
                         real=None if tr is None else np.array(tr[1])))
print('observations', len(rows), 'with trace', sum(r['path'] is not None for r in rows))
print('status:', dict(collections.Counter(r['status'] for r in rows)))
print('reason:', dict(collections.Counter(r['reason'] for r in rows)))
# offline reproduction check
bad = 0; chk = 0
for a, b in zip(rows, rows[1:]):
    if a['sid'] != b['sid'] or a['path'] is None or b['path'] is None or b['status'] not in ('OK', 'FAIL'): continue
    r = register(a['path'], b['path']); chk += 1
    if r is None or b['score'] is None or abs(r[0] - b['score']) > 0.02: bad += 1
print(f'offline recomputation matches logged score in {chk-bad}/{chk} pairs')
# where does the residual come from? error profile along the path for FAIL vs OK
prof = {'OK': [], 'FAIL': []}
for a, b in zip(rows, rows[1:]):
    if a['sid'] != b['sid'] or a['path'] is None or b['path'] is None or b['status'] not in prof: continue
    r = register(a['path'], b['path'])
    if r is None: continue
    _, s, aa, bb = r[:4]; s = int(s); old, new = a['path'], b['path']; L = min(len(old)-s, len(new)) - 3
    if L < 30: continue
    e = np.abs(new[:L] - (aa*old[s:s+L] + bb)) / (new[:L].std() + 1e-9)
    prof[b['status']].append(np.interp(np.linspace(0, 1, 10), np.linspace(0, 1, L), e))
for k, v in prof.items():
    if v: print(k, 'mean |err|/std by path decile (left->right):', np.round(np.mean(v, axis=0), 2), 'n', len(v))
# path-length change and tail behaviour
dn = collections.defaultdict(list)
for a, b in zip(rows, rows[1:]):
    if a['sid'] == b['sid'] and b['status'] in ('OK', 'FAIL') and a['new_n'] and b['new_n']: dn[b['status']].append(abs(b['new_n'] - a['new_n']))
for k, v in dn.items(): print(k, '|path length change| quantiles 50/75/95:', np.percentile(v, [50, 75, 95]))
print('clamped scale among FAIL:', sum(1 for r in rows if r['status'] == 'FAIL' and r['clamped']), '/', sum(1 for r in rows if r['status'] == 'FAIL'))
