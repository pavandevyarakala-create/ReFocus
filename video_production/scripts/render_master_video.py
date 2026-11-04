"""
render_master_video.py - World-Class Re:Focus Commercial Master Renderer
Renders 32.0s (960 frames @ 30fps) at 1080x1920 (9:16 Vertical Master).
Compositing, kinetic typography, 3D perspective camera moves, and seamless match cuts.
Direct FFmpeg pipeline encoding to H.264 (CRF 17) + AAC 48kHz stereo.
"""

import os, sys, math, subprocess, time
from PIL import Image, ImageDraw, ImageFont, ImageFilter, ImageOps, ImageEnhance
import numpy as np

sys.path.append('video_production/scripts')
from ui_components import (
    get_font, LIME_PRIMARY, LIME_PRESSED, LIME_DARK, LIME_HIGHLIGHT,
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

WIDTH = 1080
HEIGHT = 1920
FPS = 30
DURATION = 32.0
TOTAL_FRAMES = int(FPS * DURATION) # 960 frames

# ---------------------------------------------------------------------------
# Easing Curves (Cubic-Bezier / Overshoot / Smoothstep)
# ---------------------------------------------------------------------------
def ease_out_cubic(x):
    return 1.0 - pow(1.0 - max(0.0, min(1.0, x)), 3.0)

def ease_in_cubic(x):
    return pow(max(0.0, min(1.0, x)), 3.0)

def ease_in_out_cubic(x):
    x = max(0.0, min(1.0, x))
    return 4.0 * x * x * x if x < 0.5 else 1.0 - pow(-2.0 * x + 2.0, 3.0) / 2.0

def ease_out_overshoot(x, s=1.25):
    x = max(0.0, min(1.0, x))
    return (x - 1.0) * (x - 1.0) * ((s + 1.0) * (x - 1.0) + s) + 1.0

# ---------------------------------------------------------------------------
# Load Brand Assets
# ---------------------------------------------------------------------------
HOOK_BG_PATH = 'video_production/assets/scrolling_hook_bg.jpg'
FIGURE_PATH = 'video_production/assets/logos/ic_refocus_figure.png'
CLOCK_PATH = 'video_production/assets/logos/ic_refocus_clock.png'
LOGO_PATH = 'video_production/assets/logos/ic_refocus_logo.png'
WORDMARK_PATH = 'video_production/assets/logos/ic_refocus_wordmark.png'

hook_bg_img = Image.open(HOOK_BG_PATH).convert('RGB')
figure_img = Image.open(FIGURE_PATH).convert('RGBA')
clock_img = Image.open(CLOCK_PATH).convert('RGBA')
logo_img = Image.open(LOGO_PATH).convert('RGBA')
wordmark_img = Image.open(WORDMARK_PATH).convert('RGBA')

# Pre-render luxury procedural studio background (1080x1920)
def generate_studio_backdrop():
    bg = Image.new('RGB', (WIDTH, HEIGHT), (9, 10, 12))
    draw = ImageDraw.Draw(bg)
    # Radial ambient spotlight from upper center
    cx, cy = WIDTH // 2, int(HEIGHT * 0.38)
    max_r = int(math.hypot(WIDTH, HEIGHT) * 0.65)
    for r in range(max_r, 0, -8):
        factor = 1.0 - (r / float(max_r))
        red = int(9 + 18 * factor * factor)
        green = int(10 + 22 * factor * factor)
        blue = int(12 + 28 * factor * factor)
        draw.ellipse([cx - r, cy - r, cx + r, cy + r], fill=(red, green, blue))
    return bg

studio_backdrop = generate_studio_backdrop()

# ---------------------------------------------------------------------------
# Pre-render App Screens & Phone Mockups
# ---------------------------------------------------------------------------
print("Pre-rendering authentic Re:Focus screens & mockups...")

# Dashboard screens
dash_screen_49 = render_phone_screen_dashboard(daily_limit=50, reels_watched=49, strict_mode_on=False, active_tab=0)
dash_screen_50 = render_phone_screen_dashboard(daily_limit=50, reels_watched=50, strict_mode_on=True, active_tab=0)

# Calendar & Analytics screens
cal_screen = render_calendar_screen(highlight_progress=0.85)
analytics_screen = render_analytics_screen()

# Strict Mode Shield Screen
shield_overlay = render_strict_mode_shield_overlay()
shield_screen = dash_screen_50.copy()
shield_screen.paste(shield_overlay, (0, 0), shield_overlay)

# Feed screens for different counts
feed_screen_47 = render_reels_feed_screen(reel_number=0, counter_visible=True, counter_val=47, counter_over_limit=False, pulse_glow=False)
feed_screen_48 = render_reels_feed_screen(reel_number=1, counter_visible=True, counter_val=48, counter_over_limit=False, pulse_glow=True)
feed_screen_49 = render_reels_feed_screen(reel_number=2, counter_visible=True, counter_val=49, counter_over_limit=False, pulse_glow=True)
feed_screen_50 = render_reels_feed_screen(reel_number=3, counter_visible=True, counter_val=50, counter_over_limit=True, pulse_glow=True)

# Phone mockups at base scale (0.86)
mockup_dash_49 = render_smartphone_mockup(dash_screen_49, scale=0.86)
mockup_dash_50 = render_smartphone_mockup(dash_screen_50, scale=0.86)
mockup_cal = render_smartphone_mockup(cal_screen, scale=0.86)
mockup_analytics = render_smartphone_mockup(analytics_screen, scale=0.86)
mockup_shield = render_smartphone_mockup(shield_screen, scale=0.86)

mockup_feed_47 = render_smartphone_mockup(feed_screen_47, scale=0.86)
mockup_feed_48 = render_smartphone_mockup(feed_screen_48, scale=0.86)
mockup_feed_49 = render_smartphone_mockup(feed_screen_49, scale=0.86)
mockup_feed_50 = render_smartphone_mockup(feed_screen_50, scale=0.86)

print("Pre-rendering complete. Initializing frame synthesis pipeline...")

# ---------------------------------------------------------------------------
# Kinetic Typography & UI Helper Functions
# ---------------------------------------------------------------------------
def draw_pill_badge(draw, x, y, text, font, bg_color, text_color, border_color=None):
    w = int(draw.textlength(text, font=font) + 44)
    h = 48
    box = [x - w // 2, y - h // 2, x + w // 2, y + h // 2]
    draw.rounded_rectangle(box, radius=h//2, fill=bg_color, outline=border_color, width=1)
    draw.text((x, y), text, font=font, fill=text_color, anchor='mm')

def draw_masked_text(canvas, text, font, color, cx, cy, reveal_progress=1.0, tracking=0):
    """
    Simulates After Effects track-matte / clipping mask reveal:
    Text slides up from cy + 40 through an invisible horizontal line as it fades in.
    """
    draw = ImageDraw.Draw(canvas)
    p = ease_out_cubic(reveal_progress)
    alpha = int(255 * p)
    offset_y = int(35 * (1.0 - p))

    # Apply tracking if specified
    if tracking == 0:
        draw.text((cx, cy + offset_y), text, font=font, fill=(color[0], color[1], color[2], alpha), anchor='mm')
    else:
        # manual letter spacing
        chars = list(text)
        widths = [draw.textlength(c, font=font) for c in chars]
        total_w = sum(widths) + tracking * (len(chars) - 1)
        cur_x = cx - total_w // 2
        for c, w in zip(chars, widths):
            draw.text((cur_x + w // 2, cy + offset_y), c, font=font, fill=(color[0], color[1], color[2], alpha), anchor='mm')
            cur_x += w + tracking

# ---------------------------------------------------------------------------
# Master Frame Renderer (0 to 959)
# ---------------------------------------------------------------------------
def render_frame(frame_idx):
    canvas = Image.new('RGB', (WIDTH, HEIGHT), (9, 10, 12))
    draw = ImageDraw.Draw(canvas)
    t = frame_idx / float(FPS)

    # =========================================================================
    # SCENE 1: THE HOOK (0.0s – 3.2s | Frames 0 to 95)
    # =========================================================================
    if frame_idx < 96:
        # Camera zoom push-in on person scrolling
        scale = 1.0 + (frame_idx / 96.0) * 0.16
        crop_w = int(WIDTH / scale)
        crop_h = int(HEIGHT / scale)
        cx, cy = hook_bg_img.width // 2, hook_bg_img.height // 2
        crop_box = (cx - crop_w // 2, cy - crop_h // 2, cx + crop_w // 2, cy + crop_h // 2)
        frame_bg = hook_bg_img.crop(crop_box).resize((WIDTH, HEIGHT), Image.Resampling.BILINEAR)
        canvas.paste(frame_bg, (0, 0))

        # Atmospheric dark cinematic vignette
        grad_overlay = Image.new('RGBA', (WIDTH, HEIGHT), (0, 0, 0, 0))
        gdraw = ImageDraw.Draw(grad_overlay)
        for y in range(480):
            a = int(230 * (1.0 - y / 480.0))
            gdraw.line([(0, y), (WIDTH, y)], fill=(0, 0, 0, a))
        for y in range(HEIGHT - 600, HEIGHT):
            a = int(240 * ((y - (HEIGHT - 600)) / 600.0))
            gdraw.line([(0, y), (WIDTH, y)], fill=(0, 0, 0, a))
        # Center subtle shadow
        for y in range(700, 1300):
            dist = abs(y - 1000) / 300.0
            a = int(120 * (1.0 - dist))
            gdraw.line([(0, y), (WIDTH, y)], fill=(0, 0, 0, a))
        canvas.paste(grad_overlay, (0, 0), grad_overlay)

        draw = ImageDraw.Draw(canvas)
        font_hook = get_font('bold', 76)
        font_sub = get_font('semibold', 28)

        if frame_idx < 32: # 0.0s - 1.07s: "ONE MORE REEL?"
            p = frame_idx / 32.0
            draw_masked_text(canvas, "ONE MORE REEL?", font_hook, (245, 244, 238), WIDTH // 2, 980, reveal_progress=min(1.0, p * 1.8), tracking=2)
            draw_masked_text(canvas, "Just 5 minutes, right?", font_sub, (184, 217, 78), WIDTH // 2, 1070, reveal_progress=min(1.0, p * 1.5))

        elif frame_idx < 64: # 1.07s - 2.13s: "THEN ANOTHER."
            p = (frame_idx - 32) / 32.0
            draw_masked_text(canvas, "THEN ANOTHER.", font_hook, (222, 139, 111), WIDTH // 2, 980, reveal_progress=min(1.0, p * 1.8), tracking=2)
            draw_masked_text(canvas, "Swipe after swipe...", font_sub, (245, 244, 238), WIDTH // 2, 1070, reveal_progress=min(1.0, p * 1.5))
            # Kinetic upward motion streak
            streak_y = int(HEIGHT * (1.0 - p))
            draw.line([(WIDTH//2 - 240, streak_y), (WIDTH//2 + 240, streak_y)], fill=(255, 255, 255, 120), width=3)

        else: # 2.13s - 3.2s: "AND ANOTHER."
            p = (frame_idx - 64) / 32.0
            draw_masked_text(canvas, "AND ANOTHER.", font_hook, (200, 109, 59), WIDTH // 2, 980, reveal_progress=min(1.0, p * 1.8), tracking=2)
            draw_masked_text(canvas, "The infinite scroll won't stop.", font_sub, (245, 244, 238), WIDTH // 2, 1070, reveal_progress=min(1.0, p * 1.5))

    # =========================================================================
    # SCENE 2: THE PROBLEM (3.2s – 7.5s | Frames 96 to 224)
    # =========================================================================
    elif frame_idx < 225:
        canvas.paste(studio_backdrop, (0, 0))
        draw = ImageDraw.Draw(canvas)

        if frame_idx < 170: # 3.2s - 5.67s: Rapid Time Passing & Ghost Counters
            p = (frame_idx - 96) / 74.0

            times = ["11:45 PM", "12:30 AM", "1:15 AM", "2:05 AM", "2:50 AM", "3:30 AM"]
            curr_time = times[min(len(times)-1, int(p * len(times)))]

            # Elevated Time Pill HUD at top
            draw.rounded_rectangle([WIDTH//2 - 170, 480, WIDTH//2 + 170, 560], radius=40, fill=(24, 27, 34), outline=(58, 63, 76), width=2)
            draw.text((WIDTH // 2, 520), f"TIME  {curr_time}", font=get_font('bold', 28), fill=(245, 244, 238), anchor='mm')

            draw_masked_text(canvas, "YOU LOSE TRACK.", get_font('bold', 78), (245, 244, 238), WIDTH // 2, 880, reveal_progress=min(1.0, p * 2.0), tracking=1)
            draw_masked_text(canvas, "How many have you watched tonight?", get_font('medium', 32), (168, 170, 169), WIDTH // 2, 980, reveal_progress=min(1.0, p * 1.5))

            # Floating dopamine ghost counters drifting up
            for offset_i in range(8):
                drift_y = int((HEIGHT - 320) - ((frame_idx * 22 + offset_i * 170) % 940))
                drift_x = 200 + (offset_i * 105) % 680
                alpha = int(45 * (1.0 - abs(drift_y - 1000) / 600.0))
                draw.text((drift_x, drift_y), f"+{offset_i * 14 + 1}", font=get_font('bold', 44), fill=(255, 255, 255, alpha))

        else: # 5.67s - 7.5s: "AND THE ALGORITHM WON."
            p = (frame_idx - 170) / 55.0
            draw_masked_text(canvas, "AND THE ALGORITHM WON.", get_font('bold', 68), (200, 109, 59), WIDTH // 2, 860, reveal_progress=min(1.0, p * 2.0))
            
            # Big HUD Box with ticking count up to 147
            reels_count = min(147, int(42 + p * 105))
            box_w, box_h = 560, 160
            cx, cy = WIDTH // 2, 1040
            draw.rounded_rectangle([cx - box_w//2, cy - box_h//2, cx + box_w//2, cy + box_h//2], radius=24, fill=(28, 18, 16), outline=(200, 109, 59), width=2)
            draw.text((cx, cy - 30), "REELS CONSUMED", font=get_font('bold', 18), fill=(200, 109, 59), anchor='mm')
            draw.text((cx, cy + 24), f"{reels_count}", font=get_font('bold', 56), fill=(245, 244, 238), anchor='mm')

            draw.text((WIDTH // 2, 1280), "Unconscious endless scrolling stealing hours of your life.", font=get_font('medium', 24), fill=(168, 170, 169), anchor='mm')

    # =========================================================================
    # SCENE 3: THE INTERVENTION (7.5s – 9.8s | Frames 225 to 293)
    # =========================================================================
    elif frame_idx < 294:
        p = (frame_idx - 225) / 69.0

        if p < 0.12:
            # Sudden high-impact white flash / iris cut
            flash_a = int(255 * (1.0 - p / 0.12))
            canvas = Image.new('RGB', (WIDTH, HEIGHT), (flash_a, flash_a, flash_a))
        else:
            canvas.paste(studio_backdrop, (0, 0))
            draw = ImageDraw.Draw(canvas)

            # Luminous signature lime accent line expanding horizontally
            sub_p = (p - 0.12) / 0.88
            line_w = int(WIDTH * ease_out_cubic(min(1.0, sub_p * 2.5)))
            draw.line([(WIDTH//2 - line_w//2, 640), (WIDTH//2 + line_w//2, 640)], fill=LIME_PRIMARY, width=2)

            # Floating Counter Pill blooms in center
            pill_scale = ease_out_overshoot(min(1.0, sub_p * 1.6), s=1.3)
            if pill_scale > 0.05:
                pill = render_floating_counter(count=1, is_over_limit=False, scale=pill_scale * 1.35, pulse_glow=True)
                canvas.paste(pill, (WIDTH // 2 - pill.width // 2, 700 - pill.height // 2), pill)

            # Kinetic Headline: "TAKE BACK YOUR ATTENTION."
            draw_masked_text(canvas, "TAKE BACK YOUR ATTENTION.", get_font('bold', 62), (245, 244, 238), WIDTH // 2, 940, reveal_progress=min(1.0, sub_p * 1.8), tracking=1)
            draw_masked_text(canvas, "Real-time on-device awareness for Android.", get_font('medium', 28), (184, 217, 78), WIDTH // 2, 1020, reveal_progress=min(1.0, sub_p * 1.5))

            # Privacy Badge
            draw_pill_badge(draw, WIDTH // 2, 1140, "ZERO CLOUD TRACKING • 100% PRIVATE • LOCAL ONLY", get_font('bold', 20), (28, 35, 20), LIME_PRIMARY, border_color=LIME_PRIMARY)

    # =========================================================================
    # SCENE 4: PRODUCT REVEAL & LIVE COUNTER (9.8s – 15.8s | Frames 294 to 473)
    # =========================================================================
    elif frame_idx < 474:
        canvas.paste(studio_backdrop, (0, 0))
        draw = ImageDraw.Draw(canvas)

        p = (frame_idx - 294) / 180.0

        # Title & Subtitle at top
        draw_masked_text(canvas, "SEE EVERY REEL IN REAL TIME.", get_font('bold', 64), (245, 244, 238), WIDTH // 2, 180, reveal_progress=min(1.0, p * 4.0))
        draw.text((WIDTH // 2, 250), "Stay aware as you browse.", font=get_font('medium', 28), fill=(184, 217, 78), anchor='mm')

        # Phone entry with gentle 3D tilt & hover
        tilt = 4.0 * (1.0 - ease_out_cubic(min(1.0, p * 2.0)))
        hover_y = int(math.sin(p * math.pi * 4) * 10)
        phone_y = 350 + hover_y

        # Select feed mockup based on swiping choreography
        if frame_idx < 342: # 9.8s - 11.4s
            phone_img = mockup_feed_47
        elif frame_idx < 393: # 11.4s - 13.1s
            phone_img = mockup_feed_48
        elif frame_idx < 444: # 13.1s - 14.8s
            phone_img = mockup_feed_49
        else: # 14.8s - 15.8s
            phone_img = mockup_feed_50

        phone_x = WIDTH // 2 - phone_img.width // 2
        canvas.paste(phone_img, (phone_x, phone_y), phone_img)

        # Bottom Pill Badge
        draw_pill_badge(draw, WIDTH // 2, 1720, "LIVE FLOATING COUNTER OVERLAY", get_font('bold', 22), (24, 27, 31), (245, 244, 238), border_color=(58, 63, 76))
        draw.text((WIDTH // 2, 1780), "Tracks Instagram Reels & YouTube Shorts automatically", font=get_font('medium', 22), fill=(146, 149, 144), anchor='mm')

    # =========================================================================
    # SCENE 5: DAILY LIMITS & STRICT MODE (15.8s – 21.2s | Frames 474 to 635)
    # =========================================================================
    elif frame_idx < 636:
        canvas.paste(studio_backdrop, (0, 0))
        draw = ImageDraw.Draw(canvas)

        p = (frame_idx - 474) / 162.0

        # Title & Subtitle
        if frame_idx < 550:
            draw_masked_text(canvas, "SET YOUR DAILY LIMIT.", get_font('bold', 64), (245, 244, 238), WIDTH // 2, 180, reveal_progress=min(1.0, p * 4.0))
            draw.text((WIDTH // 2, 250), "Know exactly when to stop.", font=get_font('medium', 28), fill=(184, 217, 78), anchor='mm')
        else:
            draw_masked_text(canvas, "STRICT MODE ANTI-BYPASS.", get_font('bold', 60), (245, 244, 238), WIDTH // 2, 180, reveal_progress=1.0)
            draw.text((WIDTH // 2, 250), "Zero willpower required. Feed locks automatically.", font=get_font('medium', 28), fill=(200, 109, 59), anchor='mm')

        # Phone mockup transition:
        # 474 - 540: Dashboard 50/50 Limit Reached
        # 541 - 580: Strict Mode Card highlighted
        # 581 - 635: Shield Overlay engages!
        if frame_idx < 580:
            phone_img = mockup_dash_50
        else:
            phone_img = mockup_shield

        phone_x = WIDTH // 2 - phone_img.width // 2
        phone_y = 350
        canvas.paste(phone_img, (phone_x, phone_y), phone_img)

        # Bottom Feature Callout
        if frame_idx < 550:
            draw_pill_badge(draw, WIDTH // 2, 1720, "LIMIT REACHED (50 / 50 REELS)", get_font('bold', 24), (45, 24, 20), (200, 109, 59), border_color=(200, 109, 59))
            draw.text((WIDTH // 2, 1780), "Floating counter shifts to high-visibility warning state", font=get_font('medium', 22), fill=(244, 214, 195), anchor='mm')
        else:
            draw_pill_badge(draw, WIDTH // 2, 1720, "AUTOMATIC FEED EXIT • NO BYPASS", get_font('bold', 22), (28, 35, 20), LIME_PRIMARY, border_color=LIME_PRIMARY)
            draw.text((WIDTH // 2, 1780), "Auto-closes Reels & Shorts when limit is reached", font=get_font('medium', 22), fill=(146, 149, 144), anchor='mm')

    # =========================================================================
    # SCENE 6: CONSISTENCY & TRANSFORMATION (21.2s – 26.8s | Frames 636 to 803)
    # =========================================================================
    elif frame_idx < 804:
        canvas.paste(studio_backdrop, (0, 0))
        draw = ImageDraw.Draw(canvas)

        p = (frame_idx - 636) / 168.0

        if frame_idx < 720: # 21.2s - 24.0s: Calendar 84-Day Consistency Grid
            draw_masked_text(canvas, "TRACK YOUR HABITS.", get_font('bold', 64), (245, 244, 238), WIDTH // 2, 180, reveal_progress=min(1.0, p * 3.5))
            draw.text((WIDTH // 2, 250), "84-Day Goal Calendar & Consistency Heatmap.", font=get_font('medium', 28), fill=(184, 217, 78), anchor='mm')
            phone_img = mockup_cal
            draw_pill_badge(draw, WIDTH // 2, 1720, "14-DAY STREAK • CONSISTENT TIER", get_font('bold', 22), (28, 35, 20), LIME_PRIMARY, border_color=LIME_PRIMARY)
            draw.text((WIDTH // 2, 1780), "Skull Days reset streaks, but Discipline Level represents best tier", font=get_font('medium', 22), fill=(146, 149, 144), anchor='mm')

        else: # 24.0s - 26.8s: Usage Analytics
            draw_masked_text(canvas, "SCROLL WITH INTENTION.", get_font('bold', 64), (245, 244, 238), WIDTH // 2, 180, reveal_progress=1.0)
            draw.text((WIDTH // 2, 250), "Weekly trends, daily averages & hours saved.", font=get_font('medium', 28), fill=(184, 217, 78), anchor='mm')
            phone_img = mockup_analytics
            draw_pill_badge(draw, WIDTH // 2, 1720, "18.5 HOURS RECLAIMED PER MONTH", get_font('bold', 22), (28, 35, 20), LIME_PRIMARY, border_color=LIME_PRIMARY)
            draw.text((WIDTH // 2, 1780), "Detailed analytics to understand and master your screen time", font=get_font('medium', 22), fill=(146, 149, 144), anchor='mm')

        phone_x = WIDTH // 2 - phone_img.width // 2
        phone_y = 350
        canvas.paste(phone_img, (phone_x, phone_y), phone_img)

    # =========================================================================
    # SCENE 7: BRAND REVEAL & CTA (26.8s – 32.0s | Frames 804 to 959)
    # =========================================================================
    else:
        # Authentic Re:Focus Startup Splash Canvas (#FCF4E9)
        canvas = Image.new('RGB', (WIDTH, HEIGHT), (252, 244, 233))
        draw = ImageDraw.Draw(canvas)

        p = (frame_idx - 804) / 156.0 # 0.0 to 1.0 (over 5.2s)
        cx = WIDTH // 2
        cy = HEIGHT // 2 - 160

        if p < 0.32:
            # Phase 1: Leaping figure sweeps toward the clock with overshoot inertia
            sub_p = p / 0.32
            ease = ease_out_cubic(sub_p)

            # Clock at top right of center
            c_scale = 0.8 + 0.2 * ease
            cw, ch = int(120 * c_scale), int(120 * c_scale)
            c_res = clock_img.resize((cw, ch), Image.Resampling.LANCZOS)
            canvas.paste(c_res, (cx + 60, cy - 140), c_res)

            # Figure leaping up from bottom-left
            fw, fh = int(180 * c_scale), int(180 * c_scale)
            f_res = figure_img.resize((fw, fh), Image.Resampling.LANCZOS)
            fx = int(cx - 160 + 60 * ease)
            fy = int(cy + 40 - 120 * ease)
            canvas.paste(f_res, (fx, fy), f_res)

        else:
            # Phase 2: Resolves into official Re:Focus brand logo lockup
            logo_w = int(logo_img.width * 1.30)
            logo_h = int(logo_img.height * 1.30)
            logo_main = logo_img.resize((logo_w, logo_h), Image.Resampling.LANCZOS)
            canvas.paste(logo_main, (cx - logo_w // 2, cy - 40), logo_main)

            # Tagline: "BE INTENTIONAL. NOT INFINITE."
            draw.text((cx, cy + 140), "BE INTENTIONAL. NOT INFINITE.", font=get_font('bold', 34), fill=(48, 37, 22), anchor='mm')

            # Primary Call-To-Action Pill: "TAKE CONTROL OF YOUR SCROLLING"
            cta_w, cta_h = 640, 84
            cta_y = cy + 260
            draw.rounded_rectangle([cx - cta_w//2, cta_y - cta_h//2, cx + cta_w//2, cta_y + cta_h//2], radius=cta_h//2, fill=(21, 22, 25), outline=(184, 217, 78), width=2)
            draw.text((cx, cta_y), "TAKE CONTROL OF YOUR SCROLLING", font=get_font('bold', 24), fill=(184, 217, 78), anchor='mm')

            # Release status badge
            draw.text((cx, cta_y + 80), "Available for Android • 100% On-Device Privacy", font=get_font('semibold', 20), fill=(102, 106, 106), anchor='mm')

        # Brand Footer: "by FOCRAMEX" (Stationary from first frame, tracking 3px)
        draw_masked_text(canvas, "by FOCRAMEX", get_font('semibold', 24), (48, 37, 22), cx, HEIGHT - 130, reveal_progress=1.0, tracking=3)

    return canvas

def render_master_commercial():
    print(f"Beginning Master Video Render: {WIDTH}x{HEIGHT} @ {FPS}fps, {TOTAL_FRAMES} frames ({DURATION}s)")
    start_time = time.time()

    audio_master = 'video_production/output/master_audio_with_vo.wav'
    audio_clean = 'video_production/output/clean_audio_instrumental.wav'
    out_master_mp4 = 'video_production/output/refocus_ad_master_with_audio.mp4'
    out_clean_mp4 = 'video_production/output/refocus_ad_master_clean.mp4'

    cmd_master = [
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
        out_master_mp4
    ]

    proc = subprocess.Popen(cmd_master, stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    for i in range(TOTAL_FRAMES):
        frame = render_frame(i)
        raw_bytes = frame.tobytes()
        proc.stdin.write(raw_bytes)

        if i % 60 == 0 or i == TOTAL_FRAMES - 1:
            elapsed = time.time() - start_time
            fps_speed = (i + 1) / max(0.1, elapsed)
            print(f"Rendered {i+1}/{TOTAL_FRAMES} frames ({(i+1)/TOTAL_FRAMES*100:.1f}%) - Speed: {fps_speed:.1f} fps")

    proc.stdin.close()
    proc.wait()
    print(f"Master Video successfully exported to: {out_master_mp4} (Exit code {proc.returncode})")

    # Clean Instrumental Cut (Music + SFX only, no Voice-Over)
    print("Exporting Clean Version (Music + SFX only, no Voice-Over)...")
    cmd_clean = [
        'ffmpeg', '-y',
        '-i', out_master_mp4,
        '-i', audio_clean,
        '-c:v', 'copy',
        '-map', '0:v:0',
        '-map', '1:a:0',
        '-c:a', 'aac',
        '-b:a', '256k',
        '-shortest',
        out_clean_mp4
    ]
    subprocess.run(cmd_clean, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    print(f"Clean Video successfully exported to: {out_clean_mp4}")

if __name__ == '__main__':
    render_master_commercial()
