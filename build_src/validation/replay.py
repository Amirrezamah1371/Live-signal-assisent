import json,glob,math,collections,sys
import numpy as np, pandas as pd
sys.path.insert(0,'/tmp/proto')
from engine import ChangeEngine
WEAK={"FILTER","SAME_SETUP","ANTI_FLIP","CONFIRM","DIRECTION_UNSTABLE","LATE_ENTRY_RISK","SHOCK_UNRESOLVED"}; LATE={"LATE_ENTRY_RISK","SHOCK_UNRESOLVED"}
def sig(x): return 1/(1+math.exp(-x))
# ---- Experience mirror ----
class Exp:
    def __init__(s): s.m={}
    def keys(s,a,st,rg,bd): return [a,f"{a}|{st}",f"{a}|{st}|{rg}",f"{a}|{st}|{rg}|{bd}"]
    def chain(s,keys):
        c=[s.m.get(k,[0.0,0.0]) for k in keys]
        w0=max(0.0,c[0][0]-c[1][0]); l0=max(0.0,c[0][1]-c[1][1]); prior=(w0+10)/(w0+l0+20); al=be=0
        for lv in (1,2,3):
            own=c[lv]; al=own[0]+10*prior; be=own[1]+10*(1-prior)
            if lv<3:
                ch=c[lv+1]; rw=max(0.0,own[0]-ch[0]); rl=max(0.0,own[1]-ch[1]); prior=(rw+10*prior)/(rw+rl+10)
        return al,be
    def advise(s,a,st,rg,bd):
        k=s.keys(a,st,rg,bd); al,be=s.chain(k); c=s.m.get(k[3],[0,0]); n=c[0]+c[1]; p=al/(al+be); sd=math.sqrt(p*(1-p)/(al+be+1)); lb=p-1.2816*sd; ub=p+1.2816*sd
        if n<10: return 'NONE',0.0,n,p
        if ub<0.5: return 'AVOID',-100,n,p
        if ub<0.55 and n>=20: return 'DAMP',-min(15,max(3,(0.55-p)*100)),n,p
        # BOOST used to add this posterior onto STR. It is not a calibrated probability.
        if lb>0.55: return 'NONE',0.0,n,p
        return 'NONE',0.0,n,p
    def learn(s,a,st,rg,bd,res):
        for k,v in s.m.items():
            if k==a or k.startswith(a+'|'): v[0]*=0.995; v[1]*=0.995
        for k in s.keys(a,st,rg,bd):
            c=s.m.setdefault(k,[0.0,0.0]); c[0 if res=='WIN' else 1]+=1
def band(e): return 'E_LOW' if e<50 else ('E_MID' if e<70 else 'E_HIGH')
def regime(t): return 'TREND' if t>=0.6 else ('RANGE' if t<=0.3 else 'MIXED')
def decide(obs,endMs,ce,tnow,exp,stale=0,use_recent=True):
    tw=lambda o: 0.5**(max(0,endMs-o['t'])/1000/25)*(1.6 if o['t']>=80000 else 1.0)
    ef=lambda o: 0.55+min(100,max(0,o['entry']))/220
    up=dn=0.0;dirN=0;weakN=0;lateW=0.0;sw30=0.0
    for o in obs:
        if o['reason']=='LOW_VISIBILITY': continue
        d=o['dir'] in('UP','DOWN'); weak=(not d) and o['side']!=0 and o['reason'] in WEAK
        if not d and not weak: continue
        w=tw(o)*ef(o)*(1.0 if d else 0.3)
        if weak and o['reason'] in LATE:
            if endMs-o['t']<=30000: lateW+=w
            continue
        sg=(1 if o['dir']=='UP' else -1) if d else o['side']
        if sg>0: up+=w
        else: dn+=w
        dirN+=d; weakN+=weak
        if endMs-o['t']<=30000: sw30+=w
    tot=up+dn; cons=max(up,dn)/tot if tot>0 else 0
    base='UP' if up>=dn*1.15 and up>0 else ('DOWN' if dn>=up*1.15 and dn>0 else 'WAIT')
    late=lateW/(sw30+lateW) if (sw30+lateW)>0 else 0
    last20=[o for o in obs if endMs-o['t']<=20000]; extQ=np.mean([o['q'] for o in last20]) if last20 else 0
    info=dict(base=base,dirN=dirN,cons=cons)
    if stale>4000: return 'WAIT','STALE',0,info
    if len(obs)<45: return 'WAIT','INSUFFICIENT_OBSERVATIONS',0,info
    if dirN<4: return 'WAIT','FEW_DIRECTIONAL',0,info
    bs=1 if base=='UP' else (-1 if base=='DOWN' else 0)
    feB=ce.features(tnow,bs) if use_recent else dict(valid=False)
    side=bs;corr=False;rled=False
    zc=(0.2*feB['z3']+0.3*feB['z6']+0.3*feB['z10']+0.2*feB['z20']) if feB['valid'] else 0
    if bs==0:
        if feB['valid'] and abs(feB['agree'])>=0.999 and abs(feB['z10'])>=1 and abs(feB['z6'])>=0.8:
            c=1 if zc>0 else -1; f2=ce.features(tnow,c)
            if f2['valid'] and f2['state']=='CONTINUING': side=c; rled=True
        if side==0: return 'WAIT','CYCLE_INCOHERENT',0,info
    elif feB['valid'] and feB['state']=='REGIME_CHANGE': side=-bs; corr=True
    elif cons<0.60: return 'WAIT','CYCLE_INCOHERENT',0,info
    sstr='UP' if side>0 else 'DOWN'
    fe=ce.features(tnow,side) if use_recent else dict(valid=False)
    state='REGIME_CHANGE' if corr else (fe['state'] if fe['valid'] else 'NO_RECENT')
    sw=eS=cS=xS=rS=rW=0.0
    for o in last20:
        if o['reason']=='LOW_VISIBILITY': continue
        d=o['dir'] in('UP','DOWN'); s=(1 if o['dir']=='UP' else -1) if d else o['side']; t=tw(o)
        if o.get('trend') is not None: rS+=o['trend']*t; rW+=t
        if s!=side: continue
        w=t*(1.0 if d else 0.3); sw+=w; eS+=o['entry']*w; cS+=o['conflict']*w; xS+=(o.get('exh') or 0)*w
    eb=eS/sw if sw>0 else 50.0; conf=cS/sw if sw>0 else 50.0; exh=xS/sw if sw>0 else 0.0; rg=regime(rS/rW if rW>0 else 0.5)
    pen=0.0
    if state=='EXHAUSTION': pen+=22
    if fe['valid'] and side*fe['z3']<=-0.7 and not corr: pen+=18
    if state=='PULLBACK': pen+=8
    if state=='NOISE': pen+=15
    pen+=20*(1-extQ)+0.15*conf+15*late
    eq=min(100,max(0,eb-pen))
    sideW=up if side>0 else dn; oth=dn if side>0 else up; margin=(sideW-oth)/tot if tot>0 else 0
    agree=fe['agree'] if fe['valid'] else 0.0
    cl=lambda v:min(1,max(0,v))
    cV=cl(agree*0.5+0.5) if corr else cl((margin-0.1)/0.7); cC=0.5 if corr else cl((cons-0.5)/0.5); cR=(agree+1)/2 if fe['valid'] else 0.0
    e=0.26*cV+0.18*cC+0.22*cR+0.18*eq/100+0.10*extQ+0.06*cl(dirN/20)-0.20*cl(conf/100)-0.15*cl(exh)-(0.10 if corr else 0)-(0 if fe['valid'] else 0.06)
    s0=100*sig(7*(e-0.42))
    bd=band(eq); act,delta,nEff,post=exp.advise(sstr,state,rg,bd)
    st=min(100,max(0,s0+(0 if act=='AVOID' else delta)))
    info.update(state=state,corr=corr,rled=rled,eq=eq,s0=s0,act=act,ctx=(sstr,state,rg,bd),late=late,pen=pen,extQ=extQ)
    if not fe.get('valid'): return 'WAIT','NO_RECENT_EVIDENCE',st,info
    if state=='EXHAUSTION': return 'WAIT','EXHAUSTION',st,info
    if state=='NOISE': return 'WAIT','NOISE',st,info
    if late>=0.5: return 'WAIT','LATE_WINDOW',st,info
    if act=='AVOID': return 'WAIT','EXPERIENCE_AVOID',st,info
    if eq<32: return 'WAIT','POOR_ENTRY',st,info
    if st<22: return 'WAIT','WEAK_EVIDENCE',st,info
    return sstr,('REGIME_CHANGE' if corr else ('RECENT_LED' if rled else 'OK')),st,info
def load_cycles():
    out=[]
    for p in sorted(glob.glob('/tmp/mem/*/sessions/*/timeline.jsonl'),key=lambda x:x.split('/')[-2]):
        sid=p.split('/')[-2]
        if any(sid==c['session'] for c in out): continue
        ev=[json.loads(l) for l in open(p)]
        obs=collections.defaultdict(list)
        for e in ev:
            if e['type']=='BOT_OBSERVATION':
                dg=e.get('diagnostics',{})
                side=0
                if e['direction'] not in('UP','DOWN') and e['reason'] in WEAK: side=1 if e['up']>=e['down'] else -1
                obs[e['cycle']].append(dict(t=e['cycle_elapsed_ms'],dir=e['direction'],reason=e['reason'],entry=e['entry'],conflict=e['conflict'],side=side,
                    q=min(1.0,max(0.0,dg.get('vision_coverage',0.8))) if e['reason']!='LOW_VISIBILITY' else 0.0,trend=dg.get('trend_regime'),exh=dg.get('exhaustion'),
                    tr=dg.get('trace_right'),ts=e['ts_ms']))
        for e in ev:
            if e['type'] in('MINUTE_DECISION','NINETY_SECOND_DECISION'):
                out.append(dict(session=sid,kind=e['type'],cycle=e['cycle'],old=e['direction'],ts=e['ts_ms'],obs=obs.get(e['cycle'],[]),endMs=90000 if e['type']=='NINETY_SECOND_DECISION' else 60000,reason=e['reason']))
    return out
