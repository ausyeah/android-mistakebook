package com.mistakebook.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import java.io.File

/**
 * 图片归一化：把「来源各异的原始照片」统一成「方向正确、适合 OCR 的平铺 JPEG」。
 *
 * ## 为什么必须有这一步（两起真实事故）
 *
 * **1. 莫名旋转。** 原先 [ImageImporter] 对 JPEG/PNG 直接裸字节拷贝，
 * EXIF 方向标记原封不动留在文件里。而 `BitmapFactory` 不认 EXIF——
 * 于是同一个文件：有的路径（比如 WebView、某些图片库）会按 EXIF 转正，
 * 有的路径（裁剪页、缩略图）不会，用户看到的就是「我明明调正了，它又歪了」。
 * 根治办法只有一个：**把方向烘进像素，EXIF 不再携带方向**。
 *
 * **2. 照片发灰、铅笔字看不清。** 手机在室内光下拍的作业照对比度低，
 * 直接丢给 MinerU 会大量误识别。这里做灰度化 + 自适应对比度拉伸。
 *
 * ## 为什么保留彩色像素
 * 老师用红笔批注是错题本里信息量最大的部分。全转黑白会把红笔批注一起抹掉。
 * 所以策略是：**近灰像素**（纸面、铅笔、黑字）走强对比拉伸；
 * **彩色像素**（红笔、蓝笔）保留色相，只做温和的亮度对比增强。
 */
object ImageNormalizer {

    /** 归一化后的长边上限。再大对 OCR 没有增益，只会拖慢处理和占内存。 */
    private const val MAX_LONG_EDGE = 2400

    /** JPEG 质量。90 以上对文字没有可见收益，体积却线性上涨。 */
    private const val JPEG_QUALITY = 92

    /** 饱和度低于此值视为「近灰」，走强对比通道。 */
    private const val GRAY_SATURATION = 0.18f

    /** 超过此饱和度视为「彩色笔迹」，走保色通道。 */
    private const val COLOR_SATURATION = 0.30f

    /**
     * 把 [source] 归一化后写入 [target]，返回是否成功。
     *
     * @param enhance 是否做 OCR 对比度增强（设置页可关）。
     */
    fun normalize(source: File, target: File, enhance: Boolean): Boolean {
        if (!source.exists()) return false
        return runCatching {
            val orientation = readOrientation(source)
            val decoded = decodeBounded(source) ?: return false
            // 先把方向烘进像素，后面所有环节（裁剪、缩略图、打印、识别）都不再需要知道 EXIF
            val upright = applyOrientation(decoded, orientation)
            val final = if (enhance) enhanceForOcr(upright) else upright
            target.parentFile?.mkdirs()
            target.outputStream().use { out ->
                final.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
            if (final !== upright) final.recycle()
            if (upright !== decoded) upright.recycle()
            decoded.recycle()
            target.length() > 0
        }.getOrDefault(false)
    }

    /**
     * 读取 EXIF 方向，返回 [ExifInterface.ORIENTATION_*]。
     * 读不到（PNG / 无 EXIF / 损坏）返回 NORMAL。
     */
    fun readOrientation(file: File): Int = runCatching {
        file.inputStream().use { input ->
            ExifInterface(input).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        }
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    /**
     * 边长受限解码。
     * 直接 `decodeFile` 一张 4000×3000 的照片就是 48MB，
     * 连续处理多张必被 OOM——必须先算 inSampleSize。
     */
    private fun decodeBounded(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_LONG_EDGE * 2) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null

        val longEdge = maxOf(bitmap.width, bitmap.height)
        if (longEdge <= MAX_LONG_EDGE) return bitmap
        val ratio = MAX_LONG_EDGE.toFloat() / longEdge
        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1),
            true
        )
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    /** 按 EXIF 方向把位图转正，返回新位图（不需要旋转时原样返回）。 */
    fun applyOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f); matrix.postScale(-1f, 1f)
            }

            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f); matrix.postScale(-1f, 1f)
            }

            else -> return bitmap
        }
        return runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }.getOrDefault(bitmap)
    }

    /**
     * OCR 友好的对比度增强。
     *
     * 分两路：
     * - 近灰像素：走「自适应阈值 + 陡坡」曲线，纸面压到接近纯白、字迹压到接近纯黑；
     * - 彩色像素：保留色相，只按同一套曲线提亮/压暗，避免红笔批注被抹成灰。
     *
     * 阈值不是固定的 128，而是按整图直方图取 Otsu 值——
     * 偏灰的照片和偏亮的照片需要不同的分界，固定阈值会把浅铅笔字吃掉。
     */
    fun enhanceForOcr(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return bitmap
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val threshold = otsuThreshold(pixels)

        for (i in pixels.indices) {
            val pixel = pixels[i]
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            val maxC = maxOf(r, g, b)
            val minC = minOf(r, g, b)
            val saturation = if (maxC == 0) 0f else (maxC - minC).toFloat() / maxC

            val target = steepen(luma(r, g, b), threshold)
            pixels[i] = if (saturation >= COLOR_SATURATION) {
                // 彩色笔迹：按比例缩放 RGB，保住色相
                val scale = if (maxC == 0) 0f else target / maxC.toFloat()
                val nr = (r * scale).toInt().coerceIn(0, 255)
                val ng = (g * scale).toInt().coerceIn(0, 255)
                val nb = (b * scale).toInt().coerceIn(0, 255)
                (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
            } else {
                // 近灰像素（含铅笔字、纸面）：直接映射到增强后的灰阶
                val v = if (saturation >= GRAY_SATURATION) {
                    // 轻微带色但不算彩色：折中处理，仍保一点色相
                    val scale = if (maxC == 0) 0f else target / maxC.toFloat()
                    ((r * scale).toInt().coerceIn(0, 255) shl 16) or
                        ((g * scale).toInt().coerceIn(0, 255) shl 8) or
                        (b * scale).toInt().coerceIn(0, 255)
                } else {
                    (0xFF shl 24) or (target shl 16) or (target shl 8) or target
                }
                v
            }
        }

        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, width, 0, 0, width, height)
        return out
    }

    private fun luma(r: Int, g: Int, b: Int): Int =
        (0.299f * r + 0.587f * g + 0.114f * b).toInt().coerceIn(0, 255)

    /**
     * 以 [threshold] 为界做陡坡映射：
     * 明显比阈值暗的压向 0，明显比阈值亮的推向 255，阈值附近快速过渡。
     * 斜坡宽度取阈值的 12%，太宽没效果，太窄会把抗锯齿的边缘切碎。
     */
    private fun steepen(value: Int, threshold: Int): Int {
        val spread = (threshold * 0.12f).toInt().coerceAtLeast(12)
        val scaled = ((value - threshold) * 255f / spread + 128f).toInt()
        return scaled.coerceIn(0, 255)
    }

    /** Otsu 法求灰度直方图的最大类间方差阈值。 */
    private fun otsuThreshold(pixels: IntArray): Int {
        val histogram = IntArray(256)
        pixels.forEach { pixel ->
            val alpha = (pixel ushr 24) and 0xFF
            if (alpha < 16) return@forEach
            histogram[luma((pixel shr 16) and 0xFF, (pixel shr 8) and 0xFF, pixel and 0xFF)]++
        }
        val total = histogram.sum()
        if (total == 0) return 128

        var sum = 0L
        histogram.forEachIndexed { value, count -> sum += value.toLong() * count }

        var sumBackground = 0L
        var weightBackground = 0
        var best = 0
        var bestVariance = -1.0
        for (threshold in 0..255) {
            weightBackground += histogram[threshold]
            if (weightBackground == 0) continue
            val weightForeground = total - weightBackground
            if (weightForeground == 0) break

            sumBackground += threshold.toLong() * histogram[threshold]
            val meanBackground = sumBackground.toDouble() / weightBackground
            val meanForeground = (sum - sumBackground).toDouble() / weightForeground
            val variance = weightBackground.toDouble() * weightForeground *
                (meanBackground - meanForeground) * (meanBackground - meanForeground)
            if (variance > bestVariance) {
                bestVariance = variance
                best = threshold
            }
        }
        return best
    }

    /** 纯色覆盖绘制，供调用方复用（避免各处重复 new Canvas）。 */
    fun fill(bitmap: Bitmap, color: Int) {
        Canvas(bitmap).drawPaint(Paint().apply { this.color = color })
    }

    /** 灰度化（调试/对比用）。 */
    fun toGrayscale(bitmap: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val matrix = ColorMatrix().apply { setSaturation(0f) }
        canvas.drawBitmap(
            bitmap, 0f, 0f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                colorFilter = ColorMatrixColorFilter(matrix)
            }
        )
        return out
    }
}
