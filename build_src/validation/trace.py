import numpy as np, cv2
def ends(run,y0):
    if run[-1]-run[0]<=4: return [(run[0]+run[-1])/2.0+y0]
    return [run[0]+y0,(run[0]+run[-1])/2.0+y0,run[-1]+y0]
def extract(rgb, roi=(0.055,0.79,0.0,1.0), debug=False):
    a=rgb.astype(np.int32); H,W,_=a.shape
    x0=int(roi[0]*W); x1=int(roi[1]*W); y0=int(roi[2]*H); y1=int(roi[3]*H)
    r,g,b=a[...,0],a[...,1],a[...,2]
    # sentiment bar (green|red) marks the bottom edge of the chart area
    gm=((g>=110)&((g-r)>=50)&((g-b)>=30)); rm=((r>=170)&((r-g)>=90)&((r-b)>=90))
    bar=None
    for yy in range(int(.25*H),int(.95*H)):
        if gm[yy,x0:x1].sum()>0.18*(x1-x0) and rm[yy,x0:x1].sum()>0.05*(x1-x0): bar=yy;break
    if bar is not None: y1=min(y1,bar-max(2,int(0.006*H)))
    M=((r<=145)&((b-r)>=38)&((g-r)>=10)&(b>=g-6)).astype(np.uint8)
    M[:y0]=0;M[y1:]=0;M[:,:x0]=0;M[:,x1:]=0
    k=max(5,int(round(W/60))|1)
    box=cv2.blur(M.astype(np.float32),(k,k))
    blob=(box>=0.55).astype(np.uint8)
    blob=cv2.dilate(blob,np.ones((k,k),np.uint8))
    M2=M.copy(); M2[blob>0]=0
    ncols=x1-x0; roiH=y1-y0
    rowfrac=M2[:,x0:x1].sum(1)/ncols
    rows=np.where(rowfrac>0.35)[0]
    for rr in rows: M2[max(0,rr-1):rr+2,:]=0
    step=max(1,W//270)
    cols=list(range(x0,x1,step))
    cand=[]
    for x in cols:
        ys=np.where(M2[y0:y1,x])[0]
        c=[]
        if len(ys):
            run=[ys[0]]
            for y in ys[1:]:
                if y-run[-1]<=2: run.append(y)
                else: c+=ends(run,y0); run=[y]
            c+=ends(run,y0)
        cand.append(c)
    # DP
    INF=1e18; gap=0.05*roiH; jmax=0.25*roiH; js=0.02*roiH
    n=len(cols); states=[c+[None] for c in cand]
    cost=[[0.0]*len(s) for s in states]; back=[[0]*len(s) for s in states]
    for i in range(1,n):
        for j,y in enumerate(states[i]):
            best=INF;bk=0
            for jj,yp in enumerate(states[i-1]):
                if y is None: t=gap*0.6
                elif yp is None: t=gap
                else: t=min(abs(y-yp),jmax)/js
                if y is None and yp is None: t=gap*0.3
                v=cost[i-1][jj]+t
                if v<best: best=v;bk=jj
            cost[i][j]=best;back[i][j]=bk
    j=int(np.argmin(cost[-1])); path=[None]*n
    for i in range(n-1,-1,-1):
        path[i]=states[i][j]
        j=back[i][j]
    idx=[i for i,p in enumerate(path) if p is not None]
    if len(idx)<10: return None
    lo,hi=idx[0],idx[-1]
    ys=np.array([np.nan if p is None else p for p in path[lo:hi+1]])
    xs=np.array(cols[lo:hi+1])
    nan=np.isnan(ys); ys[nan]=np.interp(np.where(nan)[0],np.where(~nan)[0],ys[~nan])
    amb=np.mean([len(c)>1 for c in cand[lo:hi+1]])
    real=~nan
    q=dict(bar=bar,y1=y1,real_frac=float((~nan).mean()),span_frac=float((hi-lo+1)/n),amb=float(amb),maxjump=float(np.max(np.abs(np.diff(ys)))/roiH) if len(ys)>1 else 0)
    if debug: return xs,ys,q,real
    return xs,ys,q
