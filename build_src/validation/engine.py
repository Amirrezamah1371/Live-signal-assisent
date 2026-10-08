import math, numpy as np
# ---------------- ChangeEngine (mirror of Kotlin ChangeEngine.kt) ----------------
def register(old,new):
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
class ChangeEngine:
    def __init__(s): s.reset()
    def reset(s): s.prev=None; s.A=1.0; s.B=0.0; s.series=[]; s.last_reg='NEW'
    def update(s,t,p):
        p=np.asarray(p,float); reg='NEW'; sh=0; aa=1.0
        if s.prev is not None and t-s.prev[0]<=3.5:
            r=register(s.prev[1],p)
            if r is not None and r[0]<0.30:
                _,sh,a,b=r; aa=a; reg='OK'
                s.B=s.B-s.A*b/a; s.A=s.A/a
            else:
                reg='FAIL'; s.series=[]; s.A=1.0; s.B=0.0
        elif s.prev is not None: reg='GAP'; s.series=[]; s.A=1.0; s.B=0.0
        s.prev=(t,p); s.last_reg=reg
        v=s.A*float(np.median(p[-3:]))+s.B
        s.series.append((t,v)); s.series=[q for q in s.series if t-q[0]<=45.0]
        return dict(reg=reg,shift=sh,a=aa,ref_v=v)
    def grid(s,t_now,W=30):
        ts=np.array([q[0] for q in s.series]); vs=np.array([q[1] for q in s.series])
        if len(ts)<8 or t_now-ts[0]<12: return None
        W=int(min(W,t_now-ts[0])); g=t_now-np.arange(W,-1,-1.0)
        return np.interp(g,ts,vs)   # index W = now
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
