#!/usr/bin/env python3
"""Pack Volt plaintext strings into Kotlin intArrayOf declarations."""

SEED_PHRASE = "Tc7!rKm_q9Vx2"
STREAM_LEN = 31


def build_stream() -> list[int]:
    h = 0x811C9DC5
    for ch in SEED_PHRASE:
        h ^= ord(ch)
        h = (h * 0x01000193) & 0xFFFFFFFF

    state = h if h != 0 else 0x9E3779B9
    out: list[int] = []
    for _ in range(STREAM_LEN):
        state ^= (state << 13) & 0xFFFFFFFF
        state &= 0xFFFFFFFF
        state ^= state >> 17
        state &= 0xFFFFFFFF
        state ^= (state << 5) & 0xFFFFFFFF
        state &= 0xFFFFFFFF
        out.append((state >> 16) & 0xFF)
    return out


def pack(value: str) -> list[int]:
    stream = build_stream()
    return [
        (byte ^ stream[i % STREAM_LEN] ^ (i & 0xFF)) & 0xFF
        for i, byte in enumerate(value.encode("latin-1"))
    ]


def kotlin_line(name: str, values: list[int]) -> str:
    return f"val {name} = intArrayOf({', '.join(str(v) for v in values)})"


PLAINTEXT = {
    "CONFIG_ENDPOINT_BYTES": "https://thunndercrest.com/config.php",
    "ATTRIBUTION_KEY_BYTES": "PG6N5qRcCdbtsBJs7vTBre",
    "MESSAGING_PROJECT_BYTES": "500343001472",
    "CHROME_VERSION_BYTES": "149.0.7681.92",
    "WEBKIT_VERSION_BYTES": "537.36",
}


if __name__ == "__main__":
    for key, value in PLAINTEXT.items():
        print(kotlin_line(key, pack(value)))
