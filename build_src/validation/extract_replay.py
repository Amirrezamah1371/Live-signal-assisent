"""Turn the October 8 memory export into a compact replay tape for SessionReplayTest.

The tape is not shipped in the APK. It only exists so the repaired Kotlin engine can be
fed the original traces and observation labels.
"""
import json, re, struct, sys
from pathlib import Path

PARTS = [
    Path("/home/ubuntu/.cursor/projects/workspace/uploads/MEMORY_CURSOR_PART_0_5247.txt"),
    Path("/home/ubuntu/.cursor/projects/workspace/uploads/MEMORY_CURSOR_PART_1_f934.txt"),
    Path("/home/ubuntu/.cursor/projects/workspace/uploads/MEMORY_CURSOR_PART_2_8fef.txt"),
]
OUT = Path("/tmp/lsa_replay.bin")


def wstr(buf, s):
    b = (s or "").encode()
    buf.extend(struct.pack(">H", len(b)))
    buf.extend(b)


def main():
    text = b"".join(p.read_bytes() for p in PARTS).decode("utf-8")
    chunks = re.split(r"\n={10,}\nFILE: ", text)
    buf = bytearray()
    old_wait = old_pub = 0
    old_reset = old_hold = old_ok = old_gap = 0
    decisions = 0
    for chunk in chunks:
        head = chunk.split("\n", 1)[0]
        if "timeline.jsonl" not in head:
            continue
        name = head.strip()
        buf.append(3)
        wstr(buf, name)
        body = chunk.split("\n", 2)[-1]
        pending = {}
        for line in body.splitlines():
            if not line.startswith("{"):
                continue
            ev = json.loads(line)
            typ = ev.get("type")
            if typ == "BOT_OBSERVATION":
                d = ev.get("diagnostics") or {}
                reason = ev.get("reason") or ""
                if reason.startswith("RESET_") or str(d.get("reg_reason", "")).startswith("RESET_"):
                    old_reset += 1
                rs = d.get("reg_status")
                if rs == "OK":
                    old_ok += 1
                elif rs == "FAIL":
                    old_hold += 1
                elif rs == "GAP":
                    old_gap += 1
                update = 1 if "reg_t" in d else 0
                ys = []
                # trace is decoded in Kotlin from the raw ints we store; here we only pass
                # the already-decoded path when present. Keep the integer rows.
                b64 = d.get("trace_b64") or ""
                n = 0
                real = b""
                rows = []
                if update and b64:
                    import base64, zlib
                    raw = zlib.decompress(base64.b64decode(b64))
                    n = (raw[0] << 8) | raw[1]
                    rows = [((raw[2 + 2 * i] << 8) | raw[3 + 2 * i]) for i in range(n)]
                    base = 2 + 2 * n
                    real = bytes((raw[base + i // 8] >> (i % 8)) & 1 for i in range(n))
                else:
                    update = 0
                direction = ev.get("direction") or "WAIT"
                code = {"WAIT": 0, "UP": 1, "DOWN": 2}.get(direction, 0)
                buf.append(1)
                buf.extend(struct.pack(">iqB", int(ev.get("cycle") or 0), int(ev.get("cycle_elapsed_ms") or 0), update))
                if update:
                    buf.extend(struct.pack(">d", float(d.get("reg_t") or 0.0)))
                    buf.extend(struct.pack(">H", n))
                    buf.extend(struct.pack(">" + "H" * n, *rows))
                    buf.extend(real)
                buf.extend(struct.pack(
                    ">biiidddddd",
                    code,
                    int(ev.get("side") or 0),
                    int(ev.get("entry") or 0),
                    int(ev.get("conflict") or 0),
                    float(d.get("trace_q") or 0.0),
                    float(d.get("exhaustion") or 0.0),
                    float(d.get("continuation") or 0.0),
                    float(d.get("trend_regime") or 0.0),
                    float(d.get("vol_expansion") or 0.0),
                    float(d.get("conflict_value") or ev.get("conflict") or 0.0),
                ))
                wstr(buf, reason)
            elif typ == "NINETY_SECOND_DECISION":
                d = ev.get("diagnostics") or {}
                direction = ev.get("direction") or "WAIT"
                if direction == "WAIT":
                    old_wait += 1
                else:
                    old_pub += 1
                decisions += 1
                pending[int(ev.get("cycle") or 0)] = len(buf)
                recent = d.get("recent_valid")
                recent_b = 1 if recent is True or recent == "true" else 0
                buf.append(2)
                buf.extend(struct.pack(">idB", int(ev.get("cycle") or 0), float(ev.get("actual_decision_mono_ms") or 0) / 1000.0, recent_b))
                wstr(buf, direction)
                wstr(buf, ev.get("reason") or "")
                wstr(buf, "")  # filled later if we want; result attached as following record
                buf.append(4)
                buf.extend(struct.pack(">i", int(ev.get("cycle") or 0)))
                wstr(buf, "")  # result placeholder, patched by a side map written as type 5
            elif typ == "SIGNAL_PUBLISHED":
                buf.append(5)
                buf.extend(struct.pack(">i", 0))
                wstr(buf, ev.get("signal_id") or "")
                wstr(buf, ev.get("direction") or "")
            elif typ == "TRADE_RESULT":
                buf.append(6)
                wstr(buf, ev.get("signal_id") or "")
                wstr(buf, ev.get("result") or "")
                wstr(buf, ev.get("direction") or "")
    OUT.write_bytes(buf)
    meta = {
        "bytes": len(buf),
        "decisions": decisions,
        "old_wait": old_wait,
        "old_published": old_pub,
        "old_ok": old_ok,
        "old_fail_status": old_hold,
        "old_gap": old_gap,
        "old_reset_reasons": old_reset,
    }
    Path("/tmp/lsa_replay_meta.json").write_text(json.dumps(meta, indent=2))
    print(json.dumps(meta))


if __name__ == "__main__":
    main()
