package com.theacar.sensing

class AudioFrame private constructor(
    private val pcm: FloatArray,
    val sampleRateHz: Int,
    val channels: Int,
) {
    val sampleCount: Int get() = pcm.size
    fun sampleAt(index: Int): Float = pcm[index]

    companion object {
        fun fromPcm(pcm: FloatArray, sampleRateHz: Int, channels: Int = 1): AudioFrame {
            require(sampleRateHz > 0 && channels > 0)
            require(pcm.isNotEmpty() && pcm.size % channels == 0)
            require(pcm.all { it.isFinite() && it in -1f..1f })
            return AudioFrame(pcm.copyOf(), sampleRateHz, channels)
        }
    }
}

class CameraFrame private constructor(
    private val rgb: ByteArray,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
) {
    fun channelAt(index: Int): Int = rgb[index].toInt() and 0xff

    companion object {
        fun fromRgb(
            rgb: ByteArray,
            width: Int,
            height: Int,
            rotationDegrees: Int = 0,
        ): CameraFrame {
            require(width > 0 && height > 0)
            val pixels = width.toLong() * height
            require(pixels <= Int.MAX_VALUE / 3)
            require(pixels * 3 == rgb.size.toLong())
            require(rotationDegrees in listOf(0, 90, 180, 270))
            return CameraFrame(rgb.copyOf(), width, height, rotationDegrees)
        }
    }
}
