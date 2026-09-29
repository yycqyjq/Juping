#!/usr/bin/env python3
"""生成聚屏的全部位图资源（纯标准库，不需要 Pillow / ImageMagick）。

产出四样东西：
  1. ic_launcher.png —— 应用图标（深色圆角底 + 白屏幕 + 绿播放三角）
  2. ic_notify.png   —— 通知栏小图标，**必须纯白剪影**
  3. ic_banner.png   —— Android TV 启动器横幅（320×180）
  4. ic_music.png    —— 音乐投屏界面上的音符（纯音频时电视不能是一片黑）

为什么通知图标要单独做一张纯白的：
  从 Android 5.0 起，系统会把通知小图标里所有非透明像素强制涂成白色。
  如果直接复用彩色的应用图标，那个深色底会被涂成一个纯白方块，
  整个通知栏看起来就像加载失败。所以必须提供一张「只有形状、没有颜色」的版本。

为什么要有 banner：
  Android TV 的启动器不用方形图标，它要一张 320×180 的横幅。
  没有的话 lint 直接报 Error（MissingTvBanner），而且 TV 上会显示成一个难看的默认图。

实现要点：4 倍超采样后盒式平均降采样，得到抗锯齿边缘。
"""
import math
import os
import struct
import zlib

# 配色，与 res/values/colors.xml 保持一致
BG = (0x13, 0x1A, 0x21)        # 深色底
SCREEN = (0xF2, 0xF5, 0xF7)    # 近白屏幕
PLAY = (0x3D, 0xDC, 0x84)      # 强调绿
WHITE = (0xFF, 0xFF, 0xFF)     # 通知图标专用

SS = 4  # 超采样倍数
CLEAR = (0, 0, 0, 0)


# --------------------------------------------------------------- 几何判定

def in_rounded_rect(x, y, x0, y0, x1, y1, r):
    if x < x0 or x > x1 or y < y0 or y > y1:
        return False
    cx = min(max(x, x0 + r), x1 - r)
    cy = min(max(y, y0 + r), y1 - r)
    dx = x - cx
    dy = y - cy
    return dx * dx + dy * dy <= r * r


def in_rounded_rect_ring(x, y, x0, y0, x1, y1, r, t):
    """圆角矩形描边（空心）：在外框内、但不在内缩 t 的框内"""
    if not in_rounded_rect(x, y, x0, y0, x1, y1, r):
        return False
    inner_r = max(r - t, 0.0)
    return not in_rounded_rect(x, y, x0 + t, y0 + t, x1 - t, y1 - t, inner_r)


def in_play_triangle(x, y, cx, cy, w, h):
    """指向右的等腰三角形"""
    left = cx - w / 2.0
    t = (x - left) / w
    if t < 0 or t > 1:
        return False
    half = h * (1.0 - t)
    return abs(y - cy) <= half


# --------------------------------------------------------------- PNG 编码

def _encode_png(rows, w, h):
    """rows: h 行，每行 w 个 (r,g,b,a)"""
    raw = bytearray()
    for row in rows:
        raw.append(0)  # filter type 0
        for px in row:
            raw.extend(px)

    def chunk(typ, data):
        return (struct.pack('>I', len(data)) + typ + data
                + struct.pack('>I', zlib.crc32(typ + data) & 0xFFFFFFFF))

    png = b'\x89PNG\r\n\x1a\n'
    png += chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 6, 0, 0, 0))
    png += chunk(b'IDAT', zlib.compress(bytes(raw), 9))
    png += chunk(b'IEND', b'')
    return png


def _downsample(rows_big, w, h, ss=SS):
    """超采样图 → 目标尺寸。预乘 alpha 再平均，避免边缘发黑。

    注意是 w/h 两个维度都要收 —— 横幅是 320×180 的非正方形，
    早期版本只按一个边长降采样，会直接下标越界。
    """
    rows = []
    for py in range(h):
        row = []
        for px in range(w):
            r = g = b = a = 0
            for dy in range(ss):
                for dx in range(ss):
                    p = rows_big[py * ss + dy][px * ss + dx]
                    r += p[0] * p[3]
                    g += p[1] * p[3]
                    b += p[2] * p[3]
                    a += p[3]
            if a == 0:
                row.append(CLEAR)
            else:
                row.append((r // a, g // a, b // a, a // (ss * ss)))
        rows.append(row)
    return rows


def _rasterize(w, h, shade):
    """按 shade(x, y) 返回 (r,g,b,a) 渲染 w×h 图，坐标已归一化到 [0,1]"""
    big_w, big_h = w * SS, h * SS
    rows_big = []
    for py in range(big_h):
        row = []
        for px in range(big_w):
            x = (px + 0.5) / big_w
            y = (py + 0.5) / big_h
            row.append(shade(x, y))
        rows_big.append(row)
    return _downsample(rows_big, w, h)


# --------------------------------------------------------------- 三种图形

def _app_icon_shade(x, y):
    if not in_rounded_rect(x, y, 0.0, 0.0, 1.0, 1.0, 0.22):
        return CLEAR
    color = BG
    if in_rounded_rect(x, y, 0.18, 0.24, 0.82, 0.66, 0.06):
        color = SCREEN
        if in_play_triangle(x, y, 0.52, 0.45, 0.16, 0.20):
            color = PLAY
    return (color[0], color[1], color[2], 255)


def _notify_shade(x, y):
    """纯白剪影：屏幕描边 + 实心播放三角。没有任何颜色信息。"""
    # 屏幕外框（描边）
    if in_rounded_rect_ring(x, y, 0.08, 0.16, 0.92, 0.76, 0.10, 0.075):
        return (WHITE[0], WHITE[1], WHITE[2], 255)
    # 底座小横条，让轮廓更像一台电视
    if in_rounded_rect(x, y, 0.36, 0.80, 0.64, 0.86, 0.03):
        return (WHITE[0], WHITE[1], WHITE[2], 255)
    # 播放三角
    if in_play_triangle(x, y, 0.50, 0.46, 0.22, 0.28):
        return (WHITE[0], WHITE[1], WHITE[2], 255)
    return CLEAR


def _banner_shade(x, y):
    """320×180 的 TV 横幅：深色底 + 左侧绿条 + 屏幕 + 投屏波纹

    这个函数刻意在「真实像素坐标」里做几何计算，而不是归一化坐标。
    原因：320×180 是 16:9，归一化后 x 和 y 的单位长度差了一倍多，
    直接套圆形半径会画成椭圆，圆角也会一边大一边小。
    """
    W, H = 320.0, 180.0
    u, v = x * W, y * H

    # 底色：对角渐变，避免死板的纯色
    t = (u / W) * 0.55 + (v / H) * 0.45
    base = 0x0E + int(t * 0x0C)
    bg = (base, base + 4, base + 9, 255)

    # 左侧强调竖条
    if u < 6.0:
        return (PLAY[0], PLAY[1], PLAY[2], 255)

    # --- 屏幕本体（138×84，约 1.64:1）---
    sx0, sy0, sx1, sy1 = 56.0, 44.0, 194.0, 128.0
    if in_rounded_rect(u, v, sx0, sy0, sx1, sy1, 10.0):
        cx = (sx0 + sx1) / 2.0
        cy = (sy0 + sy1) / 2.0
        if in_play_triangle(u, v, cx + 3.0, cy, 30.0, 36.0):
            return (PLAY[0], PLAY[1], PLAY[2], 255)
        return (SCREEN[0], SCREEN[1], SCREEN[2], 255)

    # --- 投屏波纹：以屏幕右下角为圆心，向右上扩散的三条弧 ---
    # 这就是「正在把画面推出去」的视觉语言，同时也是为了填掉右侧的空白。
    wx, wy = sx1 - 8.0, sy1 - 8.0
    d = math.hypot(u - wx, v - wy)
    ang = math.degrees(math.atan2(wy - v, u - wx))  # 向上为正
    if -32.0 < ang < 82.0:
        for rr in (50.0, 68.0, 86.0):
            if abs(d - rr) < 3.0:
                return (PLAY[0], PLAY[1], PLAY[2], 255)

    return bg


def _music_shade(x, y):
    """音符：实心符头 + 符干 + 符尾，透明底。

    为什么要画这个：纯音频投屏走的是同一个 SurfaceView，上面什么都没有 ——
    电视就是**一片黑**，只留一条状态栏。声音明明在放，看着却像投屏失败。
    音乐是主要用途之一，这个误判代价很高。

    用几何函数画而不是塞位图，是为了跟其它资源一样由脚本生成：
    想调形状改几个数字就行，不必去开图形编辑器。
    """
    ink = (PLAY[0], PLAY[1], PLAY[2], 255)

    # 符头：椭圆
    dx = (x - 0.335) / 0.225
    dy = (y - 0.735) / 0.175
    if dx * dx + dy * dy <= 1.0:
        return ink

    # 符干
    if 0.505 <= x <= 0.605 and 0.10 <= y <= 0.76:
        return ink

    # 符尾：从符干顶端甩向右下、逐渐收细的一条带子
    if 0.585 <= x <= 0.935:
        u = (x - 0.585) / 0.35
        y_outer = 0.10 + 0.40 * (u ** 1.8)
        y_inner = y_outer - (0.20 * (1.0 - u) + 0.02)
        if y_inner <= y <= y_outer:
            return ink

    return CLEAR


# --------------------------------------------------------------- 输出清单

LAUNCHER_DENSITIES = [('mdpi', 48), ('hdpi', 72), ('xhdpi', 96), ('xxhdpi', 144)]
# 通知图标基准 24dp
NOTIFY_DENSITIES = [('mdpi', 24), ('hdpi', 36), ('xhdpi', 48), ('xxhdpi', 72)]
# 音符基准 96dp —— 界面里就是按 96dp 摆的
MUSIC_DENSITIES = [('mdpi', 96), ('hdpi', 144), ('xhdpi', 192), ('xxhdpi', 288)]


def _write(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'wb') as f:
        f.write(data)


def main():
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    res = os.path.join(root, 'app', 'src', 'main', 'res')

    print("=== 应用图标 ===")
    for density, size in LAUNCHER_DENSITIES:
        path = os.path.join(res, 'drawable-' + density, 'ic_launcher.png')
        _write(path, _encode_png(_rasterize(size, size, _app_icon_shade), size, size))
        print("  %-8s %3dpx  %s" % (density, size, path))

    print("=== 通知图标（纯白剪影）===")
    for density, size in NOTIFY_DENSITIES:
        path = os.path.join(res, 'drawable-' + density, 'ic_notify.png')
        _write(path, _encode_png(_rasterize(size, size, _notify_shade), size, size))
        print("  %-8s %3dpx  %s" % (density, size, path))

    print("=== TV 横幅 ===")
    bw, bh = 320, 180
    path = os.path.join(res, 'drawable-xhdpi', 'ic_banner.png')
    _write(path, _encode_png(_rasterize(bw, bh, _banner_shade), bw, bh))
    print("  xhdpi    %dx%d  %s" % (bw, bh, path))

    print("=== 音乐投屏音符 ===")
    for density, size in MUSIC_DENSITIES:
        path = os.path.join(res, 'drawable-' + density, 'ic_music.png')
        _write(path, _encode_png(_rasterize(size, size, _music_shade), size, size))
        print("  %-8s %3dpx  %s" % (density, size, path))

    print("全部位图资源生成完成")


if __name__ == '__main__':
    main()
