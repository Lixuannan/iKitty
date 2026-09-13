"""Generate the standardized, program-drivable cat character asset set.

Every part is drawn into its own fully transparent 1024x1024 layer using ONE shared
coordinate system, so assembling the character is a plain z-ordered composite and any
part can be rotated / scaled / offset independently around its documented pivot.
"""
import json
import math
import os

from PIL import Image, ImageDraw, ImageFont

S = 1024
OUT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# --- palette (low saturation, flat illustration) --------------------------
MAIN = (207, 180, 159, 255)      # 头/耳/尾 主色
BODY = (223, 204, 186, 255)      # 身体（略浅）
CREAM = (253, 248, 242, 255)     # 胸口/腹部/口鼻/爪
SHADE = (185, 158, 139, 255)
EAR_IN = (238, 169, 180, 255)
NOSE = (224, 142, 154, 255)
EYE = (74, 59, 51, 255)
PUPIL = (46, 37, 33, 255)
EYE_WARM = (132, 105, 90, 255)
SHINE = (255, 255, 255, 255)
MOUTH = (154, 106, 92, 255)
MOUTH_FILL = (217, 138, 151, 255)
TONGUE = (240, 163, 174, 255)
BLUSH = (240, 169, 176, 255)
SHADOW = (138, 116, 102, 255)


def px(v):
    return v * S


def rgba(c, a):
    return (c[0], c[1], c[2], a)


def lerp(a, b, t):
    return a + (b - a) * t


def cubic(p0, p1, p2, p3, n=56):
    out = []
    for i in range(n + 1):
        t = i / n
        mt = 1 - t
        out.append((
            mt ** 3 * p0[0] + 3 * mt * mt * t * p1[0] + 3 * mt * t * t * p2[0] + t ** 3 * p3[0],
            mt ** 3 * p0[1] + 3 * mt * mt * t * p1[1] + 3 * mt * t * t * p2[1] + t ** 3 * p3[1],
        ))
    return out


def quad(p0, p1, p2, n=34):
    out = []
    for i in range(n + 1):
        t = i / n
        mt = 1 - t
        out.append((
            mt * mt * p0[0] + 2 * mt * t * p1[0] + t * t * p2[0],
            mt * mt * p0[1] + 2 * mt * t * p1[1] + t * t * p2[1],
        ))
    return out


def rounded_tri(a, b, c, t):
    verts = [a, b, c]
    segs = []
    for i in range(3):
        v = verts[i]
        prev = verts[(i - 1) % 3]
        nxt = verts[(i + 1) % 3]
        segs.append(((lerp(v[0], prev[0], t), lerp(v[1], prev[1], t)), v,
                     (lerp(v[0], nxt[0], t), lerp(v[1], nxt[1], t))))
    out = [segs[0][0]]
    for start, v, end in segs:
        out += quad(out[-1], v, end, n=14)[1:]
    out.append(out[0])
    return out


def rotate_pts(pts, pivot, deg):
    r = math.radians(deg)
    cs, sn = math.cos(r), math.sin(r)
    return [(pivot[0] + (x - pivot[0]) * cs - (y - pivot[1]) * sn,
             pivot[1] + (x - pivot[0]) * sn + (y - pivot[1]) * cs) for x, y in pts]


class Layer:
    """A single transparent part on the shared canvas."""

    def __init__(self):
        self.img = Image.new('RGBA', (S, S), (0, 0, 0, 0))

    def blend(self, fn):
        tmp = Image.new('RGBA', (S, S), (0, 0, 0, 0))
        fn(ImageDraw.Draw(tmp))
        self.img.alpha_composite(tmp)

    def soft(self, cx, cy, rx, ry, color, layers=7, peak=1.0):
        def draw(ld):
            for i in range(layers, 0, -1):
                f = i / layers
                a = int(255 * peak * (1 - f) ** 1.6)
                if a <= 0:
                    continue
                ld.ellipse((px(cx - rx * f), px(cy - ry * f), px(cx + rx * f), px(cy + ry * f)),
                           fill=rgba(color, a))
        self.blend(draw)

    def ribbon(self, spine, w0, w1, color, steps=64):
        n = len(spine) - 1

        def draw(ld):
            for i in range(steps + 1):
                t = i / steps
                pos = t * n
                idx = min(n - 1, int(pos))
                local = pos - idx
                x = lerp(spine[idx][0], spine[idx + 1][0], local)
                y = lerp(spine[idx][1], spine[idx + 1][1], local)
                r = px(lerp(w0, w1, t)) / 2
                ld.ellipse((x - r, y - r, x + r, y + r), fill=color)
        self.blend(draw)


# ---------------------------------------------------------------------------
# Part geometry (shared coordinate system)
# ---------------------------------------------------------------------------

def head_path():
    """猫脸：比正圆更宽、脸颊外鼓、下巴圆润，下方带一段脖子安全重叠区。"""
    p = [(px(0.500), px(0.148))]
    p += cubic((px(0.500), px(0.148)), (px(0.352), px(0.148)), (px(0.252), px(0.196)), (px(0.228), px(0.312)))[1:]
    p += cubic((px(0.228), px(0.312)), (px(0.204), px(0.418)), (px(0.238), px(0.552)), (px(0.320), px(0.592)))[1:]
    p += cubic((px(0.320), px(0.592)), (px(0.365), px(0.617)), (px(0.415), px(0.656)), (px(0.500), px(0.658)))[1:]
    p += cubic((px(0.500), px(0.658)), (px(0.585), px(0.656)), (px(0.635), px(0.617)), (px(0.680), px(0.592)))[1:]
    p += cubic((px(0.680), px(0.592)), (px(0.762), px(0.552)), (px(0.796), px(0.418)), (px(0.772), px(0.312)))[1:]
    p += cubic((px(0.772), px(0.312)), (px(0.748), px(0.196)), (px(0.648), px(0.148)), (px(0.500), px(0.148)))[1:]
    return p


def body_path():
    """坐姿身体：上端埋进头部下方，保证头部旋转不会露缝。"""
    p = [(px(0.345), px(0.440))]
    p += cubic((px(0.345), px(0.440)), (px(0.292), px(0.500)), (px(0.276), px(0.620)), (px(0.278), px(0.748)))[1:]
    p += cubic((px(0.278), px(0.748)), (px(0.280), px(0.858)), (px(0.332), px(0.930)), (px(0.500), px(0.932)))[1:]
    p += cubic((px(0.500), px(0.932)), (px(0.668), px(0.930)), (px(0.720), px(0.858)), (px(0.722), px(0.748)))[1:]
    p += cubic((px(0.722), px(0.748)), (px(0.724), px(0.620)), (px(0.708), px(0.500)), (px(0.655), px(0.440)))[1:]
    p += cubic((px(0.655), px(0.440)), (px(0.570), px(0.396)), (px(0.430), px(0.396)), (px(0.345), px(0.440)))[1:]
    return p


def ear_points(left):
    """耳根埋得更深，给旋转留安全重叠区。"""
    if left:
        bo, tip, bi = (0.212, 0.322), (0.238, 0.018), (0.464, 0.192)
    else:
        bo, tip, bi = (0.788, 0.322), (0.762, 0.018), (0.536, 0.192)
    return ((px(bo[0]), px(bo[1])), (px(tip[0]), px(tip[1])), (px(bi[0]), px(bi[1])))


def ear_pivot(left):
    a, _, c = ear_points(left)
    return ((a[0] + c[0]) / 2, (a[1] + c[1]) / 2)


def tail_spine():
    return cubic((px(0.660), px(0.852)), (px(0.818), px(0.898)),
                 (px(0.906), px(0.792)), (px(0.892), px(0.674))) + \
           cubic((px(0.892), px(0.674)), (px(0.880), px(0.592)),
                 (px(0.828), px(0.548)), (px(0.774), px(0.578)))[1:]


def almond(cx, cy, rx, ry):
    p = quad((px(cx - rx), px(cy)), (px(cx - rx * 0.55), px(cy - ry)), (px(cx), px(cy - ry * 0.96)), n=18)
    p += quad((px(cx), px(cy - ry * 0.96)), (px(cx + rx * 0.55), px(cy - ry)), (px(cx + rx), px(cy)), n=18)[1:]
    p += quad((px(cx + rx), px(cy)), (px(cx + rx * 0.55), px(cy + ry)), (px(cx), px(cy + ry * 0.96)), n=18)[1:]
    p += quad((px(cx), px(cy + ry * 0.96)), (px(cx - rx * 0.55), px(cy + ry)), (px(cx - rx), px(cy)), n=18)[1:]
    return p


# ---------------------------------------------------------------------------
# Individual parts
# ---------------------------------------------------------------------------

def part_shadow(c):
    c.soft(0.500, 0.930, 0.190, 0.024, SHADOW, layers=6, peak=0.42)


def part_tail(c):
    spine = tail_spine()
    c.ribbon(spine, 0.105, 0.076, MAIN, steps=64)
    c.ribbon(spine[int(len(spine) * 0.72):], 0.088, 0.074, CREAM, steps=32)


def part_body(c):
    c.blend(lambda ld: ld.polygon(body_path(), fill=BODY))
    c.soft(0.500, 0.730, 0.150, 0.170, CREAM, layers=9, peak=0.95)
    c.soft(0.302, 0.790, 0.090, 0.105, SHADE, layers=5, peak=0.16)
    c.soft(0.698, 0.790, 0.090, 0.105, SHADE, layers=5, peak=0.16)


def _paw(c, cx):
    # 腿向上延伸进身体内部，留出位移安全区
    c.blend(lambda ld: ld.rounded_rectangle(
        (px(cx - 0.058), px(0.660), px(cx + 0.058), px(0.900)), radius=px(0.058), fill=BODY))
    c.soft(cx, 0.884, 0.074, 0.050, CREAM, layers=6, peak=1.0)
    for off in (-0.021, 0.021):
        c.blend(lambda ld, o=off: ld.line(
            (px(cx + o), px(0.866), px(cx + o), px(0.898)),
            fill=rgba(SHADE, 140), width=int(px(0.006))))


def part_paw_l(c):
    _paw(c, 0.418)


def part_paw_r(c):
    _paw(c, 0.582)


def _ear(c, left):
    a, b, d = ear_points(left)
    c.blend(lambda ld: ld.polygon(rounded_tri(a, b, d, 0.11), fill=MAIN))
    cx = (a[0] + b[0] + d[0]) / 3
    cy = (a[1] + b[1] + d[1]) / 3
    inner = [(cx + (x - cx) * 0.50, cy + (y - cy) * 0.50 + px(0.024)) for x, y in (a, b, d)]
    c.blend(lambda ld: ld.polygon(rounded_tri(*inner, 0.11), fill=EAR_IN))


def part_ear_l(c):
    _ear(c, True)


def part_ear_r(c):
    _ear(c, False)


def part_head(c):
    c.blend(lambda ld: ld.polygon(head_path(), fill=MAIN))
    # 口鼻浅色区
    c.soft(0.500, 0.518, 0.150, 0.092, CREAM, layers=8, peak=0.72)
    # 鼻子：小巧的倒三角
    nx, ny = 0.500, 0.478
    nose = quad((px(nx - 0.026), px(ny)), (px(nx), px(ny - 0.008)), (px(nx + 0.026), px(ny)))
    nose += quad((px(nx + 0.026), px(ny)), (px(nx + 0.016), px(ny + 0.024)), (px(nx), px(ny + 0.030)))[1:]
    nose += quad((px(nx), px(ny + 0.030)), (px(nx - 0.016), px(ny + 0.024)), (px(nx - 0.026), px(ny)))[1:]
    c.blend(lambda ld: ld.polygon(nose, fill=NOSE))


def part_cheek_l(c):
    c.soft(0.318, 0.535, 0.088, 0.062, CREAM, layers=7, peak=0.60)


def part_cheek_r(c):
    c.soft(0.682, 0.535, 0.088, 0.062, CREAM, layers=7, peak=0.60)


def part_blush(c):
    c.soft(0.300, 0.492, 0.068, 0.044, BLUSH, layers=8, peak=0.95)
    c.soft(0.700, 0.492, 0.068, 0.044, BLUSH, layers=8, peak=0.95)


def _eye_iris(c, cx, rx=0.064, ry=0.072):
    c.blend(lambda ld: ld.polygon(almond(cx, 0.390, rx, ry), fill=EYE))
    c.blend(lambda ld: ld.ellipse(
        (px(cx - rx * 0.22), px(0.390 - ry * 0.70), px(cx + rx * 0.22), px(0.390 + ry * 0.70)),
        fill=rgba(PUPIL, 120)))
    c.soft(cx, 0.390 + ry * 0.42, rx * 0.62, ry * 0.34, EYE_WARM, layers=5, peak=0.45)


def part_eye_l(c):
    _eye_iris(c, 0.392)


def part_eye_r(c):
    _eye_iris(c, 0.608)


def part_eye_highlight(c):
    for cx in (0.392, 0.608):
        rx, ry = 0.064, 0.072
        c.blend(lambda ld, x=cx: ld.ellipse(
            (px(x - rx * 0.60), px(0.390 - ry * 0.62),
             px(x - rx * 0.60) + px(0.026), px(0.390 - ry * 0.62) + px(0.026)), fill=SHINE))
        c.blend(lambda ld, x=cx: ld.ellipse(
            (px(x + rx * 0.18), px(0.390 + ry * 0.20),
             px(x + rx * 0.18) + px(0.014), px(0.390 + ry * 0.20) + px(0.014)),
            fill=rgba(SHINE, 205)))


def part_mouth(c):
    nx, my = 0.500, 0.532
    c.blend(lambda ld: ld.line(
        (px(nx), px(0.478 + 0.030), px(nx), px(my)), fill=rgba(MOUTH, 190), width=int(px(0.007))))
    for sign in (-1, 1):
        pts = quad((px(nx), px(my)), (px(nx + sign * 0.030 * 0.55), px(my + 0.020 * 1.6)),
                   (px(nx + sign * 0.030), px(my + 0.020 * 0.25)))
        c.blend(lambda ld, p=pts: ld.line(p, fill=MOUTH, width=int(px(0.012)), joint='curve'))


# --- optional expression variants ----------------------------------------

def expr_eye_happy(c, cx):
    pts = quad((px(cx - 0.064), px(0.390 + 0.072 * 0.18)),
               (px(cx), px(0.390 + 0.072 * 0.18 - 0.072)),
               (px(cx + 0.064), px(0.390 + 0.072 * 0.18)))
    c.blend(lambda ld: ld.line(pts, fill=EYE, width=int(px(0.027)), joint='curve'))


def expr_eye_closed(c, cx):
    pts = quad((px(cx - 0.058), px(0.390 + 0.072 * 0.05)),
               (px(cx), px(0.390 - 0.072 * 0.28)),
               (px(cx + 0.058), px(0.390 + 0.072 * 0.05)))
    c.blend(lambda ld: ld.line(pts, fill=EYE, width=int(px(0.021)), joint='curve'))


def expr_mouth_happy(c):
    nx, my = 0.500, 0.532
    c.blend(lambda ld: ld.line(
        (px(nx), px(0.508), px(nx), px(my)), fill=rgba(MOUTH, 190), width=int(px(0.007))))
    for sign in (-1, 1):
        pts = quad((px(nx), px(my)), (px(nx + sign * 0.040 * 0.55), px(my + 0.032 * 1.6)),
                   (px(nx + sign * 0.040), px(my + 0.032 * 0.25)))
        c.blend(lambda ld, p=pts: ld.line(p, fill=MOUTH, width=int(px(0.013)), joint='curve'))


def expr_mouth_sad(c):
    nx, my = 0.500, 0.532
    for sign in (-1, 1):
        pts = quad((px(nx), px(my)), (px(nx + sign * 0.030 * 0.55), px(my - 0.014 * 1.6)),
                   (px(nx + sign * 0.030), px(my - 0.014 * 0.25)))
        c.blend(lambda ld, p=pts: ld.line(p, fill=MOUTH, width=int(px(0.012)), joint='curve'))


def expr_mouth_open(c):
    nx, top = 0.500, 0.508
    hw, hg = px(0.050) / 2, px(0.050)
    pts = quad((px(nx) - hw, px(top)), (px(nx) - hw, px(top) + hg), (px(nx), px(top) + hg))
    pts += quad((px(nx), px(top) + hg), (px(nx) + hw, px(top) + hg), (px(nx) + hw, px(top)))[1:]
    c.blend(lambda ld: ld.polygon(pts, fill=MOUTH_FILL))
    c.blend(lambda ld: ld.ellipse((px(nx) - hw * 0.66, px(top) + hg * 0.55,
                                   px(nx) + hw * 0.66, px(top) + hg * 1.4), fill=TONGUE))


# ---------------------------------------------------------------------------
# Manifest
# ---------------------------------------------------------------------------

PARTS = [
    ("shadow", 0, part_shadow, (0.500, 0.930), "脚下软阴影，随身体压缩/拉伸改变大小与不透明度"),
    ("tail", 1, part_tail, (0.660, 0.852), "绕根端旋转做摇尾；根部埋进身体，安全重叠"),
    ("body", 2, part_body, (0.500, 0.930), "坐姿躯干，底部为缩放/呼吸锚点"),
    ("paw_l", 3, part_paw_l, (0.418, 0.800), "左前爪，可上下位移做踏步/轻点"),
    ("paw_r", 4, part_paw_r, (0.582, 0.800), "右前爪，可上下位移做踏步/轻点"),
    ("ear_l", 5, part_ear_l, (0.338, 0.257), "绕耳根旋转：竖起/压平/抖动"),
    ("ear_r", 6, part_ear_r, (0.662, 0.257), "绕耳根旋转：竖起/压平/抖动"),
    ("head", 7, part_head, (0.500, 0.700), "含脸型、口鼻浅色区与鼻子；锚点在脖子根部，可歪头/摇头"),
    ("cheek_l", 8, part_cheek_l, (0.318, 0.535), "左脸颊，可缩放做鼓腮/害羞"),
    ("cheek_r", 9, part_cheek_r, (0.682, 0.535), "右脸颊，可缩放做鼓腮/害羞"),
    ("blush", 10, part_blush, (0.500, 0.492), "腮红，整体调透明度控制害羞程度"),
    ("eye_l", 11, part_eye_l, (0.392, 0.390), "左眼虹膜+竖瞳；纵向缩放做眨眼/半闭"),
    ("eye_r", 12, part_eye_r, (0.608, 0.390), "右眼虹膜+竖瞳；纵向缩放做眨眼/半闭"),
    ("eye_highlight", 13, part_eye_highlight, (0.500, 0.390), "眼睛高光，可单独淡出"),
    ("mouth", 14, part_mouth, (0.500, 0.532), "默认柔和微笑；表情变体见 expressions/"),
]

EXPRESSIONS = [
    ("eye_l_happy", 11, lambda c: expr_eye_happy(c, 0.392), (0.392, 0.390), "弯月眼（开心）"),
    ("eye_r_happy", 12, lambda c: expr_eye_happy(c, 0.608), (0.608, 0.390), "弯月眼（开心）"),
    ("eye_l_closed", 11, lambda c: expr_eye_closed(c, 0.392), (0.392, 0.390), "闭眼（眨眼/困倦）"),
    ("eye_r_closed", 12, lambda c: expr_eye_closed(c, 0.608), (0.608, 0.390), "闭眼（眨眼/困倦）"),
    ("mouth_happy", 14, expr_mouth_happy, (0.500, 0.532), "大笑"),
    ("mouth_sad", 14, expr_mouth_sad, (0.500, 0.532), "委屈下弯"),
    ("mouth_open", 14, expr_mouth_open, (0.500, 0.532), "张嘴（兴奋/哈欠）"),
]


def build():
    os.makedirs(os.path.join(OUT, "layers"), exist_ok=True)
    os.makedirs(os.path.join(OUT, "expressions"), exist_ok=True)

    layers = {}
    for name, z, fn, pivot, role in PARTS:
        c = Layer()
        fn(c)
        layers[name] = c.img
        c.img.save(os.path.join(OUT, "layers", f"{name}.png"))

    for name, z, fn, pivot, role in EXPRESSIONS:
        c = Layer()
        fn(c)
        c.img.save(os.path.join(OUT, "expressions", f"{name}.png"))

    assembled = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    for name, z, fn, pivot, role in PARTS:
        assembled.alpha_composite(layers[name])
    assembled.save(os.path.join(OUT, "preview_assembled.png"))

    manifest = {
        "name": "AI Cat Character",
        "version": "1.0",
        "canvas": {"width": S, "height": S, "origin": "top-left", "unit": "px"},
        "note": "所有图层使用同一张 1024x1024 画布与同一坐标系，直接按 z 从低到高叠加即为完整角色。",
        "parts": [
            {
                "name": name, "z": z, "file": f"layers/{name}.png",
                "pivot_px": [round(pivot[0] * S), round(pivot[1] * S)],
                "pivot_norm": [pivot[0], pivot[1]],
                "bbox_px": list(layers[name].getbbox()),
                "role": role,
            }
            for name, z, fn, pivot, role in PARTS
        ],
        "expressions": [
            {
                "name": name, "replaces_layer_z": z,
                "file": f"expressions/{name}.png",
                "pivot_px": [round(pivot[0] * S), round(pivot[1] * S)],
                "role": role,
            }
            for name, z, fn, pivot, role in EXPRESSIONS
        ],
    }
    with open(os.path.join(OUT, "layers.json"), "w") as fh:
        json.dump(manifest, fh, ensure_ascii=False, indent=2)

    return layers, assembled, manifest


if __name__ == '__main__':
    build()
    print("assets written to", OUT)
