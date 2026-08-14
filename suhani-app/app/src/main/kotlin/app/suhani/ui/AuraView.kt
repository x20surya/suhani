package app.suhani.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.view.animation.DecelerateInterpolator
import android.view.Choreographer
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * AGSL port of the webapp's aura orb (webapp/src/components/aura/aura.tsx,
 * vendored from livekit-examples/agent-starter-react; carries an Unicorn
 * Studio "PolyForm Non-Resale" header - used, as there, on the private
 * family-app basis; revisit before any commercial shipping).
 *
 * The GLSL translates to AGSL nearly 1:1 (float2x2 for mat2, half4 main()).
 * uBloom stays 0.0 for the same reason as upstream: alpha derives from luma,
 * bloom would lift the whole quad. State choreography ports use-aura.ts.
 */
class AuraView(context: Context) : View(context) {

    // ---- shader (GLSL -> AGSL) ----
    private val shader = RuntimeShader(
        """
        uniform float2 iResolution;
        uniform float iTime;
        uniform float uSpeed;
        uniform float uBlur;
        uniform float uScale;
        uniform float uFrequency;
        uniform float uAmplitude;
        uniform float uMix;
        uniform float uSpacing;
        uniform float uColorShift;
        uniform float uVariance;
        uniform float uSmoothing;
        uniform float uMode;
        uniform half3 uColor;

        const float TAU = 6.283185;

        float2 randFibo(float2 p) {
          p = fract(p * float2(443.897, 441.423));
          p += dot(p, p.yx + 19.19);
          return fract((p.xx + p.yx) * p.xy);
        }

        half3 Tonemap(half3 x) {
          x *= 4.0;
          return x / (1.0 + x);
        }

        float luma(half3 color) {
          return dot(color, half3(0.299, 0.587, 0.114));
        }

        half3 rgb2hsv(half3 c) {
          half4 K = half4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);
          half4 p = mix(half4(c.bg, K.wz), half4(c.gb, K.xy), step(c.b, c.g));
          half4 q = mix(half4(p.xyw, c.r), half4(c.r, p.yzx), step(p.x, c.r));
          float d = q.x - min(q.w, q.y);
          float e = 1.0e-10;
          return half3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);
        }

        half3 hsv2rgb(half3 c) {
          half4 K = half4(1.0, 2.0 / 3.0, 1.0 / 3.0, 3.0);
          half3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);
          return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
        }

        float getSdf(float2 st) {
          return length(st) - uScale;
        }

        float2 turb(float2 pos, float t, float it) {
          float2x2 rotation = float2x2(0.6, -0.25, 0.25, 0.9);
          float2x2 layerRotation = float2x2(0.6, -0.8, 0.8, 0.6);

          float frequency = mix(2.0, 15.0, uFrequency);
          float amplitude = uAmplitude;
          float frequencyGrowth = 1.4;
          float animTime = t * 0.1 * uSpeed;

          for (int i = 0; i < 4; i++) {
            float2 rotatedPos = pos * rotation;
            float2 wave = sin(frequency * rotatedPos + float(i) * animTime + it);
            pos += (amplitude / frequency) * float2(rotation[0][0], rotation[0][1]) * wave;
            rotation = rotation * layerRotation;
            amplitude *= mix(1.0, max(wave.x, wave.y), uVariance);
            frequency *= frequencyGrowth;
          }
          return pos;
        }

        const float ITERATIONS = 36.0;

        half4 main(float2 fragCoord) {
          float2 uv = fragCoord / iResolution.xy;
          half3 pp = half3(0.0);
          float t = iTime * 0.5;
          float2 pos = uv - 0.5;

          float2 prevPos = turb(pos, t, 0.0 - 1.0 / ITERATIONS);
          float spacing = mix(1.0, TAU, uSpacing);

          for (float i = 1.0; i < ITERATIONS + 1.0; i++) {
            float iter = i / ITERATIONS;
            float2 st = turb(pos, t, iter * spacing);
            float d = abs(getSdf(st));
            float pd = distance(st, prevPos);
            prevPos = st;
            float dynamicBlur = exp2(pd * 2.0 * 1.4426950408889634) - 1.0;
            float ds = smoothstep(0.0, uBlur * 0.05 + max(dynamicBlur * uSmoothing, 0.001), d);

            half3 color = uColor;
            if (uColorShift > 0.01) {
              half3 hsv = rgb2hsv(color);
              hsv.x = fract(hsv.x + (1.0 - iter) * uColorShift * 0.3);
              color = hsv2rgb(hsv);
            }
            pp += (ds - 1.0) * color;
          }

          pp *= 1.0 / ITERATIONS;

          half3 color;
          if (uMode < 0.5) {
            color = (-pp) * 1.2;
            color += half3((randFibo(fragCoord).x - 0.5) / 255.0);
            color = Tonemap(color);
            float alpha = luma(color) * uMix;
            // UNpremultiplied out - Skia premultiplies after us; doing it here
            // too crushed the brights into gray fog (the "blurry" complaint).
            return half4(color * uMix, alpha);
          } else {
            color = -pp;
            color += half3((randFibo(fragCoord).x - 0.5) / 255.0);
            float brightness = length(color);
            half3 direction = brightness > 0.0 ? color / brightness : color;
            float factor = 2.0;
            float mappedBrightness = (brightness * factor) / (1.0 + brightness * factor);
            color = direction * mappedBrightness;
            float gray = dot(color, half3(0.2, 0.5, 0.1));
            color = mix(half3(gray), color, 3.0);
            color = clamp(color, 0.0, 1.0);
            float alpha = mappedBrightness * clamp(uMix, 1.0, 2.0);
            return half4(color, alpha);
          }
        }
        """.trimIndent(),
    )

    private val paint = Paint()

    // ---- animated uniform state (ports use-aura.ts) ----
    private var speed = 10f
    private var scale = 0.2f
    private var amplitude = 2f
    private var frequency = 0.5f
    private var brightness = 1.5f
    private var animators = mutableListOf<ValueAnimator>()
    private var startNanos = System.nanoTime()

    var darkMode: Boolean = true

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            invalidate()
            if (isAttachedToWindow) Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    override fun onDetachedFromWindow() {
        animators.forEach { it.cancel() }
        super.onDetachedFromWindow()
    }

    private fun animateTo(
        set: (Float) -> Unit,
        from: Float,
        to: Float,
        durationMs: Long = 500,
        pulse: Boolean = false,
    ): ValueAnimator = ValueAnimator.ofFloat(from, to).apply {
        duration = durationMs
        interpolator = if (pulse) LinearInterpolator() else DecelerateInterpolator()
        if (pulse) {
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
        }
        addUpdateListener { set(it.animatedValue as Float) }
        start()
    }

    /** State choreography - the exact targets from use-aura.ts. */
    fun setAgentState(state: String) {
        animators.forEach { it.cancel() }
        animators.clear()
        when (state) {
            "listening" -> {
                speed = 20f
                animators += animateTo({ scale = it }, scale, 0.3f, 1000)
                animators += animateTo({ amplitude = it }, amplitude, 1.0f)
                animators += animateTo({ frequency = it }, frequency, 0.7f)
                animators += animateTo({ brightness = it }, 1.5f, 2.0f, 350, pulse = true)
            }
            "thinking", "connecting" -> {
                speed = 30f
                animators += animateTo({ scale = it }, scale, 0.3f)
                animators += animateTo({ amplitude = it }, amplitude, 0.5f)
                animators += animateTo({ frequency = it }, frequency, 1.0f)
                animators += animateTo({ brightness = it }, 0.5f, 2.5f, 350, pulse = true)
            }
            "speaking" -> {
                speed = 70f
                animators += animateTo({ scale = it }, scale, 0.3f)
                animators += animateTo({ amplitude = it }, amplitude, 0.75f)
                animators += animateTo({ frequency = it }, frequency, 1.25f)
                animators += animateTo({ brightness = it }, brightness, 1.5f)
            }
            "muted" -> { // dimmed, nearly still - visibly "not hearing you"
                speed = 5f
                animators += animateTo({ scale = it }, scale, 0.18f)
                animators += animateTo({ amplitude = it }, amplitude, 0.9f)
                animators += animateTo({ frequency = it }, frequency, 0.3f)
                animators += animateTo({ brightness = it }, brightness, 0.4f)
            }
            else -> { // idle / failed
                speed = 10f
                animators += animateTo({ scale = it }, scale, 0.2f)
                animators += animateTo({ amplitude = it }, amplitude, 1.2f)
                animators += animateTo({ frequency = it }, frequency, 0.4f)
                animators += animateTo({ brightness = it }, brightness, 1.0f)
            }
        }
    }

    private var levelSmoothed = 0f
    private var levelTarget = 0f

    /** Live volume -> shape (webapp formula 0.2 + 0.2*v), but SMOOTHED - the
     * webapp got smoothing free from its audio analyser; raw 10Hz RMS made the
     * orb twitch. Exponential target + per-frame easing in onDraw. */
    fun setLevel(volume: Float) {
        levelTarget = volume.coerceIn(0f, 1f)
    }

    private fun easeLevelScale() {
        levelSmoothed += (levelTarget - levelSmoothed) * 0.25f
        if (animators.none { it.isRunning && it.repeatCount == 0 }) {
            val target = 0.2f + 0.2f * levelSmoothed
            scale += (target - scale) * 0.3f
        }
    }

    override fun onDraw(canvas: Canvas) {
        easeLevelScale()
        val time = (System.nanoTime() - startNanos) / 1e9f
        shader.setFloatUniform("iResolution", width.toFloat(), height.toFloat())
        shader.setFloatUniform("iTime", time)
        shader.setFloatUniform("uSpeed", speed)
        shader.setFloatUniform("uBlur", 0.15f)
        shader.setFloatUniform("uScale", scale)
        shader.setFloatUniform("uFrequency", frequency)
        shader.setFloatUniform("uAmplitude", amplitude)
        shader.setFloatUniform("uMix", brightness)
        shader.setFloatUniform("uSpacing", 0.5f)
        shader.setFloatUniform("uColorShift", 0.05f)
        shader.setFloatUniform("uVariance", 0.1f)
        // Below the webapp's 1.0: less motion-blur on the filaments, so the
        // swirl reads as luminous threads instead of fog at phone size.
        shader.setFloatUniform("uSmoothing", 0.6f)
        shader.setFloatUniform("uMode", if (darkMode) 0f else 1f)
        // #1FD5F9 - Suhani's signature cyan.
        shader.setFloatUniform("uColor", 0x1F / 255f, 0xD5 / 255f, 0xF9 / 255f)
        paint.shader = shader
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }
}
