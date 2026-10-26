"""
generate_audio.py - Master Audio Engineering Suite for Re:Focus Commercial
Broadcast Standard: 48,000 Hz, 16-bit Stereo PCM
Features:
- Sophisticated procedural cinematic synthesis (Sub-braams, Shepard tension, 808 groove, analog bassline)
- Tactile UI sound design (Haptic clicks, mechanical switches, resonant warning chimes, crystalline logo chord)
- Neural Voice-Over integration (Microsoft Christopher Neural)
- Broadcast sidechain ducking (-4.5dB under VO with smooth 120ms lookahead envelope)
- Exports both Master (VO + Music + SFX) and Clean Instrumental (Music + SFX)
"""

import os, math, subprocess
import numpy as np
import wave

SAMPLE_RATE = 48000
DURATION = 32.0 # 32.0 seconds exact master
TOTAL_SAMPLES = int(SAMPLE_RATE * DURATION)

def synthesize_master_soundtrack():
    print(f"Synthesizing Master Soundtrack: {DURATION}s @ {SAMPLE_RATE}Hz Stereo...")
    left = np.zeros(TOTAL_SAMPLES, dtype=np.float32)
    right = np.zeros(TOTAL_SAMPLES, dtype=np.float32)

    def add_sound(t_start, sig_l, sig_r=None):
        if sig_r is None:
            sig_r = sig_l
        idx = int(t_start * SAMPLE_RATE)
        length = len(sig_l)
        if idx >= TOTAL_SAMPLES or idx < 0:
            return
        if idx + length > TOTAL_SAMPLES:
            length = TOTAL_SAMPLES - idx
            sig_l = sig_l[:length]
            sig_r = sig_r[:length]
        left[idx:idx+length] += sig_l
        right[idx:idx+length] += sig_r

    # -----------------------------------------------------------------------
    # DSP & Sound Generators
    # -----------------------------------------------------------------------
    def create_sub_braam(duration=3.0, start_freq=95.0, end_freq=36.0, amp=0.85):
        num_s = int(duration * SAMPLE_RATE)
        t = np.linspace(0, duration, num_s, endpoint=False)
        freq = start_freq * np.exp(-t * 2.2) + end_freq * (1 - np.exp(-t * 2.2))
        phase = 2 * np.pi * np.cumsum(freq) / SAMPLE_RATE
        env = np.exp(-t * (3.5 / duration))
        sig = np.sin(phase) * env * amp
        # 2nd harmonic saturation for warmth
        sig += 0.28 * np.sin(phase * 2) * env * amp
        # 3rd harmonic for edge
        sig += 0.12 * np.sin(phase * 3) * env * amp
        return sig

    def create_ambient_drone(duration=7.5, freq=73.416, amp=0.35): # D2 drone
        num_s = int(duration * SAMPLE_RATE)
        t = np.linspace(0, duration, num_s, endpoint=False)
        # slow binaural beat
        f_left = freq
        f_right = freq + 1.2
        l = np.sin(2 * np.pi * f_left * t) * (0.8 + 0.2 * np.sin(2 * np.pi * 0.4 * t))
        r = np.sin(2 * np.pi * f_right * t) * (0.8 + 0.2 * np.cos(2 * np.pi * 0.4 * t))
        # envelope with smooth fade in/out
        env = np.ones(num_s, dtype=np.float32)
        fade_in = int(0.5 * SAMPLE_RATE)
        fade_out = int(1.2 * SAMPLE_RATE)
        env[:fade_in] = np.linspace(0, 1, fade_in)
        env[-fade_out:] = np.linspace(1, 0, fade_out)
        return l * env * amp, r * env * amp

    def create_clock_tick(is_rapid=False):
        dur = 0.025
        num_s = int(dur * SAMPLE_RATE)
        t = np.linspace(0, dur, num_s, endpoint=False)
        freq = 2400 if is_rapid else 1800
        env = np.exp(-t * 180.0)
        noise = np.random.uniform(-1, 1, num_s) * np.exp(-t * 260.0) * 0.3
        return (np.sin(2 * np.pi * freq * t) * env * 0.7 + noise) * 0.4

    def create_kick():
        dur = 0.32
        num_s = int(dur * SAMPLE_RATE)
        t = np.linspace(0, dur, num_s, endpoint=False)
        pitch = 160 * np.exp(-t * 32.0) + 48
        phase = 2 * np.pi * np.cumsum(pitch) / SAMPLE_RATE
        env = np.exp(-t * 14.0)
        click = np.random.uniform(-1, 1, num_s) * np.exp(-t * 140.0) * 0.45
        return (np.sin(phase) * env + click) * 0.85

    def create_snare():
        dur = 0.24
        num_s = int(dur * SAMPLE_RATE)
        t = np.linspace(0, dur, num_s, endpoint=False)
        noise = np.random.uniform(-1, 1, num_s)
        env = np.exp(-t * 20.0)
        tone = np.sin(2 * np.pi * 220 * t) * np.exp(-t * 26.0)
        return (noise * env * 0.65 + tone * 0.35) * 0.6

    def create_hihat(open_hh=False):
        dur = 0.14 if open_hh else 0.045
        num_s = int(dur * SAMPLE_RATE)
        t = np.linspace(0, dur, num_s, endpoint=False)
        noise = np.random.uniform(-1, 1, num_s)
        decay = 16.0 if open_hh else 65.0
        return noise * np.exp(-t * decay) * 0.24

    def create_bass_pluck(freq=73.416, dur=0.45):
        num_s = int(dur * SAMPLE_RATE)
        t = np.linspace(0, dur, num_s, endpoint=False)
        # filtered sawtooth
        sig = np.zeros(num_s, dtype=np.float32)
        for h in range(1, 6):
            sig += (1.0 / h) * np.sin(2 * np.pi * (freq * h) * t)
        env = np.exp(-t * 6.5)
        return sig * env * 0.4

    def create_tactile_click(is_high=False):
        dur = 0.032
        num_s = int(dur * SAMPLE_RATE)
        t = np.linspace(0, dur, num_s, endpoint=False)
        freq = 2200 if is_high else 1450
        env = np.exp(-t * 160.0)
        return np.sin(2 * np.pi * freq * t) * env * 0.45

    def create_warning_chime():
        dur = 1.4
        num_s = int(dur * SAMPLE_RATE)
        t = np.linspace(0, dur, num_s, endpoint=False)
        # A4 (440Hz) and C#5 (554.37Hz) dual tone
        tone1 = np.sin(2 * np.pi * 440 * t) * np.exp(-t * 3.0)
        tone2 = np.sin(2 * np.pi * 554.37 * t) * np.exp(-t * 3.5)
        # subtle upper octave
        tone3 = np.sin(2 * np.pi * 880 * t) * np.exp(-t * 5.0) * 0.3
        return (tone1 + tone2 + tone3) * 0.45

    def create_switch_latch():
        dur = 0.08
        num_s = int(dur * SAMPLE_RATE)
        t = np.linspace(0, dur, num_s, endpoint=False)
        snap1 = np.sin(2 * np.pi * 1800 * t) * np.exp(-t * 220.0)
        snap2 = np.sin(2 * np.pi * 900 * t) * np.exp(-t * 140.0)
        return (snap1 + snap2) * 0.55

    def create_whoosh(duration=0.5, sweep_up=True):
        num_s = int(duration * SAMPLE_RATE)
        t = np.linspace(0, duration, num_s, endpoint=False)
        noise = np.random.uniform(-1, 1, num_s)
        env = np.sin(np.pi * t / duration) ** 2 if sweep_up else np.exp(-t * 5.5)
        # stereo pan sweep
        pan = np.linspace(-0.6, 0.6, num_s) if sweep_up else np.linspace(0.6, -0.6, num_s)
        l = noise * env * (0.5 - pan * 0.5) * 0.35
        r = noise * env * (0.5 + pan * 0.5) * 0.35
        return l, r

    def create_crystal_chime(freq=1760.0, duration=2.2):
        num_s = int(duration * SAMPLE_RATE)
        t = np.linspace(0, duration, num_s, endpoint=False)
        chime = np.sin(2 * np.pi * freq * t) * np.exp(-t * 2.8)
        chime += 0.5 * np.sin(2 * np.pi * (freq * 1.5) * t) * np.exp(-t * 3.8)
        chime += 0.3 * np.sin(2 * np.pi * (freq * 2.0) * t) * np.exp(-t * 4.8)
        return chime * 0.4

    def create_brand_chord(duration=4.2):
        num_s = int(duration * SAMPLE_RATE)
        t = np.linspace(0, duration, num_s, endpoint=False)
        # D Major triad with 9th: D3 (146.8), A3 (220), F#4 (369.99), E5 (659.25), A5 (880)
        notes = [146.83, 220.00, 369.99, 659.25, 880.00]
        sig = np.zeros(num_s, dtype=np.float32)
        for idx, n in enumerate(notes):
            decay = 1.4 + idx * 0.35
            sig += np.sin(2 * np.pi * n * t) * np.exp(-t * decay) * (1.0 / (idx + 1))
        # smooth shimmer modulation
        shimmer = 1.0 + 0.15 * np.sin(2 * np.pi * 5.5 * t)
        return sig * shimmer * 0.6

    # -----------------------------------------------------------------------
    # Audio Timeline Assembly
    # -----------------------------------------------------------------------
    # SCENE 1: THE HOOK (0.0s – 3.2s)
    add_sound(0.0, create_sub_braam(duration=3.2, start_freq=110, end_freq=38, amp=0.9))
    drone_l, drone_r = create_ambient_drone(duration=7.5, freq=73.416, amp=0.35)
    add_sound(0.0, drone_l, drone_r)
    
    # Atmospheric clock ticks
    for tick_t in [0.8, 1.6, 2.4]:
        add_sound(tick_t, create_clock_tick(is_rapid=False) * 0.4)

    # SCENE 2: THE PROBLEM (3.2s – 7.5s)
    # Accelerating clock ticks
    t_curr = 3.2
    tick_interval = 0.4
    while t_curr < 7.4:
        add_sound(t_curr, create_clock_tick(is_rapid=True) * 0.65)
        t_curr += tick_interval
        tick_interval = max(0.08, tick_interval * 0.88) # exponential acceleration!

    # Sub-riser before drop
    riser_dur = 2.4
    riser_s = int(riser_dur * SAMPLE_RATE)
    riser_t = np.linspace(0, riser_dur, riser_s, endpoint=False)
    riser_freq = 120 + 800 * (riser_t / riser_dur) ** 2
    riser_sig = np.sin(2 * np.pi * np.cumsum(riser_freq) / SAMPLE_RATE) * (riser_t / riser_dur) * 0.35
    add_sound(5.1, riser_sig)

    # SCENE 3: THE INTERVENTION (7.5s – 9.8s)
    # 7.5s: Complete vacuum cut!
    # 7.6s: Sub-drop impact
    add_sound(7.6, create_sub_braam(duration=2.2, start_freq=85, end_freq=32, amp=0.75))
    # 7.7s: Crystalline chime on lime iris reveal
    add_sound(7.7, create_crystal_chime(freq=1760.0, duration=2.5))
    wh_l, wh_r = create_whoosh(0.5, sweep_up=False)
    add_sound(7.55, wh_l, wh_r)

    # SCENE 4: PRODUCT REVEAL & GROOVE (9.8s – 15.8s)
    # 122 BPM = 0.4918s per beat
    beat_dur = 60.0 / 122.0
    start_groove = 9.8
    end_groove = 26.8

    # Drum loop & Bassline
    kick = create_kick()
    snare = create_snare()
    hh_c = create_hihat(open_hh=False)
    hh_o = create_hihat(open_hh=True)

    beat_idx = 0
    t_beat = start_groove
    bass_notes = [73.42, 73.42, 87.31, 98.00] # D2, D2, F2, G2

    while t_beat < end_groove:
        # Kick on 1 and 3
        if beat_idx % 4 == 0 or beat_idx % 4 == 2:
            add_sound(t_beat, kick)
        # Snare on 2 and 4
        if beat_idx % 4 == 1 or beat_idx % 4 == 3:
            add_sound(t_beat, snare)
        # Hi-hats on 8ths & 16ths
        add_sound(t_beat, hh_c)
        add_sound(t_beat + beat_dur * 0.5, hh_o if beat_idx % 4 == 2 else hh_c)

        # Bassline
        bn = bass_notes[(beat_idx // 2) % len(bass_notes)]
        add_sound(t_beat, create_bass_pluck(freq=bn, dur=beat_dur * 0.85))

        beat_idx += 1
        t_beat = start_groove + beat_idx * beat_dur

    # Tactile Haptic Counter Clicks (Scene 4)
    # Swiping reels 47 -> 48 -> 49 -> 50
    add_sound(11.4, create_tactile_click(is_high=False) * 1.5)
    add_sound(13.1, create_tactile_click(is_high=False) * 1.5)
    add_sound(14.8, create_tactile_click(is_high=True) * 1.8)

    # SCENE 5: LIMIT REACHED & STRICT MODE (15.8s – 21.2s)
    # Warning chime at 15.8s
    add_sound(15.8, create_warning_chime())
    # Strict mode toggle snap at 17.6s
    add_sound(17.6, create_switch_latch() * 1.6)
    # Feed auto-close exit whoosh at 19.3s
    wh2_l, wh2_r = create_whoosh(0.55, sweep_up=False)
    add_sound(19.3, wh2_l * 1.2, wh2_r * 1.2)
    add_sound(19.3, create_sub_braam(1.8, start_freq=95, end_freq=38, amp=0.6))

    # SCENE 6: CONSISTENCY & TRANSFORMATION (21.2s – 26.8s)
    # Uplifting shimmer at 21.8s
    add_sound(21.8, create_crystal_chime(freq=2093.0, duration=3.0) * 0.8)
    wh3_l, wh3_r = create_whoosh(0.45, sweep_up=True)
    add_sound(23.6, wh3_l, wh3_r)

    # SCENE 7: BRAND REVEAL & CTA (26.8s – 32.0s)
    # Music stops at 26.8s. Pure stillness.
    wh4_l, wh4_r = create_whoosh(0.45, sweep_up=False)
    add_sound(26.85, wh4_l * 0.8, wh4_r * 0.8)
    # 27.6s: Brand Chord & Sub-drop
    add_sound(27.6, create_brand_chord(duration=4.4))
    add_sound(27.6, create_sub_braam(duration=3.8, start_freq=80, end_freq=32, amp=0.85))
    add_sound(28.2, create_crystal_chime(freq=2637.0, duration=2.8) * 0.7)

    # Normalization & Master Limiting
    max_val = max(np.max(np.abs(left)), np.max(np.abs(right)), 1e-4)
    if max_val > 0.94:
        left = (left / max_val) * 0.94
        right = (right / max_val) * 0.94

    os.makedirs('video_production/output', exist_ok=True)
    music_out = 'video_production/output/music_and_sfx.wav'
    with wave.open(music_out, 'wb') as wf:
        wf.setnchannels(2)
        wf.setsampwidth(2)
        wf.setframerate(SAMPLE_RATE)
        stereo = np.empty((TOTAL_SAMPLES * 2,), dtype=np.int16)
        stereo[0::2] = (left * 32767).astype(np.int16)
        stereo[1::2] = (right * 32767).astype(np.int16)
        wf.writeframes(stereo.tobytes())

    print(f"Generated Music & SFX stem: {music_out}")
    return music_out

def assemble_master_audio():
    music_wav = synthesize_master_soundtrack()

    vo_cues = [
        ("vo_01_hook.mp3", 0.2),
        ("vo_02_problem.mp3", 3.3),
        ("vo_03_intervention.mp3", 7.7),
        ("vo_04_counter.mp3", 9.9),
        ("vo_05_limits.mp3", 15.9),
        ("vo_06_transformation.mp3", 21.3),
        ("vo_07_brand.mp3", 27.0)
    ]

    print("Decoding neural voiceover clips & building VO track...")
    vo_track = np.zeros((TOTAL_SAMPLES, 2), dtype=np.float32)

    for fname, start_t in vo_cues:
        mp3_path = os.path.join('video_production/output/vo', fname)
        if not os.path.exists(mp3_path):
            print(f"Warning: {mp3_path} not found!")
            continue

        # Decode MP3 to raw PCM WAV via ffmpeg pipe
        cmd = ['ffmpeg', '-y', '-i', mp3_path, '-f', 's16le', '-ac', '2', '-ar', str(SAMPLE_RATE), '-']
        raw_pcm = subprocess.check_output(cmd, stderr=subprocess.DEVNULL)
        data = np.frombuffer(raw_pcm, dtype=np.int16).astype(np.float32).reshape(-1, 2) / 32768.0

        start_idx = int(start_t * SAMPLE_RATE)
        end_idx = min(TOTAL_SAMPLES, start_idx + len(data))
        actual_len = end_idx - start_idx
        vo_track[start_idx:end_idx] += data[:actual_len] * 0.98

    # Load synthesized music
    with wave.open(music_wav, 'rb') as mw:
        raw_music = mw.readframes(mw.getnframes())
        music_data = np.frombuffer(raw_music, dtype=np.int16).astype(np.float32).reshape(-1, 2) / 32768.0

    # 1. Clean Instrumental Mix
    clean_path = 'video_production/output/clean_audio_instrumental.wav'
    with wave.open(clean_path, 'wb') as cw:
        cw.setnchannels(2)
        cw.setsampwidth(2)
        cw.setframerate(SAMPLE_RATE)
        stereo_clean = np.empty((TOTAL_SAMPLES * 2,), dtype=np.int16)
        stereo_clean[0::2] = (np.clip(music_data[:, 0], -1.0, 1.0) * 32767).astype(np.int16)
        stereo_clean[1::2] = (np.clip(music_data[:, 1], -1.0, 1.0) * 32767).astype(np.int16)
        cw.writeframes(stereo_clean.tobytes())

    # 2. Master Mix with Adaptive Sidechain Ducking
    print("Applying sidechain ducking (-4.5dB under voiceover)...")
    vo_envelope = np.maximum(np.abs(vo_track[:, 0]), np.abs(vo_track[:, 1]))
    # Smooth 120ms Gaussian window for natural ducking
    kernel_size = int(SAMPLE_RATE * 0.12)
    kernel = np.hamming(kernel_size)
    kernel /= np.sum(kernel)
    vo_smooth = np.convolve(vo_envelope, kernel, mode='same')
    duck_gain = 1.0 - np.clip(vo_smooth * 1.6, 0.0, 0.42) # Duck up to -4.5dB

    mixed_l = music_data[:, 0] * duck_gain + vo_track[:, 0] * 1.15
    mixed_r = music_data[:, 1] * duck_gain + vo_track[:, 1] * 1.15

    # Peak soft limiter
    max_m = max(np.max(np.abs(mixed_l)), np.max(np.abs(mixed_r)), 1e-4)
    if max_m > 0.95:
        mixed_l = (mixed_l / max_m) * 0.95
        mixed_r = (mixed_r / max_m) * 0.95

    master_path = 'video_production/output/master_audio_with_vo.wav'
    with wave.open(master_path, 'wb') as mwf:
        mwf.setnchannels(2)
        mwf.setsampwidth(2)
        mwf.setframerate(SAMPLE_RATE)
        stereo_master = np.empty((TOTAL_SAMPLES * 2,), dtype=np.int16)
        stereo_master[0::2] = (mixed_l * 32767).astype(np.int16)
        stereo_master[1::2] = (mixed_r * 32767).astype(np.int16)
        mwf.writeframes(stereo_master.tobytes())

    print("Master Audio Assembly Complete:")
    print("  ->", master_path)
    print("  ->", clean_path)

if __name__ == '__main__':
    assemble_master_audio()
