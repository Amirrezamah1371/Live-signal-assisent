import numpy as np, base64, sys
sys.path.insert(0,'.')
from decode_trace import encode, decode
rng=np.random.default_rng(0)
for n in (1,7,8,9,397,399):
    ys=list(rng.integers(0,2400,n)); real=list(rng.random(n)>0.2)
    out=decode(encode(ys,real)); assert out==(ys,real),n
print('codec round-trip OK; encoded size for n=397:',len(encode(list(rng.integers(300,900,397)),[True]*397)),'chars (random worst case)')
