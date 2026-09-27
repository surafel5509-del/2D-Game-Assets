"""Generate the bundled CC0 sound effects. No recorded or third-party samples used."""
import math
import random
import struct
import wave
from pathlib import Path

RATE = 22050
OUT = Path('tools/generated-sfx')
OUT.mkdir(parents=True, exist_ok=True)
NAMES = ['click','hover','jump','step','hit','explosion','laser','engine-start','engine','brake','door','coin','powerup','damage','gameover','menu','notification','success','failure','wind','splash','magic','sparkle','spawn','shield','teleport','level','countdown','fire','heartbeat']

for index, name in enumerate(NAMES):
    rng = random.Random(index * 2981 + 11)
    duration = [0.13,0.10,0.32,0.18,0.26,0.9,0.23,0.7,1.0,0.45,0.43,0.32,0.57,0.35,0.9,0.26,0.3,0.67,0.5,1.15,0.5,0.66,0.4,0.55,0.5,0.7,0.95,0.58,1.2,0.75][index]
    samples = []
    phase = 0.0
    for sample in range(int(duration * RATE)):
        t = sample / RATE
        progress = t / duration
        fade = min(1.0, t * 65) * (1 - progress) ** (0.55 if name in ('wind','engine','fire') else 1.8)
        noise = rng.uniform(-1, 1)
        if name in ('explosion','hit','damage','fire','step','splash','wind'):
            freq = 100 + 120 * (1 - progress) + (index % 4) * 35
            phase += 2 * math.pi * freq / RATE
            tone = math.sin(phase) * 0.35 + noise * (0.65 if name != 'wind' else 0.35)
            if name == 'explosion': fade *= (0.5 + 0.5 * math.sin(t * 26) ** 2)
        elif name in ('engine','engine-start','brake','door','heartbeat'):
            freq = (68 + index * 5) * (1 + (progress * 1.7 if name == 'engine-start' else -progress * 0.65 if name == 'brake' else 0))
            phase += 2 * math.pi * freq / RATE
            tone = math.sin(phase) * 0.5 + math.sin(phase * 2) * 0.24 + noise * 0.11
            if name == 'heartbeat': fade *= max(0, math.sin(t * 16)) ** 8
        else:
            direction = -1 if name in ('laser','failure','gameover','spawn') else 1
            freq = 280 + index * 19 + direction * progress * (250 if name not in ('click','hover') else 70)
            if name in ('coin','success','powerup','level','notification'):
                freq += (240 if progress > 0.45 else 0) + (150 if progress > 0.72 else 0)
            phase += 2 * math.pi * freq / RATE
            tone = math.sin(phase) * 0.65 + math.sin(phase * 2) * 0.17 + noise * 0.045
        sample_value = max(-1.0, min(1.0, tone * fade * 0.44))
        samples.append(struct.pack('<h', int(sample_value * 32767)))
    with wave.open(str(OUT / (name + '.wav')), 'wb') as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(RATE)
        wav.writeframes(b''.join(samples))
print(f'Generated {len(NAMES)} original sound effects in {OUT}')
