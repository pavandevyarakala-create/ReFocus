"""
render_cutdown_15s.py - High-Performance 15-Second Cutdown Renderer
Optimized for rapid acquisition on TikTok, Instagram Reels, and YouTube Shorts.
450 frames @ 30fps = 15.0s at 1080x1920.
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
    render_reels_feed_screen, render_strict_mode_shield_overlay,
    render_smartphone_mockup
)

WIDTH = 1080
HEIGHT = 1920
FPS = 30
DURATION = 15.0
TOTAL_FRAMES = int(FPS * DURATION) # 450 frames

HOOK_BG_PATH = 'video_production/assets/scrolling_hook_bg.jpg'
FIGURE_PATH = 'video_production/assets/logos/ic_refocus_figure.png'
CLOCK_PATH = 'video_production/assets/logos/ic_refocus_clock.png'
LOGO_PATH = 'video_production/assets/logos/ic_refocus_logo.png'

hook_bg_img = Image.open(HOOK_BG_PATH).convert('RGB')
figure_img = Image.open(FIGURE_PATH).convert('RGBA')
clock_img = Image.open(CLOCK_PATH).convert('RGBA')
logo_img = Image.open(LOGO_PATH).convert('RGBA')

# Pre-render studio backdrop
studio_backdrop = Image.new('RGB', (WIDTH, HEIGHT), (9, 10, 12))
sdraw = ImageDraw.Draw(studio_backdrop)
cx, cy = WIDTH // 2, int(HEIGHT * 0.38)
for r in range(int(math.hypot(WIDTH, HEIGHT) * 0.65), 0, -8):
    factor = 1.0 - (r / float(math.hypot(WIDTH, HEIGHT) * 0.65))
    sdraw.ellipse([cx - r, cy - r, cx + r, cy + r], fill=(int(9 + 18 * factor**2), int(10 + 22 * factor**2), int(12 + 28 * factor**2)))

# Pre-render phone mockups
feed_48 = render_reels_feed_screen(reel_number=1, counter_visible=True, counter_val=48, counter_over_limit=False, pulse_glow=False)
feed_49 = render_reels_feed_screen(reel_number=2, counter_visible=True, counter_val=49, counter_over_limit=False, pulse_glow=True)
dash_50 = render_phone_screen_dashboard(daily_limit=50, reels_watched=50, strict_mode_on=True)
shield_screen = dash_50.copy()
shield_overlay = render_strict_mode_shield_overlay()
shield_screen.paste(shield_overlay, (0, 0), shield_overlay)

mockup_feed_48 = render_smartphone_mockup(feed_48, scale=0.86)
mockup_feed_49 = render_smartphone_mockup(feed_49, scale=0.86)
mockup_dash_50 = render_smartphone_mockup(dash_50, scale=0.86)
mockup_shield = render_smartphone_mockup(shield_screen, scale=0.86)

def ease_out_cubic(x):
    return 1.0 - pow(1.0 - max(0.0, min(1.0, x)), 3.0)

def draw_pill_badge(draw, x, y, text, font, bg_color, text_color, border_color=None):
    w = int(draw.textlength(text, font=font) + 40)
    h = 46
    box = [x - w // 2, y - h // 2, x + w // 2, y + h // 2]
    draw.rounded_rectangle(box, radius=h//2, fill=bg_color, outline=border_color, width=1)
    draw.text((x, y), text, font=font, fill=text_color, anchor='mm')

def render_cutdown_frame(frame_idx):
    canvas = Image.new('RGB', (WIDTH, HEIGHT), (9, 10, 12))
    draw = ImageDraw.Draw(canvas)

    # 1. Hook (0.0s - 2.0s | Frames 0 to 59)
    if frame_idx < 60:
        scale = 1.0 + (frame_idx / 60.0) * 0.15
        crop_w = int(WIDTH / scale)
        crop_h = int(HEIGHT / scale)
        cx, cy = hook_bg_img.width // 2, hook_bg_img.height // 2
        frame_bg = hook_bg_img.crop((cx - crop_w // 2, cy - crop_h // 2, cx + crop_w // 2, cy + crop_h // 2)).resize((WIDTH, HEIGHT), Image.Resampling.BILINEAR)
        canvas.paste(frame_bg, (0, 0))

        # Vignette
        vign = Image.new('RGBA', (WIDTH, HEIGHT), (0, 0, 0, 0))
        vdraw = ImageDraw.Draw(vign)
        for y in range(400):
            vdraw.line([(0, y), (WIDTH, y)], fill=(0, 0, 0, int(220 * (1 - y/400.0))))
        for y in range(HEIGHT - 500, HEIGHT):
            vdraw.line([(0, y), (WIDTH, y)], fill=(0, 0, 0, int(230 * ((y - (HEIGHT - 500))/500.0))))
        canvas.paste(vign, (0, 0), vign)

        draw = ImageDraw.Draw(canvas)
        draw.text((WIDTH // 2, 960), "ONE MORE REEL?", font=get_font('bold', 76), fill=(245, 244, 238), anchor='mm')
        draw.text((WIDTH // 2, 1050), "Then another? The infinite scroll won't stop.", font=get_font('semibold', 26), fill=(184, 217, 78), anchor='mm')

    # 2. Problem (2.0s - 4.5s | Frames 60 to 134)
    elif frame_idx < 135:
        canvas.paste(studio_backdrop, (0, 0))
        draw = ImageDraw.Draw(canvas)
        p = (frame_idx - 60) / 75.0

        times = ["11:45 PM", "1:15 AM", "2:50 AM"]
        curr_time = times[min(len(times)-1, int(p * len(times)))]
        draw.rounded_rectangle([WIDTH//2 - 160, 480, WIDTH//2 + 160, 560], radius=40, fill=(24, 27, 34), outline=(58, 63, 76), width=2)
        draw.text((WIDTH // 2, 520), f"TIME  {curr_time}", font=get_font('bold', 28), fill=(245, 244, 238), anchor='mm')

        draw.text((WIDTH // 2, 880), "YOU LOSE TRACK.", font=get_font('bold', 76), fill=(245, 244, 238), anchor='mm')
        draw.text((WIDTH // 2, 970), "Until the algorithm consumes hours.", font=get_font('medium', 30), fill=(200, 109, 59), anchor='mm')

        # Ghost counters
        for offset_i in range(6):
            drift_y = int((HEIGHT - 320) - ((frame_idx * 24 + offset_i * 180) % 900))
            drift_x = 220 + (offset_i * 110) % 640
            draw.text((drift_x, drift_y), f"+{offset_i * 22 + 1}", font=get_font('bold', 42), fill=(255, 255, 255, 45))

    # 3. Live Floating Counter (4.5s - 8.5s | Frames 135 to 254)
    elif frame_idx < 255:
        canvas.paste(studio_backdrop, (0, 0))
        draw = ImageDraw.Draw(canvas)

        draw.text((WIDTH // 2, 180), "SEE EVERY REEL.", font=get_font('bold', 64), fill=(245, 244, 238), anchor='mm')
        draw.text((WIDTH // 2, 250), "Live on-device floating counter for Android.", font=get_font('medium', 28), fill=(184, 217, 78), anchor='mm')

        phone_img = mockup_feed_49 if frame_idx >= 195 else mockup_feed_48
        phone_x = WIDTH // 2 - phone_img.width // 2
        canvas.paste(phone_img, (phone_x, 350), phone_img)

        draw_pill_badge(draw, WIDTH // 2, 1720, "LIVE FLOATING COUNTER OVERLAY", get_font('bold', 22), (24, 27, 31), (245, 244, 238), border_color=(58, 63, 76))
        draw.text((WIDTH // 2, 1780), "Tracks Instagram Reels & YouTube Shorts in real time", font=get_font('medium', 22), fill=(146, 149, 144), anchor='mm')

    # 4. Strict Mode Anti-Bypass (8.5s - 11.5s | Frames 255 to 344)
    elif frame_idx < 345:
        canvas.paste(studio_backdrop, (0, 0))
        draw = ImageDraw.Draw(canvas)

        draw.text((WIDTH // 2, 180), "STRICT MODE ANTI-BYPASS.", font=get_font('bold', 60), fill=(245, 244, 238), anchor='mm')
        draw.text((WIDTH // 2, 250), "Auto-closes feeds at limit. Zero willpower required.", font=get_font('medium', 26), fill=(200, 109, 59), anchor='mm')

        phone_img = mockup_shield if frame_idx >= 300 else mockup_dash_50
        phone_x = WIDTH // 2 - phone_img.width // 2
        canvas.paste(phone_img, (phone_x, 350), phone_img)

        draw_pill_badge(draw, WIDTH // 2, 1720, "LIMIT REACHED (50 / 50 REELS)", get_font('bold', 24), (45, 24, 20), (200, 109, 59), border_color=(200, 109, 59))
        draw.text((WIDTH // 2, 1780), "Feed is safely locked until tomorrow", font=get_font('medium', 22), fill=(244, 214, 195), anchor='mm')

    # 5. Brand Finale (11.5s - 15.0s | Frames 345 to 449)
    else:
        canvas = Image.new('RGB', (WIDTH, HEIGHT), (252, 244, 233))
        draw = ImageDraw.Draw(canvas)

        cx = WIDTH // 2
        cy = HEIGHT // 2 - 160

        logo_w = int(logo_img.width * 1.30)
        logo_h = int(logo_img.height * 1.30)
        logo_main = logo_img.resize((logo_w, logo_h), Image.Resampling.LANCZOS)
        canvas.paste(logo_main, (cx - logo_w // 2, cy - 40), logo_main)

        draw.text((cx, cy + 140), "BE INTENTIONAL. NOT INFINITE.", font=get_font('bold', 34), fill=(48, 37, 22), anchor='mm')

        cta_w, cta_h = 640, 84
        cta_y = cy + 260
        draw.rounded_rectangle([cx - cta_w//2, cta_y - cta_h//2, cx + cta_w//2, cta_y + cta_h//2], radius=cta_h//2, fill=(21, 22, 25), outline=(184, 217, 78), width=2)
        draw.text((cx, cta_y), "TAKE CONTROL OF YOUR SCROLLING", font=get_font('bold', 24), fill=(184, 217, 78), anchor='mm')

        draw.text((cx, cta_y + 80), "Available for Android • 100% On-Device Privacy", font=get_font('semibold', 20), fill=(102, 106, 106), anchor='mm')
        draw.text((cx, HEIGHT - 130), "by FOCRAMEX", font=get_font('semibold', 24), fill=(48, 37, 22), anchor='mm')

    return canvas

def render_cutdown():
    print("Synthesizing 15s Cutdown Audio...")
    # Extract first 15s of master audio
    audio_master_32s = 'video_production/output/master_audio_with_vo.wav'
    cutdown_audio = 'video_production/output/cutdown_15s_audio.wav'
    subprocess.run([
        'ffmpeg', '-y',
        '-i', audio_master_32s,
        '-t', '15.0',
        '-af', 'afade=t=out:st=14.5:d=0.5',
        cutdown_audio
    ], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    out_mp4 = 'video_production/output/refocus_ad_15s_cutdown.mp4'
    print(f"Beginning 15s Cutdown Render: {TOTAL_FRAMES} frames...")
    start_time = time.time()

    cmd = [
        'ffmpeg', '-y',
        '-f', 'rawvideo',
        '-vcodec', 'rawvideo',
        '-s', f'{WIDTH}x{HEIGHT}',
        '-pix_fmt', 'rgb24',
        '-r', str(FPS),
        '-i', '-',
        '-i', cutdown_audio,
        '-c:v', 'libx264',
        '-preset', 'medium',
        '-crf', '17',
        '-pix_fmt', 'yuv420p',
        '-c:a', 'aac',
        '-b:a', '256k',
        '-shortest',
        out_mp4
    ]

    proc = subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    for i in range(TOTAL_FRAMES):
        frame = render_cutdown_frame(i)
        proc.stdin.write(frame.tobytes())

    proc.stdin.close()
    proc.wait()
    print(f"15s Cutdown successfully exported to: {out_mp4} (Exit code {proc.returncode})")

if __name__ == '__main__':
    render_cutdown()
