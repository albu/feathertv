#!/usr/bin/env python3
"""Sync a WiZ light/strip to whatever music this Mac is playing — v2 DSP.

Pipeline — visible channels stay intuitive, fancy DSP in support roles:

  BlackHole 48 kHz mono
    -> STFT (4096 Hann, 75% overlap, hop 512 @ 93.7 fps)
    -> ERB filterbank (Glasberg & Moore 1990)          # cochlear frequency scale
    -> [onsets]    Superflux (Böck & Widmer 2012)
    -> [rhythm]    tempo: onset-envelope autocorrelation with a log-normal
                   120 BPM prior (Moelants 2002) + beat PLL (Ellis 2007)
                   -> predicted beat events, confidence
    -> [color]     ONE HUE PER SONG: a silence gap (track boundary) triggers
                   a new color from the song's spectral character
                   (heavy/low -> warm, airy/bright -> cool), delivered on a
                   barline. Ear-verifiable by definition: the color changes
                   exactly when the song changes.
    -> [brightness] BS.1770 short-term loudness (macro, slow) + one gentle
                   swell per beat, decay = half a beat, depth capped ~30%
                   (flicker comfort: IEEE 1789, Harding & Jeavons)
    -> decimated to 10 Hz setPilot commands (WiZ firmware limit)

Why it moves the way it moves:
  - Colors change only at section boundaries (a few times per song), and
    each change coincides with an audible dramatic shift — the timing is
    confirmable by ear, which no harmony->color mapping could be.
  - Brightness never follows the audio frame-by-frame: 3-30 Hz contrast
    modulation is the uncomfortable/dangerous band, so modulation is
    beat-locked (typically ~2 Hz) and shallow.
  - If the music has no stable beat (confidence low), the pulse disappears
    and hue drifts on a slow timer instead.

One-time setup (macOS):
  1. brew install blackhole-2ch          # virtual loopback audio device
  2. Audio MIDI Setup -> "+" -> "Create Multi-Output Device":
         [x] BlackHole 2ch             (keep it as primary / clock device)
         [x] your speakers / soundbar  (tick "Drift Correction" on it)
     Note: AirPlay sinks can't join a multi-output group (they go offline);
     Bluetooth can. K-weighting coefficients are exact at 48 kHz — keep the
     group at 48 kHz.
  3. Music player -> output device = "Multi-Output Device"
     (macOS will ask Terminal for microphone permission on first run:
      BlackHole counts as an input device.)

Usage:
  uv run wiz-sync.py --list-devices
  uv run wiz-sync.py --selftest          # synthetic-signal DSP verification
  uv run wiz-sync.py --test              # bulb blinks R/G/B -> control path OK
  uv run wiz-sync.py                     # discover bulb, start syncing
  uv run wiz-sync.py --ip 192.168.1.100 --audio-delay 150
"""

# /// script
# requires-python = ">=3.10"
# dependencies = [
#   "pywizlight>=0.6",
#   "sounddevice>=0.4.6",
#   "numpy>=1.26",
#   "scipy>=1.11",
# ]
# ///

import argparse
import asyncio
import colorsys
import math
import queue
import sys
import time
from collections import deque

import numpy as np
import sounddevice as sd
from scipy.ndimage import maximum_filter1d
from scipy.signal import lfilter
from scipy.signal.windows import tukey
from pywizlight import PilotBuilder, discovery, wizlight

TX_INTERVAL = 0.10          # never command the bulb more often than 10 Hz
SR_REQUIRED = 48000         # BS.1770 K-weighting coefficients are exact here
WINDOW = 4096               # STFT window (85 ms)
HOP = 512                   # 10.7 ms frame rate = 93.7 fps

# BS.1770-4 K-weighting, published biquads for 48 kHz (shelf + high-pass).
KB1 = [1.53512485958697, -2.69169618940638, 1.19839281085285]
KA1 = [1.0, -1.69065929318241, 0.73248077421585]
KB2 = [1.0, -2.0, 1.0]
KA2 = [1.0, -1.99004745483398, 0.99007225036621]

FIFTHS = 7                  # chroma pc -> circle-of-fifths position: (7*pc) % 12
SECTION_DWELL_DEFAULT = 18.0  # s; real sections last tens of seconds
PROFILE_NOW_TAU = 0.8       # "right now" spectral profile
SECTION_COS_THRESHOLD = 0.15  # spectral divergence that marks a boundary
SECTION_LU_JUMP = 7.0       # sustained loudness shift that marks a boundary
SECTION_PERSIST_S = 2.0     # divergence must hold this long to count


def clamp(lo, hi, v):
    return max(lo, min(hi, v))


def a_weight_db(f):
    """A-weighting curve, IEC 61672 (returns dB, 0 at 1 kHz)."""
    f2 = np.asarray(f, dtype=float) ** 2
    ra = (12194.0 ** 2 * f2 * f2) / (
        (f2 + 20.6 ** 2)
        * np.sqrt((f2 + 107.7 ** 2) * (f2 + 737.9 ** 2))
        * (f2 + 12194.0 ** 2)
    )
    return 2.0 + 20.0 * np.log10(ra)


class DSP:
    """Perceptual analysis chain: ERB/A-weighted spectrum, BS.1770 loudness,
    superflux onsets, chroma -> circle-of-fifths hue, shaped envelopes."""

    def __init__(self, sr, sensitivity=1.2, gain=1.15, range_lu=7.0,
                 bars=4, min_section=SECTION_DWELL_DEFAULT, slow_bias=True,
                 hue_min=20.0, hue_max=250.0, sat=0.9):
        assert sr == SR_REQUIRED, f"need {SR_REQUIRED} Hz, got {sr}"
        self.sr = sr
        self.fps = sr / HOP
        self.sensitivity = sensitivity
        self.gain = gain
        self.range_lu = range_lu
        self.bars = bars
        self.min_section = min_section
        self.slow_bias = slow_bias
        self.hue_min, self.hue_max, self.sat = hue_min, hue_max, sat

        self.buf = np.zeros(0)
        self.freqs = np.fft.rfftfreq(WINDOW, 1.0 / sr)
        self.win = np.hanning(WINDOW)

        # --- ERB filterbank (Glasberg & Moore 1990) --------------------------
        erb = lambda f: 21.4 * np.log10(0.00437 * f + 1.0)
        inv_erb = lambda e: (10.0 ** (e / 21.4) - 1.0) / 0.00437
        n_bands = int(erb(0.45 * sr / 2) - erb(50.0))
        edges = inv_erb(np.linspace(erb(50.0), erb(0.45 * sr / 2), n_bands + 1))
        centers = 0.5 * (edges[:-1] + edges[1:])
        w = np.clip(1.0 - np.abs(self.freqs[None, :] - centers[:, None]) /
                    np.maximum(edges[1:, None] - edges[:-1, None], 1e-9), 0.0, None)
        self.W = w / np.maximum(w.sum(axis=1, keepdims=True), 1e-9)

        # A-weighting per band (10^(A/10)) sharpens onset salience where the
        # ear is sensitive (transients live in the 1-6 kHz region). Not used
        # for chroma — see _frame.
        self.aw_band_pow = 10.0 ** (a_weight_db(centers) / 10.0)
        self.W = self.W * self.aw_band_pow[:, None]

        # --- chroma bin -> pitch class, standard C=0 convention --------------
        # (12*log2(f/440) mod 12 puts A at 0; +9 rotates so C=0, A=9.
        #  Masked bins only: log2(0) is -inf.)
        self.cmask = (self.freqs >= 55.0) & (self.freqs <= 5000.0)
        self.pc = np.zeros(len(self.freqs), dtype=int)
        self.pc[self.cmask] = (np.round(
            12.0 * np.log2(self.freqs[self.cmask] / 440.0)).astype(int)
            + 9) % 12

        # --- BS.1770 K-weighting filter state --------------------------------
        self.z1 = np.zeros(2)
        self.z2 = np.zeros(2)

        # --- running state ----------------------------------------------------
        self.prev_lf = None
        self.odf_peak = 0.0                       # decayed recent max flux
        self.odf_hist = deque(maxlen=140)         # ~1.5 s adaptive baseline
        self.ms_short = deque(maxlen=int(0.4 * sr / HOP))   # 400 ms loudness
        # Track-length AGC reference (~30 s): a shorter one is built from the
        # same material it is compared against and cancels the music's own
        # macro dynamics — brightness would peg at the top.
        self.ms_long = deque(maxlen=int(30.0 * sr / HOP))
        self.chroma = np.full(12, 1.0 / 12.0)     # kept: selftest-verified
                                                  # spectral diagnostic
        # --- section tracking (color stage) ----------------------------------
        self.band_ref = None                      # snapshot at section start
        self.band_now = None                      # what's happening right now
        self.L_sect = None                        # loudness at section start
        self.centroid_slow = 400.0                # s of centroid smoothing
        self.section_idx = 0
        self.section_pending = False
        self.section_t = 0.0
        self.last_section = -1e9
        self.high_since = None                    # divergence persistence
        self.level = 0.0
        self.bri = 5.0
        self.hue = hue_min
        self.hue_target = hue_min
        self.last_onset = -1.0
        self.frame_t = 0.0
        # --- beat clock: tempo + phase (Ellis 2007 / Moelants 2002) ----------
        self.beat_period = 0.5                    # 120 BPM prior
        self.next_beat = None                     # analysis time of next beat
        self.beat_idx = 0
        self.odf_env = deque(maxlen=int(8.0 * self.fps))
        self.last_tempo_scan = 0.0
        self.beat_hits = deque(maxlen=16)         # PLL hit/miss -> confidence
        self.confidence = 0.0
        self.conf_ema = 0.0                       # smoothed; gates the pulse
        self.pulse = 0.0                          # beat swell, depth-capped
        self.last = {"onset": False, "strength": 0.0, "odf": 0.0, "L": -70.0,
                     "ref": -90.0, "hue": hue_min, "bri": 5.0, "sat": sat,
                     "tonal": 0.0, "level": 0.0, "beat": False, "beat_idx": 0,
                     "bpm": 120.0, "conf": 0.0, "section": False,
                     "sect": 0}

    # ------------------------------------------------------------------ #

    def process(self, block, now):
        """Consume samples, advance all frames, return the latest frame state."""
        out = dict(self.last)
        out["onset"] = False
        out["beat"] = False
        out["section"] = False
        self.buf = np.concatenate([self.buf, block])
        while len(self.buf) >= WINDOW:
            out = self._frame(self.buf[:WINDOW], self.buf[:WINDOW], now)
            self.buf = self.buf[HOP:]
            self.frame_t = now
        return out

    def _frame(self, frame_raw, frame_win, now):
        spec = np.abs(np.fft.rfft(frame_win * self.win))
        power = spec ** 2
        dt = HOP / self.sr

        # ---- BS.1770 K-weighted loudness ---------------------------------
        y1, self.z1 = lfilter(KB1, KA1, frame_raw, zi=self.z1)
        y2, self.z2 = lfilter(KB2, KA2, y1, zi=self.z2)
        ms = float(np.mean(y2 ** 2))
        self.ms_short.append(ms)
        self.ms_long.append(ms)
        L = -0.691 + 10.0 * math.log10(max(np.mean(self.ms_short), 1e-12))
        ref = -0.691 + 10.0 * math.log10(max(np.mean(self.ms_long), 1e-12))
        ref = ref if len(self.ms_long) >= int(2.0 * self.sr / HOP) and ref > -70 \
            else -90.0          # partial window fine; avoids a 30 s dark start
        # Window ~= the short-term loudness range of mastered music (~±3-6 LU);
        # much wider and level pegs at the top and brightness stops breathing.
        level_t = clamp(0.0, 1.0, (L - (ref - self.range_lu)) / self.range_lu)

        # ---- ERB band spectrum -> superflux onset detection ---------------
        # Böck & Widmer's formulation: log compression on raw magnitudes,
        # NO moving normalization — dividing by a per-frame mean or an EMA
        # reference manufactures phantom flux when the reference moves.
        band = self.W @ power
        lf = maximum_filter1d(np.log10(1.0 + 1000.0 * np.sqrt(band)), 3)
        odf = 0.0
        if self.prev_lf is not None:
            odf = float(np.maximum(lf - self.prev_lf, 0.0).sum())
        self.prev_lf = lf
        self.odf_hist.append(odf)
        med = float(np.median(self.odf_hist))
        thr = max(1.0, self.sensitivity * med)
        # adaptive whitening / echo rejection: one attack produces several
        # flux humps (attack, decay shoulders, release edges); an event must
        # carry 25% of the recent peak flux to count as its own onset
        self.odf_peak = max(odf, self.odf_peak * math.exp(-dt / 0.5))
        strength = odf / thr
        onset = (odf > thr and strength > 1.15
                 and odf > 0.25 * self.odf_peak
                 and now - self.last_onset > 0.12
                 and len(self.odf_hist) > 10)

        # ---- chroma (diagnostic; kept for the selftest's spectral check) ---
        cw = power
        cr = np.bincount(self.pc[self.cmask], weights=cw[self.cmask], minlength=12)
        self.chroma = 0.9 * self.chroma + 0.1 * (cr / max(cr.sum(), 1e-12))
        tonal = float((self.chroma.max() - self.chroma.mean()) /
                      (self.chroma.max() + 1e-12))

        # ---- spectral centroid (drives section color) -----------------------
        tot = float(power.sum())
        if tot > 1e-12:
            c = float((self.freqs * power).sum() / tot)
            self.centroid_slow += (1.0 - math.exp(-dt / 2.0)) * (c - self.centroid_slow)

        # ---- song boundaries (color changes live here) ----------------------
        # One hue per song: a silence gap of >= 2.5 s (track change) triggers
        # a new color from the new song's spectral character once it settles.
        # Within a song the hue holds — no pseudo-structural guessing.
        silent = L < -60.0
        section = False
        if silent and not self.was_silent:
            self.silence_start = now
        if not silent and self.was_silent and \
                now - self.silence_start > 2.5:
            self.section_pending = True
            self.section_t = now + 4.0     # let the new song establish first
            self.section_idx += 1
            section = True
        self.was_silent = silent
        # apply: on the next barline (musical), or once settled if beats lost
        if self.section_pending and now >= self.section_t:
            self._apply_section()
            self.section_pending = False

        # ---- beat clock: tempo scan + PLL ----------------------------------
        self.odf_env.append(odf)
        if (now - self.last_tempo_scan > 2.0
                and len(self.odf_env) >= int(4.0 * self.fps)):
            self.last_tempo_scan = now
            x = np.asarray(self.odf_env)
            x = x - x.mean()
            ac = np.correlate(x, x, "full")[len(x) - 1:]
            lags = np.arange(int(0.3 * self.fps), int(1.25 * self.fps))
            prior = np.exp(-0.5 * (np.log2(lags / (0.5 * self.fps))) ** 2)
            scores = ac[lags] * prior
            best = lags[np.argmax(scores)]
            # Octave correction / slow bias: onset grids gravitate to the
            # densest steady layer (subdivisions, compound pulses), which on
            # ballads is 1.5-2x the felt beat. When the half-tempo reading
            # has real support in the tempogram, take it — that is the level
            # a human conductor would choose.
            slow = 2 * best
            slow_score = ac[slow] * np.interp(slow, lags, prior) \
                if slow <= lags[-1] else 0.0
            octave_flip = slow_score > \
                (0.20 if self.slow_bias else 0.45) * scores.max()
            if octave_flip:
                best = slow
            est = best / self.fps
            if abs(est / self.beat_period - 1.0) < 0.25:
                self.beat_period = 0.7 * self.beat_period + 0.3 * est
            elif octave_flip:
                # deliberate metrical-level switch: jump and re-anchor the
                # grid phase (the blend guard above is for small drifts and
                # would otherwise veto every octave change forever)
                self.beat_period = est
                if self.next_beat is not None:
                    self.next_beat = now + est

        beat = False
        if onset:
            self.last_onset = now
            if self.next_beat is None:
                self.next_beat = now + self.beat_period
            else:
                # PLL: compare the onset to the nearest predicted beat
                ref = self.next_beat - self.beat_period
                k = round((now - ref) / self.beat_period)
                err = now - (ref + k * self.beat_period)
                good = abs(err) < 0.2 * self.beat_period
                self.beat_hits.append(good)
                self.confidence = sum(self.beat_hits) / len(self.beat_hits)
                if good:                     # nudge grid toward the truth
                    self.next_beat += 0.25 * err
                    self.beat_period *= 1.0 + 0.08 * err / self.beat_period
        if self.next_beat is not None and now >= self.next_beat:
            self.beat_idx += 1
            beat = True
            self.next_beat += self.beat_period
            if now - (self.next_beat - self.beat_period) > 0.5 * self.beat_period:
                self.next_beat = now + 0.5 * self.beat_period   # fell behind

        # ambient fallback: if the beat lock is poor the pulse dies (confidence
        # gate) — section-driven color keeps the light alive without it
        self.conf_ema += 0.05 * (self.confidence - self.conf_ema)

        # ---- brightness: slow macro + capped beat swell --------------------
        # No frame-level audio following: 3-30 Hz contrast flicker is the
        # uncomfortable band (IEEE 1789). One shallow swell per beat, decay
        # = half a beat; loudness only moves the base, slowly.
        self.level = level_t
        self.pulse *= math.exp(-dt / max(0.15, 0.5 * self.beat_period))
        # Base tops out at 85: the beat swell needs headroom to stay visible
        # in loud passages (a swell on a pegged 100% base is invisible).
        base = 10.0 + 75.0 * clamp(0.0, 1.0, self.level * self.gain) ** 0.6
        target = clamp(5.0, 100.0, base * (1.0 + self.pulse))
        tau = 0.25 if target > self.bri else 0.6
        self.bri += (1.0 - math.exp(-dt / tau)) * (target - self.bri)

        step = 120.0 * dt
        self.hue += clamp(self.hue_target - self.hue, -step, step)

        state = {"onset": onset, "strength": strength, "odf": odf, "L": L,
                 "ref": ref, "hue": self.hue, "bri": self.bri,
                 "sat": self.sat * clamp(0.35, 1.0, 0.35 + 1.3 * tonal),
                 "tonal": tonal, "level": self.level, "beat": beat,
                 "beat_idx": self.beat_idx,
                 "bpm": 60.0 / self.beat_period, "conf": self.conf_ema,
                 "section": section, "sect": self.section_idx}
        self.last = state
        return state

    def _apply_section(self):
        """Move the hue to the new section's spectral character: heavy/low
        centroid -> warm, airy/bright -> cool. One decisive move per section;
        the per-frame slew (120°/s) acts as the crossfade."""
        c = clamp(150.0, 4000.0, self.centroid_slow)
        t = (math.log2(c) - math.log2(150.0)) / \
            (math.log2(4000.0) - math.log2(150.0))
        self.hue_target = self.hue_min + t * (self.hue_max - self.hue_min)

    def apply_beat(self, beat_idx):
        """A predicted beat reached the listener's ears (audio-delay already
        applied): swell the light, and deliver pending section color changes
        on barlines so they stay musical."""
        if self.conf_ema < 0.15:
            return
        self.pulse = max(self.pulse, clamp(0.0, 0.35,
                                           0.10 + 0.25 * self.conf_ema))
        if self.section_pending and beat_idx % self.bars == 0:
            self._apply_section()
            self.section_pending = False


# ----------------------------------------------------------------- audio ----

def pick_input_device(want):
    if want:
        for i, d in enumerate(sd.query_devices()):
            if want.lower() in d["name"].lower() and d["max_input_channels"] > 0:
                return i
        sys.exit(f"no input device matching '{want}'. Try --list-devices.")
    for i, d in enumerate(sd.query_devices()):
        if "blackhole" in d["name"].lower() and d["max_input_channels"] > 0:
            return i
    sys.exit(
        "no BlackHole input device found.\n"
        "  brew install blackhole-2ch\n"
        "  then create a Multi-Output Device in Audio MIDI Setup\n"
        "  (or point --device at a real mic and play music out loud)."
    )


# ----------------------------------------------------------------- bulb ----

async def connect(args):
    """Return the list of bulbs to drive: every lamp found on the LAN, or
    the comma-separated --ip list."""
    if args.ip:
        bulbs = []
        for ip in (s.strip() for s in args.ip.split(",")):
            if not ip:
                continue
            b = wizlight(ip)
            await b.updateState()
            bulbs.append(b)
        print(f"[bulb] driving {len(bulbs)} lamp(s): "
              + ", ".join(b.ip for b in bulbs))
        return bulbs
    print("[bulb] searching the LAN for all WiZ lights...")
    bulbs = await discovery.discover_lights()
    if not bulbs:
        sys.exit("no WiZ lights found — check they're on the same Wi-Fi, "
                 "or pass --ip.")
    bulbs.sort(key=lambda b: str(b.ip))
    for b in bulbs:
        await b.updateState()
        print(f"[bulb] found {b.mac} at {b.ip}")
    return bulbs


async def set_pilot(bulbs, hue, sat, brightness, warned):
    """Send one pilot to every lamp concurrently; lamps fail independently."""
    r, g, b = colorsys.hsv_to_rgb(hue / 360.0, clamp(0, 1, sat), 1.0)

    async def one(bulb):
        try:
            await bulb.turn_on(PilotBuilder(
                rgb=(int(r * 255), int(g * 255), int(b * 255)),
                brightness=round(clamp(5, 100, brightness)),
            ))
            return None
        except Exception as e:
            return e

    results = await asyncio.gather(*(one(b) for b in bulbs))
    new_warned = dict(warned)
    for bulb, err in zip(bulbs, results):
        if err:
            if bulb.ip not in new_warned:
                print(f"\n[warn] {bulb.ip}: {err} (will keep retrying)")
                new_warned[bulb.ip] = err
        else:
            new_warned.pop(bulb.ip, None)
    return new_warned


# -------------------------------------------------------------- selftest ----

def selftest(args):
    """Feed synthetic ground truth through the DSP and check it behaves.

    Segment A (0-4 s):  click train, 120 BPM, amp 0.2
    Segment B (4-8 s):  same clicks, amp 0.8 -> pure x4 scaling = +12.04 LU
    Segment C (8-10.5 s): A-major chord (A3 A4 C#5 E5), no percussion
                        -> chroma must resolve to A (pc 9, C=0 convention)
    Loudness is compared in the POWER domain (log of mean of means), matching
    how the script computes L — never the mean of per-frame dB values.
    """
    sr = SR_REQUIRED
    fps = sr / HOP
    dsp = DSP(sr, sensitivity=args.sensitivity, gain=args.gain,
              range_lu=args.range, bars=args.bars,
              min_section=args.min_section, slow_bias=args.slow_bias,
              hue_min=args.hue_min, hue_max=args.hue_max, sat=args.sat)

    sig = np.zeros(int(10.5 * sr))
    click_times = np.arange(1, 16) * 0.5                        # 2 Hz = 120 BPM
    for tc in click_times:
        i0 = int(tc * sr)
        n = int(0.15 * sr)
        tt = np.arange(n) / sr
        amp = 0.2 if tc < 4.0 else 0.8
        # Raised-cosine edges: an abrupt mid-cycle cutoff of the sine is
        # itself a broadband transient — the detector faithfully reports it.
        env = tukey(n, alpha=0.13)
        sig[i0:i0 + n] += (amp * env * np.exp(-tt / 0.04)
                           * np.sin(2 * np.pi * 60 * tt))
    i0 = int(8.0 * sr)
    tt = np.arange(len(sig) - i0) / sr
    for f in (220.0, 440.0, 554.37, 659.26):                    # A major
        sig[i0:] += 0.1 * np.sin(2 * np.pi * f * tt)

    onsets, ms_frames = [], []
    for i in range(0, len(sig) - HOP, HOP):
        res = dsp.process(sig[i:i + HOP], i / sr)
        if res["onset"]:
            onsets.append(i / sr)
        ms_frames.append(10.0 ** (res["L"] / 10.0))
    dsp.process(np.zeros(HOP * 8), 10.5)                         # flush

    matched = sorted(min(abs(o - c) for c in click_times)
                     for o in onsets if o < 7.9)   # chord attack isn't scored
    jitter = [m for m in matched if m < 0.1]
    hits, spurious = len(jitter), len(matched) - len(jitter)

    msf = np.array(ms_frames)
    quiet = msf[int(1.0 * fps):int(3.5 * fps)].mean()
    loud = msf[int(4.5 * fps):int(7.5 * fps)].mean()
    dl = 10.0 * math.log10(loud / quiet)

    pc = int(np.argmax(dsp.chroma))
    print(f"[selftest] onsets: {hits}/{len(click_times)} hits, "
          f"{spurious} spurious, jitter {1000 * np.mean(jitter):.0f} ms")
    print(f"[selftest] loudness step (power domain): {dl:+.1f} LU "
          f"(theory: +12.0)")
    print(f"[selftest] chroma after chord: pc={pc} "
          f"({'A' if pc == 9 else 'expected A=9'}), "
          f"tonal={dsp.last['tonal']:.2f}")
    bpm = dsp.last["bpm"]
    print(f"[selftest] tempo: {bpm:.1f} BPM (truth: 120; half-tempo 60 is "
          f"an acceptable octave), PLL confidence {dsp.confidence:.2f}")
    tempo_ok = abs(bpm - 120.0) < 15.0 or abs(bpm - 60.0) < 8.0
    ok = (hits >= len(click_times) - 2 and spurious <= 2
          and abs(dl - 12.0) < 2.5 and pc == 9
          and tempo_ok and dsp.confidence > 0.4)
    print(f"[selftest] {'PASS' if ok else 'FAIL'}")
    return 0 if ok else 1


# ----------------------------------------------------------------- main ----

async def run(args):
    bulbs = await connect(args)

    if args.test:
        for hue in (0, 120, 240):
            await set_pilot(bulbs, hue, 1.0, 80, {})
            await asyncio.sleep(0.7)
        print(f"[test] OK — control path works for {len(bulbs)} lamp(s)")
        return

    dev = pick_input_device(args.device)
    sr = int(sd.query_devices(dev)["default_samplerate"])
    if sr != SR_REQUIRED:
        sys.exit(f"'{sd.query_devices(dev)['name']}' runs at {sr} Hz; "
                 f"K-weighting needs {SR_REQUIRED}. Set the multi-output "
                 "device sample rate to 48 kHz in Audio MIDI Setup.")
    print(f"[audio] capturing '{sd.query_devices(dev)['name']}' @ {sr} Hz")

    q = queue.Queue(maxsize=128)

    def callback(indata, frames, t, status):
        block = indata[:, 0].copy()
        try:
            q.put_nowait(block)
        except queue.Full:
            try:
                q.get_nowait()
                q.put_nowait(block)
            except queue.Empty:
                pass

    dsp = DSP(sr, sensitivity=args.sensitivity, gain=args.gain,
              range_lu=args.range, bars=args.bars,
              min_section=args.min_section, slow_bias=args.slow_bias,
              hue_min=args.hue_min, hue_max=args.hue_max, sat=args.sat)
    warned = {}

    try:
        with sd.InputStream(device=dev, channels=1, samplerate=sr,
                            blocksize=HOP, dtype="float32", callback=callback):
            print("[sync] listening — Ctrl+C to stop\n")
            audio_delay = args.audio_delay / 1000.0
            pending = []          # predicted beats waiting out speaker latency
            last_tx = 0.0
            shown_sect = 0
            while True:
                now = time.monotonic()
                try:
                    while True:
                        r = dsp.process(q.get_nowait(), now)
                        if r["beat"]:
                            pending.append((now + audio_delay, r["beat_idx"]))
                except queue.Empty:
                    pass
                while pending and pending[0][0] <= now:
                    _, idx = pending.pop(0)     # beat reaches the listener
                    dsp.apply_beat(idx)

                if now - last_tx >= TX_INTERVAL:
                    last_tx = now
                    s = dsp.last
                    if s["sect"] != shown_sect:
                        shown_sect = s["sect"]
                        print(f"\n  [section {s['sect']:2d} "
                              f"{time.strftime('%H:%M:%S')}] hue -> "
                              f"{s['hue']:3.0f}°")
                    warned = await set_pilot(bulbs, s["hue"], s["sat"],
                                             s["bri"], warned)
                    bar_pos = s["beat_idx"] % args.bars + 1
                    print(f"\r  {s['bpm']:5.1f}bpm b{bar_pos}/{args.bars} "
                          f"hue={s['hue']:3.0f}° bri={s['bri']:3.0f} "
                          f"c={s['conf']:4.2f}", end="", flush=True)
                await asyncio.sleep(0.004)
    except KeyboardInterrupt:
        print("\n[sync] stopped")
        try:
            await asyncio.gather(*(
                b.turn_on(PilotBuilder(colortemp=2700, brightness=80))
                for b in bulbs))
            print(f"[sync] {len(bulbs)} lamp(s) restored to warm white")
        except Exception:
            pass


async def main():
    p = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--ip", help="lamp IP(s), comma-separated (skips "
                                "discovery; default: drive all found)")
    p.add_argument("--device", help="audio input name substring (default: BlackHole)")
    p.add_argument("--sensitivity", type=float, default=1.2,
                   help="superflux threshold multiplier (default 1.2; "
                        "higher = fewer onsets)")
    p.add_argument("--gain", type=float, default=1.15,
                   help="brightness gain (default 1.15)")
    p.add_argument("--range", type=float, default=7.0, metavar="LU",
                   help="loudness window driving brightness (default 7 LU; "
                        "raise if brightness pegs, lower if it never gets "
                        "bright)")
    p.add_argument("--bars", type=int, default=4, metavar="N",
                   help="change hue every N beats (default 4 = one bar of "
                        "4/4; raise for slower color changes)")
    p.add_argument("--no-slow-bias", dest="slow_bias", action="store_false",
                   help="don't prefer the half-tempo beat; track the densest "
                        "pulse layer instead (default: slow bias on)")
    p.add_argument("--min-section", type=float, default=SECTION_DWELL_DEFAULT,
                   metavar="S",
                   help="minimum seconds between color changes (default 18; "
                        "lower = more colors per song)")
    p.add_argument("--audio-delay", type=float, default=0.0, metavar="MS",
                   help="delay beat reactions to match speaker latency "
                        "(BT ~150-250 ms; default 0)")
    p.add_argument("--sat", type=float, default=0.9, help="max saturation 0-1")
    p.add_argument("--hue-min", type=float, default=20.0,
                   help="hue for C on the circle of fifths (default 20)")
    p.add_argument("--hue-max", type=float, default=250.0,
                   help="hue for B on the circle of fifths (default 250)")
    p.add_argument("--test", action="store_true", help="blink R/G/B and exit")
    p.add_argument("--selftest", action="store_true",
                   help="run DSP verification against synthetic signals")
    p.add_argument("--list-devices", action="store_true")
    args = p.parse_args()

    if args.list_devices:
        print(sd.query_devices())
        return
    if args.selftest:
        sys.exit(selftest(args))

    try:
        await run(args)
    except KeyboardInterrupt:
        print("\n[sync] stopped")


if __name__ == "__main__":
    asyncio.run(main())
