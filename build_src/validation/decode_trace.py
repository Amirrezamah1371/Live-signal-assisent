"""Decode the 'trace_b64' field logged in BOT_OBSERVATION diagnostics (72.0.1+).
Layout before deflate: [n:uint16 BE][n x y:uint16 BE (pixel row, smaller = higher price)][ceil(n/8) bytes, bit i (LSB first) = real pick]."""
import base64, zlib
def decode(b64):
    if not b64: return None
    raw = zlib.decompress(base64.b64decode(b64))
    n = (raw[0] << 8) | raw[1]
    ys = [(raw[2+2*i] << 8) | raw[3+2*i] for i in range(n)]
    base = 2 + 2*n
    real = [bool(raw[base + i//8] >> (i % 8) & 1) for i in range(n)]
    return ys, real
def encode(ys, real):   # reference encoder (mirrors TraceCodec.kt) used for self-tests
    n = len(ys); raw = bytearray(2 + 2*n + (n+7)//8)
    raw[0] = n >> 8 & 255; raw[1] = n & 255
    for i, y in enumerate(ys):
        v = max(0, min(32767, int(round(y)))); raw[2+2*i] = v >> 8; raw[3+2*i] = v & 255
    for i, r in enumerate(real):
        if r: raw[2+2*n+i//8] |= 1 << (i % 8)
    return base64.b64encode(zlib.compress(bytes(raw), 1)).decode()
