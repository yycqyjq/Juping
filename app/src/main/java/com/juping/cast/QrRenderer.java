package com.juping.cast;

import android.graphics.Bitmap;

import io.nayuki.qrcodegen.QrCode;

/**
 * 把一段文本画成二维码位图（电视端唯一一处用到 QR 的地方）。
 *
 * <p><b>为什么不直接用上游的 {@code QrCode.toImage()}</b>：它依赖
 * {@code java.awt} / {@code javax.imageio} —— 那是 Java SE 专有的包，
 * Android 根本没有，连编译都过不去。所以这里只取上游算好的模块矩阵
 * （{@code getModule(x, y)}），自己逐格画进 {@link Bitmap}。
 *
 * <p><b>性能铁律</b>：{@link MainActivity#refresh()} 每 1.5 秒跑一次
 * （播放中 0.5 秒）。这个方法一次要跑完整套 QR 编码 + 填几万个像素，
 * 所以调用方**必须按地址缓存结果**（见 {@code MainActivity#updateQr}）——
 * 每个 tick 重画一次的话，0.6GB 的盒子上就是白烧 CPU 和内存。
 */
final class QrRenderer {

    /**
     * 静区（quiet zone）宽度，单位是模块数。
     *
     * <p>规范要求四周至少留 4 个模块的空白：解码器靠这圈空白把码和周围内容
     * 分开。裁掉静区，识别率会明显下降 —— 而电视上这块码紧挨着深色卡片，
     * 正是最容易把静区吃掉、把码扫不出来的场景。
     */
    private static final int QUIET_ZONE = 4;

    private QrRenderer() {
    }

    /**
     * 画一张边长约 {@code sizePx} 的二维码。
     *
     * <p>实际边长会按「一个模块占整数个像素」向下取整 —— 缩放系数必须是整数，
     * 否则模块之间会出现半个像素的模糊，那正是扫码读不出来的常见原因。
     * 取整后可能比 {@code sizePx} 略小，用 ImageView 的 fitCenter 居中即可。
     *
     * @return 位图；编码失败（正常不会）返回 {@code null}，调用方据此隐藏整块
     */
    static Bitmap render(String text, int sizePx) {
        QrCode qr;
        try {
            // MEDIUM 纠错而不是 LOW：这个地址很短（版本低、格子大），多出来的
            // 纠错能力换的是「三米外、屏幕有反光时也能扫出来」，很划算。
            qr = QrCode.encodeText(text, QrCode.Ecc.MEDIUM);
        } catch (RuntimeException e) {
            return null;
        }

        // 上游把边长暴露成字段 size（不是 getSize() 方法）—— 全系版本皆然。
        int modules = qr.size;
        int total = modules + QUIET_ZONE * 2;
        int scale = sizePx / total;
        if (scale < 1) {
            scale = 1;
        }
        int dim = total * scale;

        // 一次 setPixels 填整块，而不是逐点 setPixel：几万次方法调用换成一次，
        // 在这台 0.6GB 的盒子上差别很实在。
        int[] pixels = new int[dim * dim];
        for (int y = 0; y < dim; y++) {
            int my = y / scale - QUIET_ZONE;
            boolean rowInside = my >= 0 && my < modules;
            int row = y * dim;
            for (int x = 0; x < dim; x++) {
                int mx = x / scale - QUIET_ZONE;
                boolean dark = rowInside && mx >= 0 && mx < modules && qr.getModule(mx, my);
                pixels[row + x] = dark ? 0xFF000000 : 0xFFFFFFFF;
            }
        }

        Bitmap bmp = Bitmap.createBitmap(dim, dim, Bitmap.Config.ARGB_8888);
        bmp.setPixels(pixels, 0, dim, 0, 0, dim, dim);
        return bmp;
    }
}