"""Label patches on Figures 2 and 4 (drawn with another tool; no source exists): the *_base.png images in, ../d02_container.png and ../d03b_seq_triage_pay.png out. Run from diagrams/src."""
from PIL import Image, ImageDraw, ImageFont

REG = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"
BOLD = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"


def text_block(draw, centre, lines, font, pitch, fill="black"):
    cx, cy = centre
    top = cy - pitch * len(lines) / 2
    for i, line in enumerate(lines):
        w = draw.textlength(line, font=font)
        draw.text((cx - w / 2, top + i * pitch), line, font=font, fill=fill)


def erase_text(im, box, split_x=None, right_bg=None):
    """Remove grey text and its anti-aliasing inside a tight box; coloured pixels (lines, borders) are kept. Removed
    pixels become white, or right_bg right of split_x (the box straddles a panel border there)."""
    px = im.load()
    x0, y0, x1, y1 = box
    for x in range(x0, x1 + 1):
        for y in range(y0, y1 + 1):
            r, g, b = px[x, y]
            if max(r, g, b) - min(r, g, b) < 30:
                px[x, y] = right_bg if split_x is not None and x > split_x else (255, 255, 255)


def erase(im, box):
    """Fill a rectangle with the colour found at its top-left corner (the box or page background)."""
    colour = im.getpixel((box[0], box[1]))
    ImageDraw.Draw(im).rectangle(box, fill=colour)


# Figure 2: the EMR box names both EMRs by level; the OpenFn link is the core's sync queue, not a Kafka topic
im = Image.open("d02_container_base.png").convert("RGB")
d = ImageDraw.Draw(im)
erase(im, (1695, 892, 2078, 1092))
text_block(d, (1886, 992), ["eBuzima (health centres,", "a tenant per centre),", "hospital OpenMRS,",
                            "national registry /", "shared health record"], ImageFont.truetype(REG, 18), 23)
erase_text(im, (1624, 1221, 1674, 1240), split_x=1666, right_bg=(255, 251, 243))
f = ImageFont.truetype(REG, 16)
d.text((1592, 1204), "sync queue", font=f, fill="#2D3748")
d.text((1608, 1224), "(pulled)", font=f, fill="#2D3748")
im.save("../d02_container.png")

# Figure 4: the rules run inside the core (the triage module), not in a separate service
im = Image.open("d03b_seq_triage_pay_base.png").convert("RGB")
d = ImageDraw.Draw(im)
fb = ImageFont.truetype(BOLD, 20)
for top in (96, 922):
    erase(im, (938, top, 1200, top + 90))
    text_block(d, (1069, top + 45), ["Triage rules", "(module in the core)"], fb, 21)
erase_text(im, (716, 296, 1032, 339))
text_block(d, (873, 312), ["Evaluate rules in-process", "(synchronous)"], ImageFont.truetype(REG, 19), 20)
im.save("../d03b_seq_triage_pay.png")
print("patched")
