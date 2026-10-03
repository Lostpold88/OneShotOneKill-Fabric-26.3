#!/usr/bin/env python3
"""
Synthetisiert die Tonspuren des Endgames (Nuke-Sequenz) in reinem Python – ohne numpy.

Erzeugt vier Dateien unter ``src/main/resources/assets/oneshotonekill/sounds/endgame``:

* ``nuke_countdown_bed.ogg``  Bett unter der Ansage: Sub-Drone, beschleunigender Herzschlag,
                              Sirenen-Heulen, Riser, Geiger-Ticks. Länge = Einschlag (12,05 s).
* ``nuke_impact.ogg``         Der Einschlag: Knall, Sub-Boom, Druckwelle, Trümmerregen, Tinnitus.
* ``nuke_fallout_loop.ogg``   Nahtloser Loop für den Nachlauf: Wind, Drone, Feuerknistern.
* ``nuke_victory.ogg``        Siegesfanfare: Pauken, Blechsatz, Becken, Glöckchen.

Das Bett läuft synchron zur bestehenden Ansage ``tactical_nuke_incoming.ogg`` (Start in Tick 0). Der Herzschlag
der Datei ist deterministisch - HeartbeatClock.java im Client rechnet dieselbe Formel, damit Kamera-Puls und HUD
exakt auf dem Schlag sitzen. Die Konstanten oben (BED_*) müssen auf beiden Seiten gleich bleiben.

Aufruf:  python tools/audio/generate_nuke_audio.py [bed|impact|fallout|victory]
Benötigt ffmpeg (libvorbis) im PATH.
"""
import array
import math
import os
import random
import subprocess
import sys
import tempfile
import wave

SR = 44100
TAU = 2.0 * math.pi

# --- Zeitachse des Countdown-Betts (identisch in HeartbeatClock.java) ---------------------------
BED_LENGTH = 12.05            # Einschlag bei Tick 241 = 12,05 s
BED_CUT = 11.30               # hier bricht alles ab - die Stille vor dem Sturm (wie in der Ansage)
BED_BPM_START = 60.0
BED_BPM_END = 150.0
BED_BEAT_OFFSET = 0.30        # erster Schlag

OUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "src", "main", "resources",
                       "assets", "oneshotonekill", "sounds", "endgame")


# --- Bausteine -----------------------------------------------------------------------------------

class Stereo:
    def __init__(self, seconds):
        self.n = int(seconds * SR)
        self.l = [0.0] * self.n
        self.r = [0.0] * self.n

    def add(self, i, left, right):
        if 0 <= i < self.n:
            self.l[i] += left
            self.r[i] += right

    def add_mono(self, i, v, pan=0.0):
        # gleichleistungs-Panning, pan in -1..1
        a = (pan + 1.0) * math.pi / 4.0
        self.add(i, v * math.cos(a), v * math.sin(a))


def lp_coef(fc):
    return 1.0 - math.exp(-TAU * fc / SR)


class SVF:
    """Zustandsvariablen-Filter (Chamberlin): Tief-, Band- und Hochpass in einem."""

    def __init__(self):
        self.low = 0.0
        self.band = 0.0

    def run(self, x, fc, q=0.7):
        f = 2.0 * math.sin(math.pi * min(fc, SR * 0.45) / SR)
        high = x - self.low - (1.0 / q) * self.band
        self.band += f * high
        self.low += f * self.band
        return self.low, self.band, high


def env_ad(t, attack, decay_rate):
    """Schneller Anstieg, dann exponentieller Abfall."""
    if t < 0:
        return 0.0
    if t < attack:
        return t / attack
    return math.exp(-(t - attack) * decay_rate)


def soft(x, drive=1.0):
    return math.tanh(x * drive)


def write_ogg(buf, name, peak=0.95, drive=1.0):
    """Begrenzt, normalisiert und über ffmpeg als Vorbis-OGG schreiben."""
    for i in range(buf.n):
        buf.l[i] = soft(buf.l[i], drive)
        buf.r[i] = soft(buf.r[i], drive)
    top = max(max(abs(v) for v in buf.l), max(abs(v) for v in buf.r), 1e-9)
    gain = peak / top
    frames = array.array("h")
    for i in range(buf.n):
        frames.append(int(max(-1.0, min(1.0, buf.l[i] * gain)) * 32767))
        frames.append(int(max(-1.0, min(1.0, buf.r[i] * gain)) * 32767))
    os.makedirs(OUT_DIR, exist_ok=True)
    out = os.path.normpath(os.path.join(OUT_DIR, name))
    with tempfile.TemporaryDirectory() as tmp:
        wav_path = os.path.join(tmp, "tmp.wav")
        with wave.open(wav_path, "wb") as w:
            w.setnchannels(2)
            w.setsampwidth(2)
            w.setframerate(SR)
            w.writeframes(frames.tobytes())
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", wav_path, "-c:a", "libvorbis", "-q:a", "5", out],
                       check=True)
    print(f"  {name}: {buf.n / SR:.2f}s  (peak vor Norm. {top:.2f})")


# --- Herzschlag-Uhr (gleiche Formel wie HeartbeatClock.java) --------------------------------------

def beat_phase(t):
    """Anzahl Schläge seit Beginn, wenn die Frequenz von 60 auf 150 BPM mit t^1.5 ansteigt."""
    k = (BED_BPM_END - BED_BPM_START) / 60.0
    return t * BED_BPM_START / 60.0 + k * (t ** 2.5) / (2.5 * BED_CUT ** 1.5)


def beat_times():
    times = []
    t = 0.0
    k = 0
    target = k + BED_BEAT_OFFSET
    while t < BED_CUT:
        t += 0.001
        while beat_phase(t) >= target and t < BED_CUT:
            times.append(t)
            k += 1
            target = k + BED_BEAT_OFFSET
    return times


# --- 1. Countdown-Bett ----------------------------------------------------------------------------

def make_bed():
    rnd = random.Random(7)
    s = Stereo(BED_LENGTH)
    cut = int(BED_CUT * SR)

    # Sub-Drone: steigt von 38 auf 60 Hz, Zittern wächst mit
    phase = 0.0
    for i in range(cut):
        t = i / SR
        p = t / BED_CUT
        f = 38.0 + 22.0 * p * p
        phase += TAU * f / SR
        trem = 1.0 + 0.35 * math.sin(TAU * (0.8 + 5.0 * p) * t)
        amp = 0.55 * (0.15 + 0.85 * p) * trem
        v = amp * (math.sin(phase) + 0.35 * math.sin(2 * phase + 0.3))
        s.add(i, v, v)

    # Sirenen: zwei Heulen im Fünftelabstand, gegeneinander versetzt, mit Weite durch Haas-Verzögerung
    def siren(offset, base, side):
        ph = 0.0
        delay = int(0.019 * SR)
        wave_buf = [0.0] * cut
        for i in range(cut):
            t = i / SR
            if t < 2.2:
                continue
            ramp = min(1.0, (t - 2.2) / 5.5)
            f = base * (1.0 + 0.45 * (0.5 - 0.5 * math.cos(TAU * (t + offset) / 5.6)))
            f *= 1.0 + 0.006 * math.sin(TAU * 5.5 * t)
            ph += TAU * f / SR
            v = (math.sin(ph) + 0.5 * math.sin(2 * ph) + 0.33 * math.sin(3 * ph) + 0.2 * math.sin(4 * ph))
            wave_buf[i] = v * 0.085 * ramp * (0.4 + 0.6 * ramp)
        for i in range(cut):
            v = wave_buf[i]
            d = wave_buf[i - delay] if i >= delay else 0.0
            s.add(i, v * (0.5 - 0.45 * side) + d * (0.5 + 0.45 * side),
                  v * (0.5 + 0.45 * side) + d * (0.5 - 0.45 * side))

    siren(0.0, 330.0, -1.0)
    siren(2.8, 495.0, 1.0)

    # Herzschlag: Doppelschlag, wird dichter und lauter
    for bt in beat_times():
        p = bt / BED_CUT
        bpm = BED_BPM_START + (BED_BPM_END - BED_BPM_START) * p ** 1.5
        gap = min(0.17, 60.0 / bpm * 0.34)
        for k, (delay, level) in enumerate(((0.0, 1.0), (gap, 0.7))):
            start = int((bt + delay) * SR)
            f0 = 62.0 if k == 0 else 54.0
            ph = 0.0
            for j in range(int(0.30 * SR)):
                tj = j / SR
                f = f0 * (0.55 + 0.45 * math.exp(-tj * 22.0))
                ph += TAU * f / SR
                v = math.sin(ph) * math.exp(-tj * 13.0) * level * (0.55 + 0.45 * p) * 1.1
                if j < 160:
                    v += (rnd.random() * 2 - 1) * 0.18 * (1 - j / 160.0) * level
                s.add(start + j, v, v)

    # Geiger-Ticks: Poisson-Prozess mit wachsender Rate
    t = 1.0
    while t < BED_CUT - 0.1:
        p = t / BED_CUT
        rate = 3.0 + 70.0 * p * p
        t += -math.log(1.0 - rnd.random()) / rate
        start = int(t * SR)
        pan = rnd.uniform(-1, 1)
        lvl = 0.07 + 0.09 * p
        svf = SVF()
        fc = rnd.uniform(2500, 6500)
        for j in range(int(0.004 * SR)):
            x = (rnd.random() * 2 - 1)
            _, band, _ = svf.run(x, fc, 3.0)
            s.add_mono(start + j, band * lvl * (1 - j / (0.004 * SR)), pan)

    # Rauschen-Riser (ab 5,5 s): Hochpass fährt von 300 Hz auf 9 kHz, Bandbreite öffnet sich
    svf_l, svf_r = SVF(), SVF()
    for i in range(cut):
        t = i / SR
        if t < 5.5:
            continue
        p = (t - 5.5) / (BED_CUT - 5.5)
        fc = 300.0 * (30.0 ** p)
        amp = 0.22 * p ** 1.6
        _, band_l, hi_l = svf_l.run(rnd.random() * 2 - 1, fc, 1.2)
        _, band_r, hi_r = svf_r.run(rnd.random() * 2 - 1, fc, 1.2)
        s.add(i, (hi_l * 0.6 + band_l * 0.4) * amp, (hi_r * 0.6 + band_r * 0.4) * amp)

    # Spannungs-Streicher: Quint-Stapel, der eine Oktave hochgleitet und dabei aufgeht
    notes = [73.4, 110.0, 146.8, 220.0, 293.7]
    phs = [0.0] * len(notes)
    lp = [0.0] * len(notes)
    for i in range(cut):
        t = i / SR
        if t < 4.0:
            continue
        p = (t - 4.0) / (BED_CUT - 4.0)
        bend = 2.0 ** (p * 1.0)
        amp = 0.075 * p ** 1.3
        acc_l = acc_r = 0.0
        for n_i, base in enumerate(notes):
            phs[n_i] += TAU * base * bend / SR
            saw = ((phs[n_i] / TAU) % 1.0) * 2 - 1
            lp[n_i] += lp_coef(700 + 3500 * p) * (saw - lp[n_i])
            acc_l += lp[n_i] * (1.0 - 0.15 * n_i / len(notes))
            acc_r += lp[n_i] * (0.85 + 0.15 * n_i / len(notes))
        s.add(i, acc_l * amp, acc_r * amp)

    # Fallendes Bombenpfeifen: setzt mit dem Abwurf ein (Tick 181 = 9,05 s) und gleitet bis zum Schnitt nach unten.
    ph = 0.0
    release = 9.05
    for i in range(int(release * SR), cut):
        t = i / SR
        p = (t - release) / (BED_CUT - release)
        f = 3300.0 * (1.0 - p) ** 0.8 + 900.0
        f *= 1.0 + 0.012 * math.sin(TAU * 9.0 * t)
        ph += TAU * f / SR
        amp = 0.13 * p ** 0.7 * min(1.0, (t - release) / 0.25)
        v = (math.sin(ph) + 0.35 * math.sin(2 * ph)) * amp + (rnd.random() * 2 - 1) * amp * 0.12
        s.add_mono(i, v, math.sin(TAU * 0.3 * t) * 0.5)

    # Einsaugen: kurz vor dem Schnitt wird der Pegel zu null gezogen (Vakuum)
    suck = int(0.18 * SR)
    for j in range(suck):
        g = 1.0 - j / suck
        s.l[cut - suck + j] *= g
        s.r[cut - suck + j] *= g
    # Stille bis 11,6 s, dann sammelt sich die Luft: tiefes Sub, das auf den Einschlag zuläuft
    ph = 0.0
    for i in range(int(11.60 * SR), s.n):
        t = (i / SR - 11.60) / (BED_LENGTH - 11.60)
        ph += TAU * (24.0 + 14.0 * t) / SR
        v = math.sin(ph) * 0.8 * t * t
        s.add(i, v, v)

    # Bewusst leiser als die Ansage (-9 LUFS): das Bett trägt sie, es übertönt sie nicht.
    write_ogg(s, "nuke_countdown_bed.ogg", peak=0.58, drive=1.15)


# --- 2. Einschlag ---------------------------------------------------------------------------------

def make_impact():
    rnd = random.Random(21)
    length = 15.0
    s = Stereo(length)
    n = s.n

    # Knall: breitbandiger Impuls mit scharfem Abfall
    for j in range(int(0.06 * SR)):
        t = j / SR
        v = (rnd.random() * 2 - 1) * math.exp(-t * 55.0) * 1.6
        s.add(j, v, (rnd.random() * 2 - 1) * math.exp(-t * 55.0) * 1.6)

    # Sub-Boom: 140 -> 22 Hz, übersteuert
    ph = 0.0
    for i in range(int(8.0 * SR)):
        t = i / SR
        f = 22.0 + 120.0 * math.exp(-t * 2.4)
        ph += TAU * f / SR
        v = math.sin(ph) * env_ad(t, 0.004, 0.55) * 2.2
        s.add(i, v, v)

    # Körper der Explosion: Rauschen, dessen Tiefpass von 7 kHz nach unten läuft
    for ch in (0, 1):
        y = 0.0
        y2 = 0.0
        for i in range(int(5.5 * SR)):
            t = i / SR
            fc = 180.0 + 7000.0 * math.exp(-t * 2.3)
            c = lp_coef(fc)
            x = rnd.random() * 2 - 1
            y += c * (x - y)
            y2 += c * (y - y2)
            v = y2 * env_ad(t, 0.003, 0.7) * 2.0
            if ch == 0:
                s.l[i] += v
            else:
                s.r[i] += v

    # Druckwelle: zweiter, tieferer Schlag mit Verzögerung
    ph = 0.0
    start = int(0.38 * SR)
    for j in range(int(3.0 * SR)):
        t = j / SR
        ph += TAU * (18.0 + 34.0 * math.exp(-t * 3.0)) / SR
        v = math.sin(ph) * env_ad(t, 0.02, 1.1) * 1.3
        y = (rnd.random() * 2 - 1)
        s.add(start + j, v, v)

    # Rollender Donner: sehr tiefes Rauschen mit langsamen Wellen
    for ch in (0, 1):
        y = 0.0
        for i in range(int(14.0 * SR)):
            t = i / SR
            x = rnd.random() * 2 - 1
            y += lp_coef(95.0) * (x - y)
            swell = 0.55 + 0.45 * math.sin(TAU * 0.22 * t + (1.7 if ch else 0.0)) * math.sin(TAU * 0.13 * t + 0.4)
            v = y * 5.5 * swell * math.exp(-t * 0.30) * min(1.0, t / 0.2)
            if ch == 0:
                s.l[i] += v
            else:
                s.r[i] += v

    # Trümmerregen: Körner, die erst dicht, dann spärlich fallen
    t = 0.7
    while t < 9.0:
        rate = 5.0 + 150.0 * math.exp(-(t - 0.7) * 0.7)
        t += -math.log(1.0 - rnd.random()) / rate
        start = int(t * SR)
        pan = rnd.uniform(-1, 1)
        fc = rnd.uniform(300, 4000)
        lvl = rnd.uniform(0.04, 0.22) * math.exp(-(t - 0.7) * 0.25)
        svf = SVF()
        dur = int(rnd.uniform(0.006, 0.03) * SR)
        for j in range(dur):
            _, band, _ = svf.run(rnd.random() * 2 - 1, fc, 2.0)
            s.add_mono(start + j, band * lvl * (1 - j / dur), pan)
        if rnd.random() < 0.35:
            ph = 0.0
            f = rnd.uniform(70, 200)
            for j in range(int(0.04 * SR)):
                ph += TAU * f * (1 - j / (0.08 * SR)) / SR
                s.add_mono(start + j, math.sin(ph) * lvl * 1.4 * math.exp(-j / (0.012 * SR)), pan)

    # Feuer: spätes Knistern
    t = 1.8
    while t < 13.0:
        rate = 30.0 * math.exp(-(t - 1.8) * 0.14) + 3.0
        t += -math.log(1.0 - rnd.random()) / rate
        start = int(t * SR)
        pan = rnd.uniform(-1, 1)
        lvl = rnd.uniform(0.02, 0.10) * math.exp(-(t - 1.8) * 0.12)
        for j in range(int(rnd.uniform(0.001, 0.004) * SR)):
            s.add_mono(start + j, (rnd.random() * 2 - 1) * lvl * (1 - j / (0.004 * SR)), pan)

    # Tinnitus: hohe Sinustöne, die nach dem Schlag einsetzen und langsam verklingen
    for freq, level, pan in ((8200.0, 0.050, -0.5), (6150.0, 0.035, 0.5), (1180.0, 0.018, 0.0)):
        ph = 0.0
        for i in range(int(0.7 * SR), int(11.0 * SR)):
            t = i / SR
            g = min(1.0, (t - 0.7) / 0.4) * math.exp(-(t - 0.7) * 0.34)
            ph += TAU * freq / SR
            v = math.sin(ph) * level * g * (0.8 + 0.2 * math.sin(TAU * 0.6 * t))
            s.add_mono(i, v, pan)

    # Kopfraum für Vorbis-Überschwinger: der Boom ist stark übersteuert und würde sonst nach dem Dekodieren clippen.
    write_ogg(s, "nuke_impact.ogg", peak=0.72, drive=0.9)


# --- 3. Fallout-Loop ------------------------------------------------------------------------------

def make_fallout():
    rnd = random.Random(33)
    body = 20.0
    xfade = 2.0
    s = Stereo(body + xfade)
    n = s.n

    # Wind: Bandpass-Rauschen mit wanderndem Zentrum und Böen
    for ch in (0, 1):
        svf = SVF()
        svf2 = SVF()
        for i in range(n):
            t = i / SR
            fc = 420.0 + 260.0 * math.sin(TAU * 0.07 * t + ch * 1.3) + 120.0 * math.sin(TAU * 0.19 * t + 0.4)
            gust = 0.5 + 0.5 * math.sin(TAU * 0.11 * t + ch * 0.9) * math.sin(TAU * 0.043 * t + 1.1)
            x = rnd.random() * 2 - 1
            _, band, _ = svf.run(x, fc, 1.6)
            _, band2, _ = svf2.run(x, fc * 2.3, 2.2)
            v = (band * 0.9 + band2 * 0.35) * (0.10 + 0.22 * gust)
            if ch == 0:
                s.l[i] += v
            else:
                s.r[i] += v

    # Drone: d-Moll-Orgelpunkt D1/A1/D2/F2, leicht verstimmt, atmet langsam
    voices = [(36.71, 0.60, 0.0), (55.0, 0.40, 0.6), (73.42, 0.34, 1.2), (87.31, 0.14, 2.0), (36.9, 0.38, 2.7)]
    phs = [0.0] * len(voices)
    for i in range(n):
        t = i / SR
        breathe = 0.65 + 0.35 * math.sin(TAU * 0.05 * t)
        acc = 0.0
        for k, (f, a, off) in enumerate(voices):
            phs[k] += TAU * f / SR
            acc += math.sin(phs[k]) * a * (0.7 + 0.3 * math.sin(TAU * 0.09 * t + off))
        v = acc * 0.16 * breathe
        s.add(i, v, v * 0.97)

    # ferne Grollen: tiefe Rauschwellen alle paar Sekunden
    t = 1.5
    while t < n / SR - 3.0:
        start = int(t * SR)
        dur = rnd.uniform(1.6, 3.2)
        y = 0.0
        pan = rnd.uniform(-0.8, 0.8)
        for j in range(int(dur * SR)):
            tj = j / SR
            y += lp_coef(80.0) * ((rnd.random() * 2 - 1) - y)
            g = math.sin(math.pi * tj / dur) ** 2
            s.add_mono(start + j, y * 3.2 * g * 0.5, pan)
        t += rnd.uniform(3.5, 7.0)

    # Feuerknistern und gelegentliches Metallknarren
    t = 0.0
    while t < n / SR:
        t += -math.log(1.0 - rnd.random()) / 7.0
        start = int(t * SR)
        pan = rnd.uniform(-1, 1)
        lvl = rnd.uniform(0.02, 0.09)
        for j in range(int(rnd.uniform(0.001, 0.005) * SR)):
            s.add_mono(start + j, (rnd.random() * 2 - 1) * lvl * (1 - j / (0.005 * SR)), pan)
    t = 3.0
    while t < n / SR - 2.0:
        start = int(t * SR)
        pan = rnd.uniform(-1, 1)
        f0 = rnd.uniform(140, 320)
        dur = rnd.uniform(0.5, 1.1)
        ph = 0.0
        for j in range(int(dur * SR)):
            tj = j / SR
            ph += TAU * (f0 + 40.0 * math.sin(TAU * 7.0 * tj)) * (1.0 - 0.25 * tj / dur) / SR
            g = math.sin(math.pi * tj / dur) ** 2
            s.add_mono(start + j, (math.sin(ph) + 0.5 * math.sin(2.1 * ph)) * 0.014 * g, pan)
        t += rnd.uniform(5.0, 9.0)

    # nahtloser Loop: den Überstand in den Anfang mischen (gleichleistung)
    xf = int(xfade * SR)
    body_n = int(body * SR)
    for i in range(xf):
        w = i / xf
        a = math.cos(w * math.pi / 2)
        b = math.sin(w * math.pi / 2)
        s.l[i] = s.l[i] * b + s.l[body_n + i] * a
        s.r[i] = s.r[i] * b + s.r[body_n + i] * a
    s.l = s.l[:body_n]
    s.r = s.r[:body_n]
    s.n = body_n
    write_ogg(s, "nuke_fallout_loop.ogg", peak=0.9, drive=1.0)


# --- 4. Siegesfanfare ------------------------------------------------------------------------------

def make_victory():
    rnd = random.Random(55)
    length = 9.0
    s = Stereo(length)

    def timpani(t0, level, pitch=62.0):
        ph = 0.0
        start = int(t0 * SR)
        for j in range(int(2.4 * SR)):
            tj = j / SR
            f = pitch * (1.0 + 0.9 * math.exp(-tj * 18.0))
            ph += TAU * f / SR
            v = math.sin(ph) * math.exp(-tj * 2.2) * level
            if j < 700:
                v += (rnd.random() * 2 - 1) * 0.25 * level * (1 - j / 700.0)
            s.add(start + j, v, v)

    def brass(t0, dur, freqs, level, swell=1.2):
        start = int(t0 * SR)
        ph = [[0.0, 0.0, 0.0] for _ in freqs]
        lps = [0.0] * len(freqs)
        detune = (0.994, 1.0, 1.007)
        for j in range(int((dur + 1.2) * SR)):
            tj = j / SR
            if tj < swell:
                g = (tj / swell) ** 1.6
            elif tj < dur:
                g = 1.0
            else:
                g = math.exp(-(tj - dur) * 3.0)
            cutoff = 500.0 + 3300.0 * min(1.0, tj / (swell + 0.3))
            c = lp_coef(cutoff)
            acc = 0.0
            for k, f in enumerate(freqs):
                x = 0.0
                for d in range(3):
                    ph[k][d] += TAU * f * detune[d] / SR
                    x += ((ph[k][d] / TAU) % 1.0) * 2 - 1
                lps[k] += c * (x / 3.0 - lps[k])
                acc += lps[k]
            v = acc / len(freqs) * g * level
            pan = math.sin(TAU * 0.2 * tj) * 0.25
            s.add_mono(start + j, v, pan)

    def cymbal(t0, swell_len, level):
        svf_l, svf_r = SVF(), SVF()
        start = int(t0 * SR)
        total = int((swell_len + 4.0) * SR)
        for j in range(total):
            tj = j / SR
            if tj < swell_len:
                g = (tj / swell_len) ** 2 * 0.35
            else:
                g = math.exp(-(tj - swell_len) * 1.4)
            _, _, hl = svf_l.run(rnd.random() * 2 - 1, 5200, 0.9)
            _, _, hr = svf_r.run(rnd.random() * 2 - 1, 5200, 0.9)
            s.add(start + j, hl * g * level, hr * g * level)

    def bell(t0, f, level, pan):
        start = int(t0 * SR)
        for j in range(int(2.5 * SR)):
            tj = j / SR
            v = (math.sin(TAU * f * tj) + 0.45 * math.sin(TAU * f * 2.76 * tj) + 0.25 * math.sin(TAU * f * 5.4 * tj)) \
                * math.exp(-tj * 3.0) * level
            s.add_mono(start + j, v, pan)

    # Pauken: 2 Vorschläge, dann der Schlag zur Auflösung
    timpani(0.00, 1.1)
    timpani(0.80, 0.9, 58.0)
    timpani(1.60, 1.2, 55.0)
    timpani(1.85, 1.3, 55.0)
    # Blech: d-Moll baut sich auf, löst sich in strahlendes D-Dur auf
    brass(0.0, 1.8, [146.8, 174.6, 220.0, 293.7], 0.38, swell=1.7)
    brass(1.85, 4.2, [73.4, 146.8, 185.0, 220.0, 293.7, 370.0, 440.0], 0.42, swell=0.25)
    brass(1.85, 4.2, [36.7], 0.55, swell=0.1)
    cymbal(0.0, 1.85, 0.5)
    # Glöckchen-Arpeggio D-Dur
    for k, f in enumerate((587.3, 740.0, 880.0, 1174.7, 1480.0)):
        bell(2.6 + k * 0.17, f, 0.08, -0.6 + 0.3 * k)

    write_ogg(s, "nuke_victory.ogg", peak=0.74, drive=1.0)


TARGETS = {"bed": make_bed, "impact": make_impact, "fallout": make_fallout, "victory": make_victory}


def main(argv):
    wanted = argv[1:] or list(TARGETS)
    print("Synthetisiere Endgame-Tonspuren ...")
    for name in wanted:
        TARGETS[name]()


if __name__ == "__main__":
    main(sys.argv)
