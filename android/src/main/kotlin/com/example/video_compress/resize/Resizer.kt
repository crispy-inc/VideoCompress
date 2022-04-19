package com.example.video_compress.resize

import android.util.Size
import kotlin.math.roundToInt

class Resizer(private val atMostMinor: Int, private val atMostMajor: Int) {
    constructor(atMost: Int): this(atMost, Int.MAX_VALUE)

    fun getOutputSize(inputSize: Size): Size {
        if (inputSize.getMinor() <= atMostMinor && inputSize.getMajor() <= atMostMajor) {
            return inputSize
        }
        val minorScale = inputSize.getMinor().toDouble() / atMostMinor
        val majorScale = inputSize.getMajor().toDouble() / atMostMajor
        val inputRatio = inputSize.getMinor().toDouble() / inputSize.getMajor()
        val outMinor: Int
        val outMajor: Int
        if (majorScale >= minorScale) {
            outMajor = atMostMajor.toPixel()
            outMinor = (outMajor * inputRatio).roundToInt()
        } else {
            outMinor = atMostMinor
            outMajor = (outMinor / inputRatio).roundToInt()
        }
        return inputSize.migrate(outMinor.toPixel(), outMajor.toPixel())
    }

    override fun toString(): String {
        return "${atMostMinor}x$atMostMajor"
    }
}

private fun Size.migrate(minor: Int, major: Int): Size {
    if (width > height) {
        return Size(major, minor)
    }
    return Size(minor, major)
}

private fun Size.getMinor(): Int {
    if (width < height) {
        return width
    }
    return height
}

private fun Size.getMajor(): Int {
    if (width < height) {
        return height
    }
    return width
}

private fun Int.toPixel(): Int {
    if (this % 2 == 0) {
        return this
    }
    return this - 1
}