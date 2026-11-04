#!/usr/bin/env python3
"""
Genera archivos .wav PCM 16-bit mono de prueba para el espectrograma FFT.
Solo usa la biblioteca estándar de Python.

Tipos de señal:
  chirp  - barrido de frecuencia lineal (diagonal en el espectrograma)
  chord  - acorde de tonos puros (líneas horizontales)
  melody - secuencia de notas (escalones)
  noise  - ruido blanco (energía en todo el espectro)
  mix    - combinación de chirp + acorde + algo de ruido

Ejemplos:
  python3 scripts/generate_wav.py chirp
  python3 scripts/generate_wav.py melody -d 5 -o src/main/resources/melody.wav
  python3 scripts/generate_wav.py chirp --f0 100 --f1 8000 -r 22050
"""
import argparse
import math
import random
import struct
import wave


def chirp(t, dur, f0, f1):
    k = (f1 - f0) / dur
    return math.sin(2 * math.pi * (f0 * t + 0.5 * k * t * t))


def chord(t, freqs=(440.0, 554.37, 659.25)):
    return sum(math.sin(2 * math.pi * f * t) for f in freqs) / len(freqs)


MELODY = [261.63, 293.66, 329.63, 349.23, 392.00, 440.00, 493.88, 523.25]


def melody(t, dur):
    note_len = dur / len(MELODY)
    idx = min(int(t / note_len), len(MELODY) - 1)
    f = MELODY[idx]
    local = t - idx * note_len
    env = min(1.0, local / 0.02, (note_len - local) / 0.02)  # evita clics
    return env * (0.7 * math.sin(2 * math.pi * f * t) + 0.3 * math.sin(4 * math.pi * f * t))


def generate(kind, dur, rate, f0, f1, amp):
    n = int(dur * rate)
    out = []
    for i in range(n):
        t = i / rate
        if kind == "chirp":
            s = chirp(t, dur, f0, f1)
        elif kind == "chord":
            s = chord(t)
        elif kind == "melody":
            s = melody(t, dur)
        elif kind == "noise":
            s = random.uniform(-1, 1)
        else:  # mix
            s = 0.5 * chirp(t, dur, f0, f1) + 0.4 * chord(t) + 0.1 * random.uniform(-1, 1)
        out.append(max(-1.0, min(1.0, s * amp)))
    return out


def write_wav(path, samples, rate):
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(b"".join(struct.pack("<h", int(s * 32767)) for s in samples))


def main():
    p = argparse.ArgumentParser(description="Generador de WAV de prueba")
    p.add_argument("kind", choices=["chirp", "chord", "melody", "noise", "mix"])
    p.add_argument("-d", "--duration", type=float, default=3.0, help="segundos (def. 3)")
    p.add_argument("-r", "--rate", type=int, default=44100, help="Hz (def. 44100)")
    p.add_argument("--f0", type=float, default=200.0, help="frecuencia inicial del chirp")
    p.add_argument("--f1", type=float, default=10000.0, help="frecuencia final del chirp")
    p.add_argument("-a", "--amplitude", type=float, default=0.8)
    p.add_argument("-o", "--output", help="ruta de salida (def. src/main/resources/<kind>.wav)")
    a = p.parse_args()

    out = a.output or f"src/main/resources/{a.kind}.wav"
    samples = generate(a.kind, a.duration, a.rate, a.f0, a.f1, a.amplitude)
    write_wav(out, samples, a.rate)
    print(f"Generado {out}: {a.kind}, {a.duration}s, {a.rate} Hz, {len(samples)} muestras")


if __name__ == "__main__":
    main()
