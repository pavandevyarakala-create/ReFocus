"""
render_widescreen_16x9.py - Dedicated 16:9 Widescreen Commercial Renderer
Properly redesigned horizontal composition (1920x1080 @ 30fps, 32.0s = 960 frames).
Features:
- Split layout: Left-column kinetic typography & copy, Right-column 3D smartphone mockup
- Authentic Re:Focus design tokens, Sora typography, and authentic Android UI
- Direct FFmpeg pipeline encoding to H.264 (CRF 17) + AAC 48kHz stereo.
"""

import os, sys, math, subprocess, time
from PIL import Image, ImageDraw, ImageFont, ImageFilter
import numpy as np

sys.path.append('video_production/scripts')
from ui_components import (
    get_font, LIME_PRIMARY, LIME_PRESSED, LIME_DARK,
    LIGHT_BG, LIGHT_SURFACE, LIGHT_SURFACE_SOFT, LIGHT_BORDER,
    TEXT_PRIMARY, TEXT_SECONDARY, TEXT_MUTED,
    DARK_BG, DARK_SURFACE, DARK_BORDER,
    WARNING_CORAL, WARNING_SOFT, WARNING_BORDER,
    SPLASH_BG, SPLASH_CONTENT,
    render_floating_counter, render_phone_screen_dashboard,
    render_reels_feed_screen, render_calendar_screen,
    render_analytics_screen, render_strict_mode_shield_overlay,
    render_smartphone_mockup
)

WIDTH = 1920
HEIGHT = 1080
FPS = 30
DURATION = 32.0
TOTAL_FRAMES = int(FPS * DURATION) # 960 frames

HOOK_BG_PATH = 'video_production/assets/scrolling_hook_bg.jpg'
FIGURE_PATH = 'video_production/assets/logos/ic_refocus_figure.png'
CLOCK_PATH = 'video_production/assets/logos/ic_refocus_clock.png'
LOGO_PATH = 'video_production/assets/logos/ic_refocus_logo.png'

hook_bg_img = Image.open(HOOK_BG_PATH).convert('RGB')
figure_img = Image.open(FIGURE_PATH).convert('RGBA')
clock_img = Image.open(CLOCK_PATH).convert('RGBA')
logo_img = Image.open(LOGO_PATH).convert('RGBA')

# Pre-render 1920x1080 luxury studio background
studio_16x9 = Image.new('RGB', (WIDTH, HEIGHT), (9, 10, 12))
sdraw = ImageDraw.Draw(studio_16x9)
cx, cy = int(WIDTH * 0.70), HEIGHT // 2
for r in range(1200, 0, -8):
    fac = 1.0 - (r / 1200.0)
    sdraw.ellipse([cx - r, cy - r, cx + r, cy + r], fill=(int(9 + 20 * fac**2), int(10 + 24 * fac**2), int(12 + 32 * fac**2)))

# Pre-render screens & mockups scaled for 1080 height (scale=0.68)
dash_49 = render_phone_screen_dashboard(daily_limit=50, reels_watched=49, strict_mode_on=False)
dash_50 = render_phone_screen_dashboard(daily_limit=50, reels_watched=50, strict_mode_on=True)
cal_scr = render_calendar_screen(highlight_progress=0.85)
ana_scr = render_analytics_screen()

shield_scr = dash_50.copy()
shield_ov = render_strict_mode_shield_overlay()
shield_scr.paste(shield_ov, (0, 0), shield_ov)

feed_47 = render_reels_feed_screen(reel_number=0, counter_visible=True, counter_val=47, counter_over_limit=False)
feed_48 = render_reels_feed_screen(reel_number=1, counter_visible=True, counter_val=48, counter_over_limit=False, pulse_glow=True)
feed_49 = render_reels_feed_screen(reel_number=2, counter_visible=True, counter_val=49, counter_over_limit=False, pulse_glow=True)
feed_50 = render_reels_feed_screen(reel_number=3, counter_visible=True, counter_val=50, counter_over_limit=True, pulse_glow=True)

# 16:9 phone mockups scaled to fit 1080p height nicely
MOCK_SCALE = 0.65
mockup_dash_49 = render_smartphone_mockup(dash_49, scale=MOCK_SCALE)
mockup_dash_50 = render_smartphone_mockup(dash_50, scale=MOCK_SCALE)
mockup_cal = render_smartphone_mockup(cal_scr, scale=MOCK_SCALE)
mockup_ana = render_smartphone_mockup(ana_scr, scale=MOCK_SCALE)
mockup_shield = render_smartphone_mockup(shield_scr, scale=MOCK_SCALE)

mockup_feed_47 = render_smartphone_mockup(feed_47, scale=MOCK_SCALE)
mockup_feed_48 = render_smartphone_mockup(feed_48, scale=MOCK_SCALE)
mockup_feed_49 = render_smartphone_mockup(feed_49, scale=MOCK_SCALE)
mockup_feed_50 = render_smartphone_mockup(feed_50, scale=MOCK_SCALE)

def ease_out_cubic(x):
    return 1.0 - pow(1.0 - max(0.0, min(1.0, x)), 3.0)

def draw_pill_badge_16x9(draw, x, y, text, font, bg_color, text_color, border_color=None):
    w = int(draw.textlength(text, font=font) + 36)
    h = 42
    box = [x, y, x + w, y + h]
    draw.rounded_rectangle(box, radius=h//2, fill=bg_color, outline=border_color, width=1)
    draw.text((x + w // 2, y + h // 2), text, font=font, fill=text_color, anchor='mm')

def render_frame_16x9(frame_idx):
    canvas = Image.new('RGB', (WIDTH, HEIGHT), (9, 10, 12))
    draw = ImageDraw.Draw(canvas)
    t = frame_idx / float(FPS)

    # SCENE 1: THE HOOK (0.0s – 3.2s | Frames 0 to 95)
    if frame_idx < 96:
        # Cinematic widescreen hook background
        scale = 1.0 + (frame_idx / 96.0) * 0.12
        crop_w = int(WIDTH / scale)
        crop_h = int(HEIGHT / scale)
        crop_box = (hook_bg_img.width // 2 - crop_w // 2, hook_bg_img.height // 2 - crop_h // 2,
                     hook_bg_img.width // 2 + crop_w // 2, hook_bg_img.height // 2 + crop_h // 2)
        # Pad hook_bg to 16:9
        hook_16x9 = hook_bg_img.resize((WIDTH, int(hook_bg_img.height * (WIDTH / hook_bg_img.width))))
        crop_y = (hook_16x9.height - HEIGHT) // 2
        canvas.paste(hook_16x9.crop((0, crop_y, WIDTH, crop_y + HEIGHT)), (0, 0))

        # Dark overlay
        overlay = Image.new('RGBA', (WIDTH, HEIGHT), (0, 0, 0, 160))
        canvas.paste(overlay, (0, 0), overlay)

        draw = ImageDraw.Draw(canvas)
        if frame_idx < 32:
            draw.text((WIDTH // 2, 500), "ONE MORE REEL?", font=get_font('bold', 84), fill=(245, 244, 238), anchor='mm')
            draw.text((WIDTH // 2, 590), "Just 5 minutes, right?", font=get_font('semibold', 32), fill=(184, 217, 78), anchor='mm')
        elif frame_idx < 64:
            draw.text((WIDTH // 2, 500), "THEN ANOTHER.", font=get_font('bold', 84), fill=(222, 139, 111), anchor='mm')
            draw.text((WIDTH // 2, 590), "Swipe after swipe...", font=get_font('semibold', 32), fill=(245, 244, 238), anchor='mm')
        else:
            draw.text((WIDTH // 2, 500), "AND ANOTHER.", font=get_font('bold', 84), fill=(200, 109, 59), anchor='mm')
            draw.text((WIDTH // 2, 590), "The infinite scroll won't stop.", font=get_font('semibold', 32), fill=(245, 244, 238), anchor='mm')

    # SCENE 2: THE PROBLEM (3.2s – 7.5s | Frames 96 to 224)
    elif frame_idx < 225:
        canvas.paste(studio_16x9, (0, 0))
        draw = ImageDraw.Draw(canvas)
        p = (frame_idx - 96) / 128.0

        times = ["11:45 PM", "12:30 AM", "1:15 AM", "2:05 AM", "2:50 AM", "3:30 AM"]
        curr_time = times[min(len(times)-1, int(p * len(times)))]

        # Left Column: Typography
        draw.text((140, 360), "YOU LOSE TRACK.", font=get_font('bold', 76), fill=(245, 244, 238), anchor='lm')
        draw.text((140, 440), "Hours disappear into infinite feeds.", font=get_font('medium', 32), fill=(168, 170, 169), anchor='lm')

        draw_pill_badge_16x9(draw, 140, 520, f"TIME: {curr_time}", get_font('bold', 24), (24, 27, 34), (245, 244, 238), border_color=(58, 63, 76))
        
        # Right Column: Big Counter HUD
        box_w, box_h = 540, 220
        rx, ry = 1180, 420
        draw.rounded_rectangle([rx, ry, rx + box_w, ry + box_h], radius=24, fill=(28, 18, 16), outline=(200, 109, 59), width=2)
        draw.text((rx + box_w // 2, ry + 50), "REELS CONSUMED", font=get_font('bold', 20), fill=(200, 109, 59), anchor='mm')
        reels_c = min(147, int(42 + p * 105))
        draw.text((rx + box_w // 2, ry + 130), f"{reels_c}", font=get_font('bold', 68), fill=(245, 244, 238), anchor='mm')

    # SCENE 3: THE INTERVENTION (7.5s – 9.8s | Frames 225 to 293)
    elif frame_idx < 294:
        p = (frame_idx - 225) / 69.0
        if p < 0.12:
            flash_a = int(255 * (1.0 - p / 0.12))
            canvas = Image.new('RGB', (WIDTH, HEIGHT), (flash_a, flash_a, flash_a))
        else:
            canvas.paste(studio_16x9, (0, 0))
            draw = ImageDraw.Draw(canvas)
            sub_p = (p - 0.12) / 0.88

            draw.text((WIDTH // 2, 440), "TAKE BACK YOUR ATTENTION.", font=get_font('bold', 68), fill=(245, 244, 238), anchor='mm')
            draw.text((WIDTH // 2, 530), "Real-time on-device awareness for Android.", font=get_font('medium', 32), fill=(184, 217, 78), anchor='mm')
            draw_pill_badge_16x9(draw, WIDTH // 2 - 280, 620, "ZERO CLOUD TRACKING • 100% PRIVATE • LOCAL ONLY", get_font('bold', 20), (28, 35, 20), LIME_PRIMARY, border_color=LIME_PRIMARY)

    # SCENE 4: PRODUCT REVEAL & LIVE COUNTER (9.8s – 15.8s | Frames 294 to 473)
    elif frame_idx < 474:
        canvas.paste(studio_16x9, (0, 0))
        draw = ImageDraw.Draw(canvas)

        # Left Column: Headlines & Benefits
        draw.text((140, 320), "SEE EVERY REEL.", font=get_font('bold', 68), fill=(245, 244, 238), anchor='lm')
        draw.text((140, 390), "Stay aware as you browse.", font=get_font('medium', 32), fill=(184, 217, 78), anchor='lm')
        draw.text((140, 470), "Re:Focus overlays a live floating counter over", font=get_font('regular', 22), fill=(168, 170, 169), anchor='lm')
        draw.text((140, 505), "Instagram Reels and YouTube Shorts automatically.", font=get_font('regular', 22), fill=(168, 170, 169), anchor='lm')

        draw_pill_badge_16x9(draw, 140, 590, "LIVE FLOATING COUNTER OVERLAY", get_font('bold', 20), (24, 27, 34), (245, 244, 238), border_color=(58, 63, 76))
        draw_pill_badge_16x9(draw, 140, 650, "100% ON-DEVICE ACCESSIBILITY ENGINE", get_font('bold', 20), (28, 35, 20), LIME_PRIMARY, border_color=LIME_PRIMARY)

        # Right Column: Phone Mockup
        if frame_idx < 342:
            phone_img = mockup_feed_47
        elif frame_idx < 393:
            phone_img = mockup_feed_48
        elif frame_idx < 444:
            phone_img = mockup_feed_49
        else:
            phone_img = mockup_feed_50

        px = 1140
        py = (HEIGHT - phone_img.height) // 2
        canvas.paste(phone_img, (px, py), phone_img)

    # SCENE 5: DAILY LIMITS & STRICT MODE (15.8s – 21.2s | Frames 474 to 635)
    elif frame_idx < 636:
        canvas.paste(studio_16x9, (0, 0))
        draw = ImageDraw.Draw(canvas)

        if frame_idx < 550:
            draw.text((140, 320), "SET YOUR DAILY LIMIT.", font=get_font('bold', 64), fill=(245, 244, 238), anchor='lm')
            draw.text((140, 390), "Know exactly when to stop.", font=get_font('medium', 32), fill=(184, 217, 78), anchor='lm')
            draw.text((140, 470), "Set custom boundaries per day. When the limit is reached,", font=get_font('regular', 22), fill=(168, 170, 169), anchor='lm')
            draw.text((140, 505), "the counter shifts to an urgent alert state.", font=get_font('regular', 22), fill=(168, 170, 169), anchor='lm')
            draw_pill_badge_16x9(draw, 140, 590, "LIMIT REACHED (50 / 50 REELS)", get_font('bold', 22), (45, 24, 20), (200, 109, 59), border_color=(200, 109, 59))
            phone_img = mockup_dash_50
        else:
            draw.text((140, 320), "STRICT MODE ANTI-BYPASS.", font=get_font('bold', 60), fill=(245, 244, 238), anchor='lm')
            draw.text((140, 390), "Zero willpower required.", font=get_font('medium', 32), fill=(200, 109, 59), anchor='lm')
            draw.text((140, 470), "Automatically closes Reels & Shorts feeds when your limit", font=get_font('regular', 22), fill=(168, 170, 169), anchor='lm')
            draw.text((140, 505), "is met. Prevents impulsive overrides with deliberate friction.", font=get_font('regular', 22), fill=(168, 170, 169), anchor='lm')
            draw_pill_badge_16x9(draw, 140, 590, "AUTOMATIC FEED LOCKOUT • NO BYPASS", get_font('bold', 20), (28, 35, 20), LIME_PRIMARY, border_color=LIME_PRIMARY)
            phone_img = mockup_shield

        px = 1140
        py = (HEIGHT - phone_img.height) // 2
        canvas.paste(phone_img, (px, py), phone_img)

    # SCENE 6: CONSISTENCY & TRANSFORMATION (21.2s – 26.8s | Frames 636 to 803)
    elif frame_idx < 804:
        canvas.paste(studio_16x9, (0, 0))
        draw = ImageDraw.Draw(canvas)

        if frame_idx < 720:
            draw.text((140, 320), "TRACK YOUR HABITS.", font=get_font('bold', 68), fill=(245, 244, 238), anchor='lm')
            draw.text((140, 390), "84-Day Goal Calendar & Heatmap.", font=get_font('medium', 32), fill=(184, 217, 78), anchor='lm')
            draw.text((140, 470), "Visualize consistency over weeks and months.", font=get_font('regular', 22), fill=(168, 170, 169), anchor='lm')
            draw.text((140, 505), "Advance across Seedling, Consistent, and Disciplined tiers.", font=get_font('regular', 22), fill=(168, 170, 169), anchor='lm')
            draw_pill_badge_16x9(draw, 140, 590, "14-DAY STREAK • CONSISTENT TIER", get_font('bold', 20), (28, 35, 20), LIME_PRIMARY, border_color=LIME_PRIMARY)
            phone_img = mockup_cal
        else:
            draw.text((140, 320), "SCROLL WITH INTENTION.", font=get_font('bold', 68), fill=(245, 244, 238), anchor='lm')
            draw.text((140, 390), "18.5 Hours Reclaimed Per Month.", font=get_font('medium', 32), fill=(184, 217, 78), anchor='lm')
            draw.text((140, 470), "Transform wasted hours into productive, fulfilling focus.", font=get_font('regular', 22), fill=(168, 170, 169), anchor='lm')
            draw.text((140, 505), "100% on-device data processing for absolute privacy.", font=get_font('regular', 22), fill=(168, 170, 169), anchor='lm')
            draw_pill_badge_16x9(draw, 140, 590, "18.5 HOURS SAVED • -58% CONSUMPTION", get_font('bold', 20), (28, 35, 20), LIME_PRIMARY, border_color=LIME_PRIMARY)
            phone_img = mockup_ana

        px = 1140
        py = (HEIGHT - phone_img.height) // 2
        canvas.paste(phone_img, (px, py), phone_img)

    # SCENE 7: BRAND FINALE (26.8s – 32.0s | Frames 804 to 959)
    else:
        canvas = Image.new('RGB', (WIDTH, HEIGHT), (252, 244, 233))
        draw = ImageDraw.Draw(canvas)

        cx = WIDTH // 2
        cy = HEIGHT // 2 - 40

        logo_w = int(logo_img.width * 1.45)
        logo_h = int(logo_img.height * 1.45)
        logo_main = logo_img.resize((logo_w, logo_h), Image.Resampling.LANCZOS)
        canvas.paste(logo_main, (cx - logo_w // 2, cy - 110), logo_main)

        draw.text((cx, cy + 50), "BE INTENTIONAL. NOT INFINITE.", font=get_font('bold', 38), fill=(48, 37, 22), anchor='mm')

        cta_w, cta_h = 680, 80
        cta_y = cy + 160
        draw.rounded_rectangle([cx - cta_w//2, cta_y - cta_h//2, cx + cta_w//2, cta_y + cta_h//2], radius=cta_h//2, fill=(21, 22, 25), outline=(184, 217, 78), width=2)
        draw.text((cx, cta_y), "TAKE CONTROL OF YOUR SCROLLING", font=get_font('bold', 24), fill=(184, 217, 78), anchor='mm')

        draw.text((cx, cta_y + 70), "Available for Android • 100% On-Device Privacy", font=get_font('semibold', 20), fill=(102, 106, 106), anchor='mm')
        draw.text((cx, HEIGHT - 70), "by FOCRAMEX", font=get_font('semibold', 22), fill=(48, 37, 22), anchor='mm')

    return canvas

def render_widescreen_commercial():
    print(f"Beginning 16:9 Widescreen Master Render: {WIDTH}x{HEIGHT} @ {FPS}fps, {TOTAL_FRAMES} frames ({DURATION}s)")
    start_time = time.time()

    audio_master = 'video_production/output/master_audio_with_vo.wav'
    out_16x9_mp4 = 'video_production/output/refocus_ad_16x9_widescreen.mp4'

    cmd = [
        'ffmpeg', '-y',
        '-f', 'rawvideo',
        '-vcodec', 'rawvideo',
        '-s', f'{WIDTH}x{HEIGHT}',
        '-pix_fmt', 'rgb24',
        '-r', str(FPS),
        '-i', '-',
        '-i', audio_master,
        '-c:v', 'libx264',
        '-preset', 'medium',
        '-crf', '17',
        '-pix_fmt', 'yuv420p',
        '-c:a', 'aac',
        '-b:a', '256k',
        '-shortest',
        out_16x9_mp4
    ]

    proc = subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    for i in range(TOTAL_FRAMES):
        frame = render_frame_16x9(i)
        proc.stdin.write(frame.tobytes())

        if i % 120 == 0 or i == TOTAL_FRAMES - 1:
            elapsed = time.time() - start_time
            fps_speed = (i + 1) / max(0.1, elapsed)
            print(f"16:9 Rendered {i+1}/{TOTAL_FRAMES} frames ({(i+1)/TOTAL_FRAMES*100:.1f}%) - Speed: {fps_speed:.1f} fps")

    proc.stdin.close()
    proc.wait()
    print(f"16:9 Widescreen Master successfully exported to: {out_16x9_mp4} (Exit code {proc.returncode})")

if __name__ == '__main__':
    render_widescreen_commercial()
