import math, numpy as np
# ---------------- ChangeEngine (mirror of Kotlin ChangeEngine.kt) ----------------
def register_legacy(old,new):
    n0=len(old); S=max(3,n0//8); best=None
    for s in range(0,S+1):
        L=min(n0-s,len(new))-3
        if L<max(30,int(0.5*n0)): continue
        x=old[s:s+L]; y=new[:L]
        vx=x.var()
        if vx<1e-9: continue
        a=((x-x.mean())*(y-y.mean())).mean()/vx; b=y.mean()-a*x.mean()
        a_c=min(2.8,max(0.35,a)); b=y.mean()-a_c*x.mean()
        res=math.sqrt(((y-(a_c*x+b))**2).mean())/(y.std()+1e-9)
        pen=0.0 if abs(a-a_c)<1e-9 else 1.0
        sc=res+pen
        if best is None or sc<best[0]: best=(sc,s,a_c,b)
    return best
def register(old,new,old_real=None,new_real=None):
    """72.0.2 fit. Score gate stays 0.30. The live tip and the worst 15% of columns are not the fit."""
    old=np.asarray(old,float); new=np.asarray(new,float)
    n0=len(old); S=max(3,n0//8); tail=max(6,len(new)//10); need=max(24,int(0.40*min(n0,len(new))))
    best=None
    for s in range(0,S+1):
        maxL=min(n0-s,len(new))-tail
        if maxL<need: continue
        idx=[i for i in range(maxL) if (old_real is None or old_real[s+i]) and (new_real is None or new_real[i])]
        if len(idx)<need: continue
        x=np.array([old[s+i] for i in idx]); y=np.array([new[i] for i in idx])
        vx=x.var()
        if vx<1e-9: continue
        a=((x-x.mean())*(y-y.mean())).mean()/vx
        a_c=min(2.8,max(0.35,a)); b=y.mean()-a_c*x.mean()
        order=np.sort(np.abs(y-(a_c*x+b))); keep=max(1,int(len(order)*0.85))
        rmse=math.sqrt(np.mean(order[:keep]**2)); res=rmse/(y.std()+1e-9)
        pen=0.0 if abs(a-a_c)<1e-9 else 1.0
        sc=res+pen
        if best is None or sc<best[0]: best=(sc,s,a_c,b)
    return best
class ChangeEngine:
    def __init__(s): s.reset()
    def reset(s):
        s.prev=None; s.A=1.0; s.B=0.0; s.series=[]; s.last_reg='NEW'; s.fail=0; s.provisional=True; s.last_ok=-1.0
    def _wipe(s):
        s.series=[]; s.A=1.0; s.B=0.0; s.provisional=True; s.last_ok=-1.0
    def update(s,t,p,real=None):
        p=np.asarray(p,float); reg='NEW'; sh=0; aa=1.0
        if s.prev is None:
            s.prev=(t,p,real); s.provisional=True
        elif t-s.prev[0]<=3.5 and t>s.prev[0]:
            r=register(s.prev[1],p,s.prev[2],real)
            if r is not None and r[0]<0.30:
                _,sh,a,b=r; aa=a; reg='OK'
                s.B=s.B-s.A*b/a; s.A=s.A/a
                s.prev=(t,p,real); s.fail=0; s.provisional=False; s.last_ok=t
            else:
                s.fail+=1; reg='FAIL'
                if s.fail>=2:
                    s._wipe(); s.prev=(t,p,real); s.fail=0
                else:
                    s.last_reg=reg
                    return dict(reg=reg,shift=0,a=1.0,ref_v=s.series[-1][1] if s.series else 0.0)
        else:
            reg='GAP'; s._wipe(); s.prev=(t,p,real); s.fail=0
        s.last_reg=reg
        if real is None:
            tail=p[-3:]
        else:
            picked=[p[i] for i in range(len(p)-1,-1,-1) if i < len(real) and real[i]]
            tail=np.array(picked[:3] if picked else p[-3:])
        v=s.A*float(np.median(tail))+s.B
        s.series.append((t,v)); s.series=[q for q in s.series if t-q[0]<=45.0]
        return dict(reg=reg,shift=sh,a=aa,ref_v=v)
    def grid(s,t_now,W=30):
        if s.provisional or len(s.series)<8: return None
        ts=np.array([q[0] for q in s.series]); vs=np.array([q[1] for q in s.series])
        if t_now-ts[-1]>2.5: return None
        t_ref=min(t_now,ts[-1])
        if t_ref-ts[0]<12: return None
        W=int(min(W,t_ref-ts[0])); g=t_ref-np.arange(W,-1,-1.0)
        return np.interp(g,ts,vs)
    def features(s,t_now,sb):
        g=s.grid(t_now)
        if g is None: return dict(valid=False)
        W=len(g)-1; steps=np.diff(g)
        mad=np.median(np.abs(steps-np.median(steps)))*1.4826
        u=max(mad,0.6*np.mean(np.abs(steps)),1e-6)
        def z(w):
            w=min(w,W); return (g[-1]-g[-1-w])/(u*math.sqrt(w))
        z3,z6,z10,z20=z(3),z(6),z(10),z(20)
        v3=(g[-1]-g[-4])/3; v3p=(g[-4]-g[-7])/3; v3pp=(g[-7]-g[-10])/3 if W>=9 else v3p
        acc=(v3-v3p)/3; accp=(v3p-v3pp)/3; jerk=(acc-accp)/3
        sbn=sb if sb!=0 else 1
        # impulse in base direction over last 30 s: start (min of sb*g before peak) -> peak
        x=sbn*g; ip=int(np.argmax(x)); is_=int(np.argmin(x[:ip+1])) if ip>0 else 0
        imp=max(x[ip]-x[is_],1e-9); counter=(x[ip]-x[-1])/imp           # fraction retraced
        since_peak=W-ip                                                   # seconds since extreme
        sg=np.sign(steps)*(np.abs(steps)>0.25*u)
        agree_steps=sbn*sg
        last8=agree_steps[-8:]; against=int((last8<0).sum()); withs=int((last8>0).sum())
        pers_recent=(agree_steps[-5:]>0).mean(); pers_prior=(agree_steps[-15:-5]>0).mean() if len(agree_steps)>=10 else pers_recent
        decay=max(0.0,pers_prior-pers_recent)
        nz=sg[-12:][sg[-12:]!=0]; flips=int((np.diff(nz)!=0).sum()) if len(nz)>1 else 0
        agree=float(np.mean([np.sign(sbn*z3),np.sign(sbn*z6),np.sign(sbn*z10),np.sign(sbn*z20)]))
        pv=np.max(np.abs(np.diff(g,3))/3) if W>=6 else abs(v3)
        vel_ratio=abs(v3)/max(pv,1e-9)
        # ---- state classification relative to base side sbn ----
        impz=imp/(u*math.sqrt(max(1,ip-is_)))
        seg=x[is_:ip+1]; er=(x[ip]-x[is_])/max(float(np.abs(np.diff(seg)).sum()),1e-9) if len(seg)>2 else 0.0
        if impz>=1.5 and er>=0.5 and vel_ratio<0.35 and abs(z6)<0.8 and since_peak<=3 and counter<0.5: state='EXHAUSTION'
        elif flips>=6 and abs(z10)<1.0: state='NOISE'
        elif (sbn*z10<=-1.0 and sbn*z6<=-0.8 and against>=5 and counter>=0.5 and since_peak>=5 and not (sbn*z3>=1.0)): state='REGIME_CHANGE'
        elif (sbn*z6<=-0.5): state='PULLBACK'
        else: state='CONTINUING'
        return dict(valid=True,z3=z3,z6=z6,z10=z10,z20=z20,vel=v3,acc=acc,jerk=jerk,counter=counter,
                    since_peak=since_peak,pers_decay=decay,flips=flips,agree=agree,vel_ratio=vel_ratio,
                    against8=against,impz=impz,er=er,state=state,u=u,n=len(s.series),span=t_now-s.series[0][0])
