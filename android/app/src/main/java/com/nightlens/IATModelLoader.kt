package com.nightlens

import android.graphics.Bitmap
import java.nio.ByteBuffer
import java.nio.ByteOrder

class IATModelLoader { // Context 불필요

    // Bitmap을 모델 입력(ByteBuffer)으로 변환
    fun convertBitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, 256, 256, true)
        val byteBuffer = ByteBuffer.allocateDirect(1 * 256 * 256 * 3 * 4)
        byteBuffer.order(ByteOrder.nativeOrder())

        val intValues = IntArray(256 * 256)
        resizedBitmap.getPixels(intValues, 0, 256, 0, 0, 256, 256)
        for (pixel in intValues) {
            val r = ((pixel shr 16) and 0xFF) / 255.0f
            val g = ((pixel shr 8) and 0xFF) / 255.0f
            val b = (pixel and 0xFF) / 255.0f
            byteBuffer.putFloat(r)
            byteBuffer.putFloat(g)
            byteBuffer.putFloat(b)
        }
        byteBuffer.rewind()
        return byteBuffer
    }

    // 모델 출력(ByteBuffer)을 Bitmap으로 변환
    fun convertByteBufferToBitmap(buffer: ByteBuffer, width: Int, height: Int): Bitmap {
        buffer.rewind()
        val pixels = IntArray(width * height)
        for (i in 0 until width * height) {
            val r = (buffer.float * 255.0f).toInt().coerceIn(0, 255)
            val g = (buffer.float * 255.0f).toInt().coerceIn(0, 255)
            val b = (buffer.float * 255.0f).toInt().coerceIn(0, 255)
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }
}