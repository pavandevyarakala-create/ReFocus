"""
generate_thumbnail.py - High-CTR Video Thumbnail Generator
Creates 1080x1920 high-resolution thumbnail in both lossless PNG and compressed JPEG.
"""

import os, sys
from PIL import Image, ImageDraw, ImageFont, ImageFilter

sys.path.append('video_production/scripts')
from ui_components import (
    get_font, LIME_PRIMARY, LIME_PRESSED, LIME_DARK,
    LIGHT_BG, LIGHT_SURFACE, LIGHT_SURFACE_SOFT, LIGHT_BORDER,
    TEXT_PRIMARY, TEXT_SECONDARY, TEXT_MUTED,
    DARK_BG, DARK_SURFACE, DARK_BORDER,
    WARNING_CORAL, WARNING_SOFT, WARNING_BORDER,
    render_reels_feed_screen, render_smartphone_mockup
)

WIDTH = 1080
HEIGHT = 1920

def generate_thumbnail():
    print("Generating High-CTR Video Thumbnail (1080x1920)...")
    
    # 1. Dark Luxury Studio Backdrop
    canvas = Image.new('RGB', (WIDTH, HEIGHT), (9, 10, 12))
    draw = ImageDraw.Draw(canvas)
    
    cx, cy = WIDTH // 2, int(HEIGHT * 0.42)
    max_r = 1100
    for r in range(max_r, 0, -6):
        fac = 1.0 - (r / float(max_r))
        draw.ellipse([cx - r, cy - r, cx + r, cy + r], fill=(int(9 + 24 * fac**2), int(10 + 28 * fac**2), int(12 + 38 * fac**2)))

    # 2. Header: Value Proposition Badge & Bold Headline
    draw.rounded_rectangle([WIDTH//2 - 210, 150, WIDTH//2 + 210, 206], radius=28, fill=(28, 35, 20), outline=LIME_PRIMARY, width=2)
    draw.text((WIDTH // 2, 178), "DIGITAL WELLNESS FOR ANDROID", font=get_font('bold', 18), fill=LIME_PRIMARY, anchor='mm')

    draw.text((WIDTH // 2, 280), "STOP THE", font=get_font('bold', 72), fill=(245, 244, 238), anchor='mm')
    draw.text((WIDTH // 2, 360), "INFINITE SCROLL.", font=get_font('bold', 72), fill=LIME_PRIMARY, anchor='mm')

    # 3. Phone Mockup: Reels Feed with Live Floating Counter
    feed_img = render_reels_feed_screen(reel_number=2, counter_visible=True, counter_val=49, counter_over_limit=False, pulse_glow=True)
    phone_mock = render_smartphone_mockup(feed_img, scale=0.86)
    
    px = WIDTH // 2 - phone_mock.width // 2
    py = 460
    canvas.paste(phone_mock, (px, py), phone_mock)

    # 4. Floating Badge Accent: "LIVE COUNTER OVERLAY"
    pill_w, pill_h = 600, 56
    pill_y = 1680
    draw.rounded_rectangle([WIDTH//2 - pill_w//2, pill_y - pill_h//2, WIDTH//2 + pill_w//2, pill_y + pill_h//2], radius=pill_h//2, fill=(24, 27, 34), outline=LIME_PRIMARY, width=2)
    draw.text((WIDTH // 2, pill_y), "SEE EVERY REEL IN REAL TIME", font=get_font('bold', 22), fill=LIME_PRIMARY, anchor='mm')

    # 5. Footer: Re:Focus by FOCRAMEX
    draw.text((WIDTH // 2, 1780), "Re:Focus by FOCRAMEX", font=get_font('bold', 26), fill=(245, 244, 238), anchor='mm')
    draw.text((WIDTH // 2, 1825), "Available for Android • 100% On-Device Privacy", font=get_font('medium', 20), fill=(168, 170, 169), anchor='mm')

    # Export PNG & JPEG
    out_png = 'video_production/output/refocus_thumbnail.png'
    out_jpg = 'video_production/output/refocus_thumbnail.jpg'
    canvas.save(out_png, 'PNG')
    canvas.save(out_jpg, 'JPEG', quality=95)
    print(f"Thumbnail exported successfully:\n  -> {out_png}\n  -> {out_jpg}")

if __name__ == '__main__':
    generate_thumbnail()
