"""
ui_components.py - Authentic Re:Focus Motion Design UI Component Suite
Faithfully reproduces Re:Focus design tokens, Sora typography, and authentic Android UI.
Features:
- Flagship Titanium Smartphone Frame with multi-stage ambient shadows and specular glass sheen
- Authentic Instagram / YouTube Reels Feed with live Re:Focus Floating Counter
- Pixel-perfect Re:Focus Dashboard (HeroProgressCard, StrictModeCard, Weekly Activity)
- 84-Day Goal Calendar & Consistency Heatmap (Discipline Levels)
- Deep Usage Analytics Screen (Time Reclaimed, Downward Consumption Curve)
- Strict Mode Auto-Close Enforcement Shield
"""

import os, math
from PIL import Image, ImageDraw, ImageFont, ImageFilter, ImageOps
import numpy as np

# ---------------------------------------------------------------------------
# Brand Typography & Fonts
# ---------------------------------------------------------------------------
FONT_BOLD = 'video_production/assets/fonts/sora_bold.ttf'
FONT_SEMIBOLD = 'video_production/assets/fonts/sora_semibold.ttf'
FONT_MEDIUM = 'video_production/assets/fonts/sora_medium.ttf'
FONT_REGULAR = 'video_production/assets/fonts/sora_regular.ttf'

def get_font(weight='bold', size=24):
    path_map = {
        'bold': FONT_BOLD,
        'semibold': FONT_SEMIBOLD,
        'medium': FONT_MEDIUM,
        'regular': FONT_REGULAR
    }
    return ImageFont.truetype(path_map.get(weight, FONT_BOLD), int(size))

# ---------------------------------------------------------------------------
# Brand Color Tokens (Direct from Color.kt & ReFocusDesignTokens.kt)
# ---------------------------------------------------------------------------
LIME_PRIMARY = (184, 217, 78, 255)         # #B8D94E (Signature Muted Lime)
LIME_PRESSED = (162, 194, 59, 255)         # #A2C23B
LIME_SOFT_LIGHT = (238, 243, 216, 255)     # #EEF3D8
LIME_DARK = (96, 118, 21, 255)             # #607615
LIME_HIGHLIGHT = (194, 224, 87, 255)       # #C2E057
LIME_GLOW = (184, 217, 78, 60)

LIGHT_BG = (247, 246, 240, 255)            # #F7F6F0 (Warm Ivory Canvas)
LIGHT_SURFACE = (255, 255, 255, 255)       # #FFFFFF
LIGHT_SURFACE_SOFT = (239, 238, 231, 255)  # #EFEEE7
LIGHT_SURFACE_STRONG = (230, 228, 218, 255)# #E6E4DA
LIGHT_BORDER = (225, 223, 214, 255)        # #E1DFD6
TEXT_PRIMARY = (21, 22, 25, 255)           # #151619 (Deep Charcoal)
TEXT_SECONDARY = (102, 106, 106, 255)      # #666A6A
TEXT_MUTED = (146, 149, 144, 255)          # #929590

DARK_BG = (9, 10, 12, 255)                 # #090A0C
DARK_SURFACE = (17, 19, 22, 255)           # #111316
DARK_SURFACE_ELEVATED = (24, 27, 31, 255)  # #181B1F
DARK_SURFACE_FLOATING = (31, 35, 40, 255)  # #1F2328
DARK_BORDER = (43, 48, 55, 255)            # #2B3037

WARNING_CORAL = (200, 109, 59, 255)        # #C86D3B (Semantic Terracotta Warning)
WARNING_SOFT = (253, 243, 236, 255)        # #FDF3EC
WARNING_BORDER = (244, 214, 195, 255)      # #F4D6C3
WARNING_DARK = (120, 53, 27, 255)          # #78351B

SPLASH_BG = (252, 244, 233, 255)           # #FCF4E9 (Official Brand Splash Canvas)
SPLASH_CONTENT = (48, 37, 22, 255)         # #302516

# ---------------------------------------------------------------------------
# 1. The Authentic Re:Focus Floating Counter Pill
# ---------------------------------------------------------------------------
def render_floating_counter(count=49, is_over_limit=False, scale=1.0, pulse_glow=False):
    """
    Renders the exact FloatingCounter pill from FloatingCounterManager.kt:
    - Pill stadium shape with drop shadow
    - "REELS" micro-label in bold
    - Bold numeric counter
    - Distinct Lime (normal) vs Terracotta Coral (warning/limit hit) states
    - Optional animated lime/coral ambient glow
    """
    w = int(140 * scale)
    h = int(90 * scale)
    img = Image.new('RGBA', (w, h), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)

    bg_color = WARNING_SOFT if is_over_limit else (255, 255, 255, 255)
    border_color = WARNING_CORAL if is_over_limit else (LIME_PRIMARY if pulse_glow else LIGHT_BORDER)
    label_color = WARNING_CORAL if is_over_limit else TEXT_SECONDARY
    num_color = WARNING_CORAL if is_over_limit else TEXT_PRIMARY

    # Multi-stage drop shadow
    pad = int(8 * scale)
    pill_box = [pad, pad, w - pad, h - pad]
    radius = (h - 2 * pad) // 2

    shadow = Image.new('RGBA', (w, h), (0, 0, 0, 0))
    sdraw = ImageDraw.Draw(shadow)
    shadow_color = (200, 109, 59, 120) if is_over_limit else (184, 217, 78, 140 if pulse_glow else 50)
    sdraw.rounded_rectangle([pad, pad + int(3*scale), w - pad, h - pad + int(3*scale)], radius=radius, fill=shadow_color)
    shadow = shadow.filter(ImageFilter.GaussianBlur(int(5 * scale)))
    img = Image.alpha_composite(img, shadow)

    # Main Pill Body
    draw = ImageDraw.Draw(img)
    draw.rounded_rectangle(pill_box, radius=radius, fill=bg_color, outline=border_color, width=max(1, int(2.5 * scale)))

    # Micro-label "REELS"
    font_label = get_font('bold', max(10, int(12 * scale)))
    draw.text((w // 2, int(28 * scale)), "REELS", font=font_label, fill=label_color, anchor='mm')

    # Count Numeral
    font_num = get_font('bold', max(18, int(34 * scale)))
    draw.text((w // 2, int(56 * scale)), str(count), font=font_num, fill=num_color, anchor='mm')

    return img

# ---------------------------------------------------------------------------
# 2. Flagship Smartphone Mockup Frame
# ---------------------------------------------------------------------------
def render_smartphone_mockup(screen_image, scale=1.0, tilt_angle=0.0):
    """
    Renders a screen image inside an ultra-premium titanium smartphone chassis.
    Features:
    - Precision rounded titanium bezel with dual specular rim highlights
    - Centered optical camera punch-hole
    - Diagonal glass glare reflection
    - Layered ambient occlusion & contact shadow for dimensional pop
    """
    sw, sh = screen_image.size
    border_thick = 16
    phone_w = sw + border_thick * 2
    phone_h = sh + border_thick * 2

    # Canvas with padding for shadow
    pad_x = 80
    pad_y = 90
    canvas_w = phone_w + pad_x * 2
    canvas_h = phone_h + pad_y * 2
    canvas = Image.new('RGBA', (canvas_w, canvas_h), (0, 0, 0, 0))

    # Stage 1: Soft diffuse floor shadow
    floor_shadow = Image.new('RGBA', (canvas_w, canvas_h), (0, 0, 0, 0))
    fsdraw = ImageDraw.Draw(floor_shadow)
    fsbox = [pad_x + 10, pad_y + 36, pad_x + phone_w - 10, pad_y + phone_h + 36]
    fsdraw.rounded_rectangle(fsbox, radius=56, fill=(0, 0, 0, 110))
    floor_shadow = floor_shadow.filter(ImageFilter.GaussianBlur(38))
    canvas = Image.alpha_composite(canvas, floor_shadow)

    # Stage 2: Sharp ambient occlusion shadow
    ao_shadow = Image.new('RGBA', (canvas_w, canvas_h), (0, 0, 0, 0))
    aodraw = ImageDraw.Draw(ao_shadow)
    aobox = [pad_x + 2, pad_y + 12, pad_x + phone_w - 2, pad_y + phone_h + 12]
    aodraw.rounded_rectangle(aobox, radius=50, fill=(0, 0, 0, 160))
    ao_shadow = ao_shadow.filter(ImageFilter.GaussianBlur(14))
    canvas = Image.alpha_composite(canvas, ao_shadow)

    # Stage 3: Smartphone Chassis
    phone = Image.new('RGBA', (phone_w, phone_h), (0, 0, 0, 0))
    pdraw = ImageDraw.Draw(phone)

    # Outer metallic titanium frame
    outer_box = [0, 0, phone_w, phone_h]
    pdraw.rounded_rectangle(outer_box, radius=52, fill=(24, 26, 32, 255), outline=(58, 63, 74, 255), width=2)

    # Inner bezel
    inner_box = [border_thick - 2, border_thick - 2, phone_w - border_thick + 2, phone_h - border_thick + 2]
    pdraw.rounded_rectangle(inner_box, radius=42, fill=(12, 13, 16, 255))

    # Screen Paste with curved corner mask
    screen_mask = Image.new('L', (sw, sh), 0)
    ImageDraw.Draw(screen_mask).rounded_rectangle([0, 0, sw, sh], radius=38, fill=255)
    phone.paste(screen_image, (border_thick, border_thick), screen_mask)

    # Dynamic Diagonal Glass Sheen
    glare = Image.new('RGBA', (sw, sh), (0, 0, 0, 0))
    gdraw = ImageDraw.Draw(glare)
    # 35-degree diagonal light sweep
    poly = [(0, 0), (int(sw * 0.45), 0), (int(sw * 0.15), sh), (0, sh)]
    gdraw.polygon(poly, fill=(255, 255, 255, 14))
    poly2 = [(int(sw * 0.48), 0), (int(sw * 0.58), 0), (int(sw * 0.28), sh), (int(sw * 0.18), sh)]
    gdraw.polygon(poly2, fill=(255, 255, 255, 20))
    glare = glare.filter(ImageFilter.GaussianBlur(6))
    phone.paste(glare, (border_thick, border_thick), screen_mask)

    # Specular Edge Highlight (Rim Light)
    pdraw.arc([4, 4, phone_w - 4, phone_h - 4], start=180, end=270, fill=(255, 255, 255, 85), width=2)
    pdraw.arc([4, 4, phone_w - 4, phone_h - 4], start=0, end=90, fill=(184, 217, 78, 90), width=1)

    # Centered Camera Punch-Hole
    cam_w, cam_h = 24, 24
    cam_x = (phone_w - cam_w) // 2
    cam_y = border_thick + 12
    pdraw.ellipse([cam_x, cam_y, cam_x + cam_w, cam_y + cam_h], fill=(8, 10, 14, 255), outline=(28, 32, 42, 255), width=2)
    # Camera lens reflection core
    pdraw.ellipse([cam_x + 6, cam_y + 6, cam_x + 13, cam_y + 13], fill=(20, 35, 65, 255))
    pdraw.ellipse([cam_x + 8, cam_y + 8, cam_x + 10, cam_y + 10], fill=(70, 110, 190, 220))

    canvas.paste(phone, (pad_x, pad_y), phone)

    # Scale & Rotation transforms
    if scale != 1.0:
        new_w = max(1, int(canvas_w * scale))
        new_h = max(1, int(canvas_h * scale))
        canvas = canvas.resize((new_w, new_h), Image.Resampling.LANCZOS)

    if tilt_angle != 0.0:
        canvas = canvas.rotate(tilt_angle, resample=Image.Resampling.BICUBIC, expand=True)

    return canvas

# ---------------------------------------------------------------------------
# 3. Authentic Re:Focus Dashboard Screen
# ---------------------------------------------------------------------------
def render_phone_screen_dashboard(daily_limit=50, reels_watched=49, strict_mode_on=True, active_tab=0, limit_save_highlight=False):
    """
    Renders the authentic Re:Focus Dashboard screen (600 x 1240).
    Directly reflects DashboardScreen.kt and HeroProgressCard.kt.
    """
    w, h = 600, 1240
    screen = Image.new('RGBA', (w, h), LIGHT_BG)
    draw = ImageDraw.Draw(screen)

    # Status Bar
    font_time = get_font('semibold', 16)
    draw.text((36, 18), "9:41", font=font_time, fill=TEXT_PRIMARY, anchor='lm')
    # Battery & Wifi icons
    draw.rounded_rectangle([w - 60, 12, w - 36, 24], radius=3, outline=TEXT_PRIMARY, width=1)
    draw.rectangle([w - 58, 14, w - 42, 22], fill=TEXT_PRIMARY)

    # Top Bar: Re:Focus Logo
    logo_path = 'video_production/assets/logos/ic_refocus_logo.png'
    if os.path.exists(logo_path):
        logo_img = Image.open(logo_path).convert('RGBA')
        logo_w = int(logo_img.width * 0.28)
        logo_h = int(logo_img.height * 0.28)
        logo_resized = logo_img.resize((logo_w, logo_h), Image.Resampling.LANCZOS)
        screen.paste(logo_resized, (36, 44), logo_resized)
    else:
        draw.text((36, 54), "Re:Focus", font=get_font('bold', 24), fill=TEXT_PRIMARY)

    # Profile Avatar
    draw.ellipse([w - 68, 40, w - 36, 72], fill=LIGHT_SURFACE, outline=LIGHT_BORDER, width=1)
    draw.ellipse([w - 56, 46, w - 48, 54], fill=TEXT_MUTED)
    draw.pieslice([w - 60, 56, w - 44, 72], 180, 360, fill=TEXT_MUTED)

    # -----------------------------------------------------------------------
    # Hero Progress Card (HeroProgressCard.kt)
    # -----------------------------------------------------------------------
    card_y = 96
    card_h = 440
    draw.rounded_rectangle([28, card_y, w - 28, card_y + card_h], radius=24, fill=LIGHT_SURFACE, outline=LIGHT_BORDER, width=1)

    draw.text((50, card_y + 28), "Today's Progress", font=get_font('semibold', 14), fill=TEXT_SECONDARY)
    
    # Large numbers: e.g. "49 / 50"
    draw.text((50, card_y + 68), f"{reels_watched}", font=get_font('bold', 44), fill=TEXT_PRIMARY)
    count_w = draw.textlength(f"{reels_watched}", font=get_font('bold', 44))
    draw.text((50 + count_w + 10, card_y + 76), f"/ {daily_limit}", font=get_font('medium', 22), fill=TEXT_MUTED)
    draw.text((50, card_y + 124), "Reels Watched Today", font=get_font('medium', 13), fill=TEXT_SECONDARY)

    # Linear Progress Indicator
    prog_y = card_y + 154
    prog_w = w - 100
    prog_pct = min(1.0, reels_watched / max(1, daily_limit))
    draw.rounded_rectangle([50, prog_y, 50 + prog_w, prog_y + 10], radius=5, fill=LIGHT_SURFACE_SOFT)
    prog_fill_w = int(prog_w * prog_pct)
    fill_color = WARNING_CORAL if reels_watched >= daily_limit else LIME_PRIMARY
    draw.rounded_rectangle([50, prog_y, 50 + prog_fill_w, prog_y + 10], radius=5, fill=fill_color)

    # Daily Limit Recessed Container
    limit_box_y = card_y + 184
    limit_box_h = 130
    draw.rounded_rectangle([50, limit_box_y, w - 50, limit_box_y + limit_box_h], radius=16, fill=LIGHT_SURFACE_SOFT, outline=LIGHT_BORDER, width=1)
    
    draw.text((68, limit_box_y + 24), "Daily Limit", font=get_font('bold', 15), fill=TEXT_PRIMARY)
    sub = "Active daily boundary" if reels_watched < daily_limit else "Limit reached for today"
    draw.text((68, limit_box_y + 46), sub, font=get_font('regular', 12), fill=WARNING_CORAL if reels_watched >= daily_limit else TEXT_SECONDARY)

    # Input Box showing daily_limit
    box_border = LIME_PRIMARY if limit_save_highlight else LIGHT_BORDER
    draw.rounded_rectangle([68, limit_box_y + 70, 160, limit_box_y + 112], radius=10, fill=LIGHT_SURFACE, outline=box_border, width=2)
    draw.text((114, limit_box_y + 91), str(daily_limit), font=get_font('bold', 18), fill=TEXT_PRIMARY, anchor='mm')

    # "Save Limit" Tactile Button (in Signature Lime)
    btn_fill = LIME_PRESSED if limit_save_highlight else LIME_PRIMARY
    draw.rounded_rectangle([176, limit_box_y + 70, 310, limit_box_y + 112], radius=10, fill=btn_fill)
    draw.text((243, limit_box_y + 91), "Save Limit", font=get_font('bold', 13), fill=TEXT_PRIMARY, anchor='mm')

    # Current Streak Row
    streak_y = card_y + 334
    draw.line([50, streak_y, w - 50, streak_y], fill=LIGHT_BORDER, width=1)
    draw.text((50, streak_y + 18), "Current Streak", font=get_font('semibold', 13), fill=TEXT_SECONDARY)
    draw.text((50, streak_y + 42), "14 Days", font=get_font('bold', 22), fill=TEXT_PRIMARY)
    
    # Active Streak Flame Badge
    draw.rounded_rectangle([w - 110, streak_y + 24, w - 50, streak_y + 56], radius=16, fill=(255, 87, 34, 30), outline=(255, 87, 34, 180), width=1)
    draw.text((w - 80, streak_y + 40), "ACTIVE", font=get_font('bold', 11), fill=(217, 107, 39), anchor='mm')

    # -----------------------------------------------------------------------
    # Strict Mode Card (StrictModeCard.kt)
    # -----------------------------------------------------------------------
    strict_y = card_y + card_h + 20
    strict_h = 100
    card_bg = (24, 27, 31, 255) if strict_mode_on else LIGHT_SURFACE
    card_border = LIME_PRIMARY if strict_mode_on else LIGHT_BORDER
    title_color = (245, 244, 238, 255) if strict_mode_on else TEXT_PRIMARY
    
    draw.rounded_rectangle([28, strict_y, w - 28, strict_y + strict_h], radius=16, fill=card_bg, outline=card_border, width=2 if strict_mode_on else 1)
    
    draw.text((48, strict_y + 26), "Strict Mode", font=get_font('bold', 16), fill=title_color)
    strict_sub = "Auto-closes apps at limit • Anti-Bypass active" if strict_mode_on else "Off • Tap switch to activate"
    draw.text((48, strict_y + 54), strict_sub, font=get_font('regular', 12), fill=LIME_PRIMARY if strict_mode_on else TEXT_SECONDARY)

    # Material 3 Switch Toggle
    sw_x = w - 90
    sw_y = strict_y + 35
    sw_w, sw_h = 52, 30
    sw_bg = LIME_PRIMARY if strict_mode_on else (200, 202, 198, 255)
    draw.rounded_rectangle([sw_x, sw_y, sw_x + sw_w, sw_y + sw_h], radius=sw_h//2, fill=sw_bg)
    thumb_x = sw_x + sw_w - 16 if strict_mode_on else sw_x + 16
    draw.ellipse([thumb_x - 11, sw_y + 4, thumb_x + 11, sw_y + sw_h - 4], fill=(255, 255, 255, 255))

    # -----------------------------------------------------------------------
    # Weekly Activity Mini Card
    # -----------------------------------------------------------------------
    week_y = strict_y + strict_h + 20
    week_h = 240
    draw.rounded_rectangle([28, week_y, w - 28, week_y + week_h], radius=20, fill=LIGHT_SURFACE, outline=LIGHT_BORDER, width=1)
    draw.text((48, week_y + 22), "Weekly Discipline Activity", font=get_font('bold', 15), fill=TEXT_PRIMARY)

    days = ["M", "T", "W", "T", "F", "S", "S"]
    vals = [24, 38, 42, 18, 50, 48, reels_watched]
    bar_w = 26
    start_bx = 64
    bx_gap = 72
    base_y = week_y + 190
    for idx, (d, v) in enumerate(zip(days, vals)):
        bx = start_bx + idx * bx_gap
        bh = int((v / 60.0) * 110)
        draw.rounded_rectangle([bx, base_y - 110, bx + bar_w, base_y], radius=6, fill=LIGHT_SURFACE_SOFT)
        bcolor = WARNING_CORAL if v >= 50 else LIME_PRIMARY
        draw.rounded_rectangle([bx, base_y - bh, bx + bar_w, base_y], radius=6, fill=bcolor)
        draw.text((bx + bar_w // 2, base_y + 16), d, font=get_font('semibold', 12), fill=TEXT_SECONDARY, anchor='mm')

    # Floating Bottom Nav Dock
    render_floating_dock_overlay(draw, w, h, active_tab)
    return screen

def render_floating_dock_overlay(draw, w, h, active_tab=0):
    dock_y = h - 110
    dock_h = 66
    dock_w = w - 64
    draw.rounded_rectangle([32, dock_y, 32 + dock_w, dock_y + dock_h], radius=dock_h//2, fill=(255, 255, 255, 250), outline=LIGHT_BORDER, width=1)
    
    tabs = ["Dashboard", "Analytics", "Calendar", "Settings"]
    tab_w = dock_w // 4
    for i, t in enumerate(tabs):
        tx = 32 + i * tab_w + tab_w // 2
        is_sel = (i == active_tab)
        if is_sel:
            draw.rounded_rectangle([32 + i * tab_w + 8, dock_y + 8, 32 + (i + 1) * tab_w - 8, dock_y + dock_h - 8], radius=24, fill=LIME_PRIMARY)
            draw.text((tx, dock_y + dock_h // 2), t, font=get_font('bold', 11), fill=TEXT_PRIMARY, anchor='mm')
        else:
            draw.text((tx, dock_y + dock_h // 2), t, font=get_font('semibold', 11), fill=TEXT_MUTED, anchor='mm')

# ---------------------------------------------------------------------------
# 4. Authentic 84-Day Goal Calendar & Consistency Heatmap
# ---------------------------------------------------------------------------
def render_calendar_screen(highlight_progress=1.0):
    """
    Renders the authentic Re:Focus Calendar screen with the 84-day discipline heatmap.
    """
    w, h = 600, 1240
    screen = Image.new('RGBA', (w, h), LIGHT_BG)
    draw = ImageDraw.Draw(screen)

    # Status Bar
    draw.text((36, 18), "9:41", font=get_font('semibold', 16), fill=TEXT_PRIMARY, anchor='lm')
    draw.rounded_rectangle([w - 60, 12, w - 36, 24], radius=3, outline=TEXT_PRIMARY, width=1)
    draw.rectangle([w - 58, 14, w - 42, 22], fill=TEXT_PRIMARY)

    draw.text((36, 52), "Calendar & Streaks", font=get_font('bold', 22), fill=TEXT_PRIMARY)

    # Hero Card: Discipline Level
    hero_y = 96
    hero_h = 160
    draw.rounded_rectangle([28, hero_y, w - 28, hero_y + hero_h], radius=24, fill=LIGHT_SURFACE, outline=LIGHT_BORDER, width=1)
    draw.text((50, hero_y + 24), "Current Discipline Level", font=get_font('semibold', 13), fill=TEXT_SECONDARY)
    draw.text((50, hero_y + 54), "Consistent Tier", font=get_font('bold', 28), fill=TEXT_PRIMARY)
    draw.text((50, hero_y + 92), "14-Day Streak • 16 days to Disciplined Tier (50 Days)", font=get_font('regular', 12), fill=TEXT_SECONDARY)

    # Tier Progress Bar
    bar_y = hero_y + 124
    draw.rounded_rectangle([50, bar_y, w - 50, bar_y + 8], radius=4, fill=LIGHT_SURFACE_SOFT)
    draw.rounded_rectangle([50, bar_y, 50 + int((w - 100) * 0.46), bar_y + 8], radius=4, fill=LIME_PRIMARY)

    # 84-Day Grid Heatmap (12 weeks x 7 days)
    cal_y = hero_y + hero_h + 20
    cal_h = 580
    draw.rounded_rectangle([28, cal_y, w - 28, cal_y + cal_h], radius=24, fill=LIGHT_SURFACE, outline=LIGHT_BORDER, width=1)
    draw.text((50, cal_y + 24), "84-Day Consistency Grid", font=get_font('bold', 16), fill=TEXT_PRIMARY)
    draw.text((50, cal_y + 48), "Each tile represents daily reel discipline adherence", font=get_font('regular', 12), fill=TEXT_SECONDARY)

    # Grid
    cols = 12
    rows = 7
    box_s = 34
    gap = 8
    start_gx = 48
    start_gy = cal_y + 85

    # Day labels (M, W, F)
    for r_idx, d_name in [(0, "M"), (2, "W"), (4, "F"), (6, "S")]:
        draw.text((start_gx - 18, start_gy + r_idx * (box_s + gap) + box_s//2), d_name, font=get_font('semibold', 10), fill=TEXT_MUTED, anchor='rm')

    total_tiles = cols * rows
    active_limit = int(total_tiles * highlight_progress)

    for c in range(cols):
        for r in range(rows):
            idx = c * rows + r
            bx = start_gx + c * (box_s + gap)
            by = start_gy + r * (box_s + gap)
            
            if idx < active_limit:
                # 85% successful green days, 15% skull days
                is_skull = (idx in [11, 23, 41, 56])
                color = WARNING_CORAL if is_skull else LIME_PRIMARY
            else:
                color = LIGHT_SURFACE_SOFT

            draw.rounded_rectangle([bx, by, bx + box_s, by + box_s], radius=6, fill=color)

    # Grid Legend
    leg_y = cal_y + cal_h - 40
    draw.rounded_rectangle([50, leg_y, 64, leg_y + 14], radius=3, fill=LIME_PRIMARY)
    draw.text((72, leg_y + 7), "Goal Met", font=get_font('semibold', 11), fill=TEXT_SECONDARY, anchor='lm')

    draw.rounded_rectangle([150, leg_y, 164, leg_y + 14], radius=3, fill=WARNING_CORAL)
    draw.text((172, leg_y + 7), "Limit Exceeded", font=get_font('semibold', 11), fill=TEXT_SECONDARY, anchor='lm')

    draw.rounded_rectangle([285, leg_y, 299, leg_y + 14], radius=3, fill=LIGHT_SURFACE_SOFT)
    draw.text((307, leg_y + 7), "Upcoming", font=get_font('semibold', 11), fill=TEXT_SECONDARY, anchor='lm')

    render_floating_dock_overlay(draw, w, h, active_tab=2)
    return screen

# ---------------------------------------------------------------------------
# 5. Authentic Re:Focus Analytics Screen
# ---------------------------------------------------------------------------
def render_analytics_screen():
    """
    Renders the authentic Re:Focus Analytics screen showing downward trend & reclaimed time.
    """
    w, h = 600, 1240
    screen = Image.new('RGBA', (w, h), LIGHT_BG)
    draw = ImageDraw.Draw(screen)

    # Status Bar
    draw.text((36, 18), "9:41", font=get_font('semibold', 16), fill=TEXT_PRIMARY, anchor='lm')
    draw.rounded_rectangle([w - 60, 12, w - 36, 24], radius=3, outline=TEXT_PRIMARY, width=1)
    draw.rectangle([w - 58, 14, w - 42, 22], fill=TEXT_PRIMARY)

    draw.text((36, 52), "Analytics & Trends", font=get_font('bold', 22), fill=TEXT_PRIMARY)

    # Trend Overview Card
    graph_y = 96
    graph_h = 420
    draw.rounded_rectangle([28, graph_y, w - 28, graph_y + graph_h], radius=24, fill=LIGHT_SURFACE, outline=LIGHT_BORDER, width=1)
    
    draw.text((50, graph_y + 24), "4-Week Consumption Curve", font=get_font('bold', 15), fill=TEXT_PRIMARY)
    draw.text((50, graph_y + 48), "-58% reduction in daily compulsive scrolling", font=get_font('regular', 12), fill=LIME_DARK)

    # Downward Curve
    weeks = ["W1", "W2", "W3", "W4"]
    vals = [142, 98, 64, 42]
    bar_width = 44
    gap = 88
    start_x = 76
    base_gy = graph_y + 350

    for idx, (d, val) in enumerate(zip(weeks, vals)):
        bx = start_x + idx * gap
        bh = int((val / 160.0) * 220)
        draw.rounded_rectangle([bx, base_gy - 220, bx + bar_width, base_gy], radius=8, fill=LIGHT_SURFACE_SOFT)
        bar_col = LIME_PRIMARY if idx == 3 else (210, 212, 206, 255)
        draw.rounded_rectangle([bx, base_gy - bh, bx + bar_width, base_gy], radius=8, fill=bar_col)
        draw.text((bx + bar_width // 2, base_gy + 18), d, font=get_font('semibold', 12), fill=TEXT_SECONDARY, anchor='mm')
        draw.text((bx + bar_width // 2, base_gy - bh - 14), str(val), font=get_font('bold', 12), fill=TEXT_PRIMARY, anchor='mm')

    # Reclaimed Time Card
    ins_y = graph_y + graph_h + 20
    ins_h = 180
    draw.rounded_rectangle([28, ins_y, w - 28, ins_y + ins_h], radius=24, fill=LIGHT_SURFACE, outline=LIGHT_BORDER, width=1)
    draw.text((50, ins_y + 26), "Attention Reclaimed", font=get_font('bold', 15), fill=TEXT_PRIMARY)
    draw.text((50, ins_y + 58), "18.5 Hours Saved", font=get_font('bold', 32), fill=LIME_DARK)
    draw.text((50, ins_y + 110), "Equivalent to 2.6 hours per week freed from infinite scrolling.", font=get_font('regular', 13), fill=TEXT_SECONDARY)
    draw.text((50, ins_y + 134), "100% on-device local calculation • Privacy preserved.", font=get_font('medium', 11), fill=TEXT_MUTED)

    render_floating_dock_overlay(draw, w, h, active_tab=1)
    return screen

# ---------------------------------------------------------------------------
# 6. Authentic Short-Form Video Feed Screen with Live Floating Counter
# ---------------------------------------------------------------------------
def render_reels_feed_screen(reel_number=1, counter_visible=True, counter_val=49, counter_over_limit=False, pulse_glow=False):
    """
    Renders a realistic short-form video feed (600 x 1240) with the genuine Re:Focus counter overlay.
    """
    w, h = 600, 1240
    screen = Image.new('RGBA', (w, h), (18, 20, 26, 255))
    draw = ImageDraw.Draw(screen)

    scenes = [
        {"top": (35, 60, 95), "bot": (12, 18, 30), "title": "Breathtaking Alps Sunrise", "user": "@alpine_wanderer", "likes": "218K"},
        {"top": (80, 45, 30), "bot": (28, 12, 16), "title": "Night City Neon Drive", "user": "@urban.tokyo", "likes": "94K"},
        {"top": (25, 75, 60), "bot": (10, 30, 24), "title": "Morning Matcha Recipe", "user": "@minimalist_chef", "likes": "340K"},
        {"top": (50, 30, 75), "bot": (18, 12, 35), "title": "10-Minute Desk Mobility", "user": "@daily.stretch", "likes": "112K"}
    ]
    scene = scenes[reel_number % len(scenes)]

    # Rich vertical gradient background representing video frame
    for y in range(h):
        fac = y / float(h)
        r = int(scene["top"][0] * (1 - fac) + scene["bot"][0] * fac)
        g = int(scene["top"][1] * (1 - fac) + scene["bot"][1] * fac)
        b = int(scene["top"][2] * (1 - fac) + scene["bot"][2] * fac)
        draw.line([(0, y), (w, y)], fill=(r, g, b, 255))

    # Center simulated video content preview container
    draw.rounded_rectangle([32, 160, w - 32, h - 260], radius=28, fill=(255, 255, 255, 22), outline=(255, 255, 255, 55), width=1)
    
    draw.text((w // 2, 420), scene["title"], font=get_font('bold', 24), fill=(255, 255, 255, 245), anchor='mm')
    draw.text((w // 2, 465), f"Reel #{reel_number} • Continuous Algorithm Feed", font=get_font('medium', 14), fill=(255, 255, 255, 175), anchor='mm')
    
    draw.polygon([(w//2, 530), (w//2 - 16, 550), (w//2 + 16, 550)], fill=(255, 255, 255, 200))
    draw.text((w // 2, 575), "Swipe Up", font=get_font('semibold', 12), fill=(255, 255, 255, 180), anchor='mm')

    # Right-side engagement stack (Likes, Comments, Share, Audio)
    rx = w - 54
    icons_y = [h - 460, h - 390, h - 320, h - 250]
    labels = [scene["likes"], "2.4K", "Share", "Audio"]
    for y, lbl in zip(icons_y, labels):
        draw.ellipse([rx - 20, y - 20, rx + 20, y + 20], fill=(0, 0, 0, 120), outline=(255, 255, 255, 60), width=1)
        draw.text((rx, y + 28), lbl, font=get_font('bold', 11), fill=(255, 255, 255, 225), anchor='mm')

    draw.text((40, h - 220), scene["user"], font=get_font('bold', 16), fill=(255, 255, 255, 255))
    draw.text((40, h - 190), "Auto-playing next short video in loop...", font=get_font('regular', 13), fill=(255, 255, 255, 210))

    # Top Status Bar
    draw.text((36, 18), "9:41", font=get_font('semibold', 16), fill=(255, 255, 255, 255))
    draw.text((w - 50, 18), "100%", font=get_font('semibold', 14), fill=(255, 255, 255, 255), anchor='rm')

    # OVERLAY: The Authentic Re:Focus Floating Counter!
    if counter_visible:
        pill = render_floating_counter(count=counter_val, is_over_limit=counter_over_limit, scale=1.1, pulse_glow=pulse_glow)
        screen.paste(pill, (w - pill.width - 24, 70), pill)

    return screen

# ---------------------------------------------------------------------------
# 7. Strict Mode Feed Lockout Shield (Auto-Close Screen)
# ---------------------------------------------------------------------------
def render_strict_mode_shield_overlay(w=600, h=1240):
    """
    Renders the auto-close screen presented when Strict Mode locks the feed.
    """
    overlay = Image.new('RGBA', (w, h), (14, 16, 20, 245))
    draw = ImageDraw.Draw(overlay)

    cx, cy = w // 2, h // 2 - 40
    # Center Shield Container
    card_w, card_h = 500, 340
    draw.rounded_rectangle([cx - card_w//2, cy - card_h//2, cx + card_w//2, cy + card_h//2], radius=28, fill=(24, 27, 34, 255), outline=LIME_PRIMARY, width=2)

    # Lock / Shield Emblem
    draw.ellipse([cx - 36, cy - 110, cx + 36, cy - 38], fill=(35, 42, 28, 255), outline=LIME_PRIMARY, width=2)
    draw.rectangle([cx - 16, cy - 78, cx + 16, cy - 50], fill=LIME_PRIMARY)
    draw.arc([cx - 12, cy - 96, cx + 12, cy - 72], start=180, end=360, fill=LIME_PRIMARY, width=4)

    draw.text((cx, cy - 6), "DAILY LIMIT REACHED", font=get_font('bold', 20), fill=(245, 244, 238), anchor='mm')
    draw.text((cx, cy + 28), "50 of 50 Reels Watched", font=get_font('semibold', 14), fill=WARNING_CORAL, anchor='mm')
    draw.text((cx, cy + 68), "Strict Mode has safely closed the feed.", font=get_font('regular', 13), fill=(168, 170, 169), anchor='mm')
    draw.text((cx, cy + 92), "Take a breath. Reclaim your focus.", font=get_font('medium', 13), fill=LIME_PRIMARY, anchor='mm')

    # Return to Home Button
    btn_w, btn_h = 320, 48
    btn_y = cy + 130
    draw.rounded_rectangle([cx - btn_w//2, btn_y - btn_h//2, cx + btn_w//2, btn_y + btn_h//2], radius=24, fill=LIME_PRIMARY)
    draw.text((cx, btn_y), "Return to Dashboard", font=get_font('bold', 13), fill=TEXT_PRIMARY, anchor='mm')

    return overlay
