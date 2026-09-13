"""Render the character part-breakdown spec sheet from the generated layers."""
import json
import math
import os
import sys

from PIL import Image, ImageDraw, ImageFont

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import gen_cat_parts as g  # noqa: E402

OUT = g.OUT
S = g.S

BG = (251, 247, 242)
CARD = (255, 255, 255)
INK = (74, 59, 51)
SUB = (150, 132, 120)
LINE = (232, 222, 213)
ACCENT = (224, 142, 154)

W = 2320
M = 70

FONT_CN = "/System/Library/Fonts/Hiragino Sans GB.ttc"
FONT_BOLD = "/System/Library/Fonts/STHeiti Medium.ttc"


def font(size, bold=False):
    return ImageFont.truetype(FONT_BOLD if bold else FONT_CN, size, index=1 if bold else 0)


def text(d, xy, s, f, fill=INK, anchor="la"):
    d.text(xy, s, font=f, fill=fill, anchor=anchor)


def card(d, box, radius=22, fill=CARD, outline=LINE, width=2):
    d.rounded_rectangle(box, radius=radius, fill=fill, outline=outline, width=width)


def fit(img, box_w, box_h):
    r = min(box_w / img.width, box_h / img.height)
    return img.resize((max(1, int(img.width * r)), max(1, int(img.height * r))), Image.LANCZOS)


def on_bg(img, size, bg=BG):
    out = Image.new('RGBA', size, bg + (255,))
    out.alpha_composite(img, ((size[0] - img.width) // 2, (size[1] - img.height) // 2))
    return out.convert('RGB')


def compose(layers, parts, transforms=None, group_pivot=None):
    """Composite the character with optional per-layer affine transforms."""
    out = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    transforms = transforms or {}
    for name, z, fn, pivot, role in parts:
        img = layers[name]
        t = transforms.get(name)
        if t:
            piv = (group_pivot[0] * S, group_pivot[1] * S) if group_pivot else (pivot[0] * S, pivot[1] * S)
            rot = t.get("rotate", 0.0)
            sc = t.get("scale", 1.0)
            dx = t.get("dx", 0.0) * S
            dy = t.get("dy", 0.0) * S
            if rot:
                img = img.rotate(rot, resample=Image.BICUBIC, center=piv)
            if sc != 1.0:
                # scale about the pivot
                big = img.resize((int(S * sc), int(S * sc)), Image.LANCZOS)
                tmp = Image.new('RGBA', (S, S), (0, 0, 0, 0))
                tmp.alpha_composite(big, (int(piv[0] - piv[0] * sc), int(piv[1] - piv[1] * sc)))
                img = tmp
            if dx or dy:
                tmp = Image.new('RGBA', (S, S), (0, 0, 0, 0))
                tmp.alpha_composite(img, (int(dx), int(dy)))
                img = tmp
        out.alpha_composite(img)
    return out


def silhouette(alpha_img, color=(226, 214, 203, 255)):
    sil = Image.new('RGBA', alpha_img.size, (0, 0, 0, 0))
    solid = Image.new('RGBA', alpha_img.size, color)
    sil.paste(solid, (0, 0), alpha_img)
    return sil


def main():
    layers, assembled, manifest = g.build()

    # ---- geometry -------------------------------------------------------
    header_h = 150
    hero_y = M + header_h + 10
    hero_size = 700
    stack_x = M + hero_size + 50
    stack_w = W - stack_x - M

    grid_top = hero_y + hero_size + 90
    cols, cell_w, cell_h, gap = 5, 400, 398, 22
    grid_w = cols * cell_w + (cols - 1) * gap
    grid_x = (W - grid_w) // 2
    rows = 3
    grid_bottom = grid_top + rows * cell_h + (rows - 1) * gap

    demo_top = grid_bottom + 90
    demo_h = 330
    footer_top = demo_top + demo_h + 70
    footer_h = 210
    H = footer_top + footer_h + M

    sheet = Image.new('RGB', (W, H), BG)
    d = ImageDraw.Draw(sheet)

    # ---- header ---------------------------------------------------------
    text(d, (M, M), "AI 猫咪 · 角色拆分设计规范", font(58, True))
    text(d, (M, M + 78),
         "Cat Character Part Sheet · v1.0 · 15 个可独立驱动图层 · 统一 1024×1024 坐标系 · 扁平日系插画风",
         font(26), SUB)
    d.line((M, M + header_h - 14, W - M, M + header_h - 14), fill=LINE, width=3)

    # ---- hero -----------------------------------------------------------
    card(d, (M - 20, hero_y - 20, M + hero_size + 20, hero_y + hero_size + 20))
    hero = fit(assembled, hero_size - 20, hero_size - 20)
    sheet.paste(on_bg(hero, (hero_size, hero_size)), (M, hero_y))
    text(d, (M + hero_size / 2, hero_y + hero_size + 6), "完整角色（全部图层按 z 叠加）",
         font(24), SUB, anchor="ma")

    # ---- layer stack ----------------------------------------------------
    text(d, (stack_x, hero_y - 8), "图层顺序（从下到上）", font(34, True))
    rows_y = hero_y + 56
    row_h = 44
    for i, p in enumerate(manifest["parts"]):
        name, z, role = p["name"], p["z"], p["role"]
        y = rows_y + i * row_h
        thumb = fit(layers[name], 34, 34)
        sheet.paste(on_bg(thumb, (36, 36), CARD), (stack_x, y + 2))
        text(d, (stack_x + 48, y + 4), f"{z:02d}", font(21, True), ACCENT)
        text(d, (stack_x + 92, y + 4), name, font(22, True))
        text(d, (stack_x + 285, y + 5), role, font(20), SUB)

    # ---- parts grid -----------------------------------------------------
    text(d, (M, grid_top - 62), "拆分部件（按实际画布位置裁切，十字为旋转/缩放锚点）", font(34, True))

    ghost = silhouette(assembled)
    for i, p in enumerate(manifest["parts"]):
        name, z = p["name"], p["z"]
        col, row = i % cols, i // cols
        x = grid_x + col * (cell_w + gap)
        y = grid_top + row * (cell_h + gap)

        card(d, (x, y, x + cell_w, y + cell_h))
        text(d, (x + 18, y + 14), f"z {z:02d}", font(19, True), ACCENT)
        text(d, (x + 74, y + 13), name, font(23, True))

        img = layers[name]
        bbox = img.getbbox()
        pad = 26
        crop = img.crop((max(0, bbox[0] - pad), max(0, bbox[1] - pad),
                         min(S, bbox[2] + pad), min(S, bbox[3] + pad)))
        shown = fit(crop, cell_w - 56, 232)
        sheet.paste(on_bg(shown, (cell_w - 56, 232), CARD), (x + 28, y + 48))

        # 位置示意
        inset = 104
        ix, iy = x + cell_w - inset - 22, y + cell_h - inset - 22
        mini = Image.new('RGBA', (S, S), (0, 0, 0, 0))
        mini.alpha_composite(ghost)
        mini.alpha_composite(img)
        ImageDraw.Draw(mini).rectangle(
            (max(0, bbox[0] - 6), max(0, bbox[1] - 6),
             min(S, bbox[2] + 6), min(S, bbox[3] + 6)), outline=ACCENT, width=10)
        sheet.paste(on_bg(fit(mini, inset, inset), (inset, inset), CARD), (ix, iy))
        d.rounded_rectangle((ix, iy, ix + inset, iy + inset), radius=8, outline=LINE, width=2)

        # 锚点
        pv = p["pivot_px"]
        if bbox[0] - pad < pv[0] < bbox[2] + pad and bbox[1] - pad < pv[1] < bbox[3] + pad:
            cxp = x + 28 + shown.width / 2 + (pv[0] - (bbox[0] + bbox[2]) / 2) * (shown.width / crop.width)
            cyp = y + 48 + shown.height / 2 + (pv[1] - (bbox[1] + bbox[3]) / 2) * (shown.height / crop.height)
            r = 10
            d.line((cxp - r, cyp, cxp + r, cyp), fill=ACCENT, width=3)
            d.line((cxp, cyp - r, cxp, cyp + r), fill=ACCENT, width=3)

        text(d, (x + 22, y + cell_h - 34), f"锚点 {pv[0]},{pv[1]} px", font(18), SUB)

    # ---- animation demo -------------------------------------------------
    text(d, (M, demo_top - 56), "可动性演示（同一套图层，仅施加独立变换）", font(34, True))

    demos = [
        ("静止", {}, "呼吸 / 默认"),
        ("眨眼", {"__eyes": "closed"}, "eye_l·r 纵向压缩"),
        ("耳朵抖动", {"ear_l": {"rotate": 22, "dx": -0.02, "dy": -0.03},
                   "ear_r": {"rotate": -8}}, "ear_l·r 绕耳根旋转"),
        ("摇尾巴", {"tail": {"rotate": 16}}, "tail 绕根端旋转"),
        ("歪头", {"__pivot": (0.50, 0.70), "head": {"rotate": -13},
                "ear_l": {"rotate": -13}, "ear_r": {"rotate": -13},
                "eye_l": {"rotate": -13}, "eye_r": {"rotate": -13},
                "eye_highlight": {"rotate": -13}, "mouth": {"rotate": -13},
                "cheek_l": {"rotate": -13}, "cheek_r": {"rotate": -13},
                "blush": {"rotate": -13}}, "head 组绕同一脖子锚点"),
        ("开心", {"__eyes": "happy", "blush": {"scale": 1.12, "dy": -0.01}},
         "换 eye/mouth 变体 + 腮红放大"),
    ]

    demo_cells = len(demos)
    dc_w = (W - 2 * M - (demo_cells - 1) * 20) // demo_cells
    for i, (title, tr, note) in enumerate(demos):
        x = M + i * (dc_w + 20)
        card(d, (x, demo_top, x + dc_w, demo_top + demo_h))
        text(d, (x + 16, demo_top + 12), title, font(22, True))
        text(d, (x + 16, demo_top + 44), note, font(17), SUB)

        t = dict(tr)
        eye_mode = t.pop("__eyes", None)
        group_pivot = t.pop("__pivot", None)
        use = dict(layers)
        if eye_mode:
            for side, cx in (("l", 0.392), ("r", 0.608)):
                c = g.Layer()
                variant = g.expr_eye_happy if eye_mode == "happy" else g.expr_eye_closed
                variant(c, cx)
                use[f"eye_{side}"] = c.img
            if eye_mode == "happy":
                c = g.Layer()
                g.expr_mouth_happy(c)
                use["mouth"] = c.img
                use["eye_highlight"] = Image.new('RGBA', (S, S), (0, 0, 0, 0))
        pose = compose(use, g.PARTS, t, group_pivot)
        ph = demo_h - 74
        sheet.paste(on_bg(fit(pose, dc_w - 30, ph), (dc_w - 30, ph), CARD), (x + 15, demo_top + 66))

    # ---- footer ---------------------------------------------------------
    d.line((M, footer_top - 30, W - M, footer_top - 30), fill=LINE, width=3)
    notes = [
        ("坐标系", "全部图层共用 1024×1024 画布、左上角原点，直接按 z 叠加即可还原完整角色；"
                   "也可按 layers.json 的 bbox 裁切为精灵图，锚点用 pivot_px。"),
        ("安全重叠", "head 下方留了脖子延伸区、ear 根部埋进头部、tail 根部埋进身体、paw 上端埋进身体，"
                   "所以旋转/位移不会露出缝隙。"),
        ("驱动建议", "eye 用纵向缩放做眨眼；ear/tail/head 绕各自 pivot 旋转；body 做纵向缩放与位移做呼吸和弹跳；"
                   "blush 调透明度；cheek 缩放做鼓腮。"),
        ("表情扩展", "expressions/ 里是 eye 与 mouth 的变体，替换同 z 的图层即可表达开心、委屈、张嘴等状态。"),
    ]
    for i, (k, v) in enumerate(notes):
        y = footer_top + i * 44
        text(d, (M, y), k, font(22, True), ACCENT)
        text(d, (M + 130, y + 2), v, font(21), SUB)

    path = os.path.join(OUT, "cat_spec_sheet.png")
    sheet.save(path)
    print("wrote", path, sheet.size)


if __name__ == '__main__':
    main()
