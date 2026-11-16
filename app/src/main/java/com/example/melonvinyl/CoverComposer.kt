package com.example.melonvinyl

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.Build

/**
 * 안드로이드 오토는 앱 화면을 직접 그릴 수 없고 '앨범 이미지 한 장'만 표시할 수 있습니다.
 * 그래서 앨범 자켓 + 뒤에서 나온 레코드판 + 앨범 색 배경을 한 장의 정사각형 이미지로 합성해서 넘깁니다.
 *
 * 오토용 개선점
 *  - 안전 영역: 차량마다 이미지를 둥글게/잘라서 표시하므로, 구성물 전체를 가운데로 모으고 사방 여백 확보
 *  - 선명도: 원본을 먼저 목표 크기로 축소(필터 적용)한 뒤 그려서 계단/모아레 감소
 *  - 부드러운 가장자리: clipPath 대신 BitmapShader로 안티앨리어싱된 원형 디스크
 *  - 가독성: 채도 가중 평균색 + 명도 제한으로 어둡고 선명한 배경, 비네팅으로 중앙 강조
 *  - 하드웨어 비트맵(getPixel 불가) 안전 처리
 */
object CoverComposer {

    enum class DiscStyle {
        /** 앨범 이미지로 꽉 채운 픽처 디스크 (웹 UI와 동일) */
        PICTURE,
        /** 검은 비닐 + 가운데 라벨에 앨범 이미지. 자켓과 구분이 더 잘 됨 */
        BLACK_VINYL
    }

    /**
     * @param size 결과 한 변(px). 안드로이드가 메타데이터 비트맵을 자체 상한으로 줄일 수 있어
     *             768 정도면 충분합니다.
     */
    fun compose(src: Bitmap, size: Int = 768, style: DiscStyle = DiscStyle.PICTURE): Bitmap {
        val s = size.toFloat()
        val px = s / 512f // 선 굵기 등을 해상도에 비례시키는 단위
        val aa = Paint.ANTI_ALIAS_FLAG
        val picture = style == DiscStyle.PICTURE

        val base = toSoftware(src)
        val pal = palette(base)

        // 레이아웃: 웹 UI와 같은 비율(자켓 1 : 디스크 지름 0.95, 전체 가로 1.475)을 가운데 정렬
        val slPx = (0.58f * s).toInt()
        val sl = slPx.toFloat()
        val r = sl * 0.475f
        val slLeft = (s - (sl + r)) / 2f
        val slTop = (s - sl) / 2f
        val cx = slLeft + sl
        val cy = s / 2f

        // 정사각형으로 자르고 목표 크기로 한 번만 축소
        val side = minOf(base.width, base.height)
        val square = Bitmap.createBitmap(base, (base.width - side) / 2, (base.height - side) / 2, side, side)
        val art = Bitmap.createScaledBitmap(square, slPx, slPx, true)
        if (square !== base && square !== art) square.recycle()

        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)

        drawBackground(c, s, pal)

        // ---- 레코드 ----
        c.drawCircle(cx, cy, r, Paint(aa).apply {
            color = Color.BLACK
            setShadowLayer(0.05f * s, 0f, 0.025f * s, black(115))
        })

        val discRect = RectF(cx - r, cy - r, cx + r, cy + r)
        if (picture) {
            c.drawCircle(cx, cy, r, Paint(aa or Paint.FILTER_BITMAP_FLAG).apply {
                shader = rectShader(art, art.width.toFloat(), art.height.toFloat(), discRect)
            })
        } else {
            c.drawCircle(cx, cy, r, Paint(aa).apply { color = Color.rgb(16, 16, 16) })
        }

        // 홈(그루브)
        val groove = Paint(aa).apply {
            this.style = Paint.Style.STROKE
            strokeWidth = px
            color = if (picture) black(46) else white(22)
        }
        var rr = r * 0.36f
        while (rr < r * 0.97f) { c.drawCircle(cx, cy, rr, groove); rr += 3.5f * px }
        // 트랙 구분선
        val gap = Paint(aa).apply { this.style = Paint.Style.STROKE; strokeWidth = 2f * px; color = black(90) }
        for (f in floatArrayOf(0.55f, 0.78f)) c.drawCircle(cx, cy, r * f, gap)

        // 빛 반사(스윕) + 가장자리 어둡게
        c.drawCircle(cx, cy, r, Paint().apply {
            shader = SweepGradient(
                cx, cy,
                intArrayOf(0, white(56), 0, 0, white(46), 0, 0),
                floatArrayOf(0f, 0.10f, 0.20f, 0.50f, 0.62f, 0.72f, 1f)
            )
        })
        c.drawCircle(cx, cy, r, Paint().apply {
            shader = RadialGradient(
                cx, cy, r, intArrayOf(0, 0, black(90)),
                floatArrayOf(0f, 0.88f, 1f), Shader.TileMode.CLAMP
            )
        })
        c.drawCircle(cx, cy, r - px, Paint(aa).apply {
            this.style = Paint.Style.STROKE; strokeWidth = 2f * px; color = black(150)
        })

        // 라벨 + 스핀들 구멍
        val lr = r * 0.30f
        val labelPaint = Paint(aa or Paint.FILTER_BITMAP_FLAG)
        if (style == DiscStyle.BLACK_VINYL) {
            val q = art.width * 0.25f
            labelPaint.shader = rectShader(
                art, art.width.toFloat(), art.height.toFloat(),
                RectF(cx - lr, cy - lr, cx + lr, cy + lr),
                src = RectF(q, q, art.width - q, art.height - q)
            )
        } else {
            labelPaint.color = tone(pal.accent, 0.45f, 0.85f, 1.15f)
        }
        c.drawCircle(cx, cy, lr, labelPaint)
        c.drawCircle(cx, cy, lr, Paint(aa).apply {
            this.style = Paint.Style.STROKE; strokeWidth = 2f * px; color = black(120)
        })
        c.drawCircle(cx, cy, r * 0.045f, Paint(aa).apply { color = pal.bottom })

        // ---- 앨범 자켓 (레코드 위에) ----
        val slRect = RectF(slLeft, slTop, slLeft + sl, slTop + sl)
        c.drawRect(slRect, Paint(aa).apply {
            color = Color.BLACK
            setShadowLayer(0.035f * s, 0f, 0.015f * s, black(110))
        })
        c.drawBitmap(art, null, slRect, Paint(Paint.FILTER_BITMAP_FLAG))
        // 광택 (웹 UI .sleeve::after와 동일)
        c.drawRect(slRect, Paint().apply {
            shader = LinearGradient(
                slRect.left, slRect.top, slRect.right, slRect.bottom,
                intArrayOf(white(41), 0, black(31)),
                floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP
            )
        })
        // 왼쪽 책등 음영 + 얇은 테두리
        c.drawRect(slRect, Paint().apply {
            shader = LinearGradient(
                slRect.left, 0f, slRect.left + sl * 0.08f, 0f,
                black(60), black(0), Shader.TileMode.CLAMP
            )
        })
        c.drawRect(slRect, Paint(aa).apply {
            this.style = Paint.Style.STROKE; strokeWidth = px; color = white(36)
        })

        if (art !== base) art.recycle()
        if (base !== src) base.recycle()
        return out
    }

    // ---------- 내부 헬퍼 ----------

    private class Palette(val top: Int, val bottom: Int, val accent: Int)

    private fun drawBackground(c: Canvas, s: Float, pal: Palette) {
        c.drawRect(0f, 0f, s, s, Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, s, pal.top, pal.bottom, Shader.TileMode.CLAMP)
        })
        // 중앙 은은한 빛
        c.drawRect(0f, 0f, s, s, Paint().apply {
            shader = RadialGradient(s / 2, s / 2, s * 0.55f, white(40), white(0), Shader.TileMode.CLAMP)
        })
        // 비네팅
        c.drawRect(0f, 0f, s, s, Paint().apply {
            shader = RadialGradient(
                s / 2, s / 2, s * 0.78f, intArrayOf(black(0), black(0), black(110)),
                floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP
            )
        })
    }

    /** 비트맵 전체(또는 src 영역)를 dest 사각형에 맞춰 그리는 셰이더 */
    private fun rectShader(bmp: Bitmap, w: Float, h: Float, dest: RectF, src: RectF? = null): BitmapShader {
        val m = Matrix().apply { setRectToRect(src ?: RectF(0f, 0f, w, h), dest, Matrix.ScaleToFit.FILL) }
        return BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(m) }
    }

    private fun toSoftware(b: Bitmap): Bitmap =
        if (Build.VERSION.SDK_INT >= 26 && b.config == Bitmap.Config.HARDWARE)
            b.copy(Bitmap.Config.ARGB_8888, false) ?: b
        else b

    /** 위쪽/아래쪽 배경색(어둡게)과 라벨용 강조색을 채도 가중 평균으로 계산 */
    private fun palette(src: Bitmap): Palette {
        val n = 24
        val b = Bitmap.createScaledBitmap(src, n, n, true)
        val top = weightedAvg(b, 0, 8)
        val bottom = weightedAvg(b, 16, n)
        val all = weightedAvg(b, 0, n)
        if (b !== src) b.recycle()
        return Palette(
            top = tone(top, 0.26f, 0.42f, 1.1f),
            bottom = tone(bottom, 0.12f, 0.26f, 1.1f),
            accent = all
        )
    }

    private fun weightedAvg(b: Bitmap, y0: Int, y1: Int): Int {
        val hsv = FloatArray(3)
        var r = 0f; var g = 0f; var bl = 0f; var w = 0f
        for (y in y0 until y1) for (x in 0 until b.width) {
            val p = b.getPixel(x, y)
            Color.colorToHSV(p, hsv)
            val wt = 0.15f + hsv[1] * hsv[2] // 선명한 색에 가중치
            r += Color.red(p) * wt; g += Color.green(p) * wt; bl += Color.blue(p) * wt; w += wt
        }
        return Color.rgb((r / w).toInt(), (g / w).toInt(), (bl / w).toInt())
    }

    private fun tone(color: Int, vMin: Float, vMax: Float, sBoost: Float): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[1] = (hsv[1] * sBoost).coerceIn(0f, 1f)
        hsv[2] = hsv[2].coerceIn(vMin, vMax)
        return Color.HSVToColor(hsv)
    }

    private fun black(a: Int) = Color.argb(a, 0, 0, 0)
    private fun white(a: Int) = Color.argb(a, 255, 255, 255)
}
