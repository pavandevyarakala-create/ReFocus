# Re:Focus Video Advertisement — Master Production Suite

This directory contains the complete, production-grade video rendering pipeline for the **Re:Focus by FOCRAMEX** commercial campaign.

The commercial was planned, art-directed, programmatically animated, and mastered to the visual standards of leading technology campaigns (Apple, Linear, Notion) using the authentic Re:Focus codebase, design tokens, Sora typography, and real Android application features.

---

## 1. Directory Structure

```
video_production/
├── assets/
│   ├── fonts/
│   │   ├── sora_bold.ttf           # Official Sora Display typeface
│   │   ├── sora_semibold.ttf
│   │   ├── sora_medium.ttf
│   │   └── sora_regular.ttf
│   ├── logos/
│   │   ├── ic_refocus_clock.png    # Crescent Moon Clock element
│   │   ├── ic_refocus_figure.png   # Reaching / Leaping Figure
│   │   ├── ic_refocus_icon.png     # Full brand icon
│   │   ├── ic_refocus_logo.png     # Horizontal lockup (Icon + Wordmark)
│   │   ├── ic_refocus_wordmark.png # Official Re:Focus wordmark
│   │   └── source_logo.png        # Master high-resolution brand asset
│   ├── scrolling_hook_bg.jpg       # Atmospheric bedroom doomscrolling backdrop
│   └── studio_dark_bg.jpg          # Deep luxury dark studio environment
├── output/
│   ├── refocus_ad_master_with_audio.mp4  # 32.0s 9:16 Vertical Master (VO + Music + SFX)
│   ├── refocus_ad_master_clean.mp4       # 32.0s 9:16 Vertical Clean (Music + SFX only)
│   ├── refocus_ad_15s_cutdown.mp4        # 15.0s 9:16 Short-Form Performance Cut
│   ├── refocus_ad_16x9_widescreen.mp4    # 32.0s 16:9 Widescreen Master (Redesigned layout)
│   ├── refocus_thumbnail.png             # 1080x1920 Lossless High-CTR Video Thumbnail
│   ├── refocus_thumbnail.jpg             # 1080x1920 Compressed Video Thumbnail
│   ├── master_audio_with_vo.wav          # 48.0kHz 16-bit Stereo Master Audio Stem
│   ├── clean_audio_instrumental.wav      # 48.0kHz 16-bit Stereo Clean Audio Stem
│   ├── music_and_sfx.wav                 # 48.0kHz Synthesized Soundtrack & UI SFX Stem
│   └── vo/                               # High-Fidelity Neural Voiceover clips (7 stems)
└── scripts/
    ├── ui_components.py          # Authentic Re:Focus UI components & phone mockup renderer
    ├── generate_audio.py         # 48kHz audio synthesis, sound design & sidechain mixer
    ├── render_master_video.py    # 32.0s 9:16 vertical master video renderer (960 frames)
    ├── render_cutdown_15s.py     # 15.0s 9:16 vertical short-form performance renderer (450 frames)
    ├── render_widescreen_16x9.py # 32.0s 16:9 widescreen master video renderer (960 frames)
    └── generate_thumbnail.py     # High-CTR thumbnail generator
```

---

## 2. Master Deliverables Matrix

| Deliverable Asset | Aspect Ratio | Resolution | FPS | Codec | Audio | Duration | Description |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Master Vertical Video** | 9:16 | 1080 × 1920 | 30.0 | H.264 (CRF 17) | AAC 48kHz Stereo | 32.00s | Full commercial with neural voiceover, music & SFX |
| **Clean Vertical Video** | 9:16 | 1080 × 1920 | 30.0 | H.264 (CRF 17) | AAC 48kHz Stereo | 32.00s | Instrumental music & tactile SFX only |
| **15-Second Cutdown** | 9:16 | 1080 × 1920 | 30.0 | H.264 (CRF 17) | AAC 48kHz Stereo | 15.00s | High-velocity cut for TikTok & Instagram Reels ads |
| **16:9 Widescreen Master** | 16:9 | 1920 × 1080 | 30.0 | H.264 (CRF 17) | AAC 48kHz Stereo | 32.00s | Redesigned split-screen layout for YouTube / Web |
| **High-CTR Thumbnail** | 9:16 | 1080 × 1920 | Still | PNG / JPEG | N/A | Still | High-contrast hero device with live floating counter |

---

## 3. How to Reproduce the Renders

### Prerequisites
- Python 3.10+
- FFmpeg installed and available on PATH
- Virtual environment (`.venv`) with `pillow`, `numpy`, `edge-tts`

### Reproduction Commands
```bash
# 1. Generate Voiceover & Master Audio Stems (48kHz)
.\.venv\Scripts\python.exe video_production/scripts/generate_audio.py

# 2. Render 32-Second Master Commercial (9:16 Vertical)
.\.venv\Scripts\python.exe video_production/scripts/render_master_video.py

# 3. Render 15-Second Cutdown Video
.\.venv\Scripts\python.exe video_production/scripts/render_cutdown_15s.py

# 4. Render 32-Second 16:9 Widescreen Master Video
.\.venv\Scripts\python.exe video_production/scripts/render_widescreen_16x9.py

# 5. Generate Lossless and JPEG Thumbnails
.\.venv\Scripts\python.exe video_production/scripts/generate_thumbnail.py
```

---

## 4. Brand Design Tokens Utilized
All colors, metrics, and typographic hierarchies directly match `com.reeltrak.app.core.ui.theme`:
- **Signature Muted Lime:** `#B8D94E` (`ReFocusLimePrimary`)
- **Warm Terracotta Warning:** `#C86D3B` (`ReFocusWarningLightAccent`)
- **Warm Ivory Background:** `#F7F6F0` (`ReFocusLightBackground`)
- **Deep Near-Black:** `#090A0C` (`ReFocusDarkBackground`)
- **Startup Splash Canvas:** `#FCF4E9` / `#302516` (`ReFocusSplashScreen.kt`)
- **Typography:** Sora (Regular, Medium, SemiBold, Bold)
- **Signature Signature:** `Re:Focus by FOCRAMEX`
