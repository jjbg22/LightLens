package com.nightlens

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.flex.FlexDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import android.util.Log

class IATModelLoader(context: Context, modelFileName: String = "IAT.tflite") {

    private var interpreter: Interpreter
    private val flexDelegate = FlexDelegate()

    init {
        // Interpreter 옵션에 Flex delegate 추가
        val options = Interpreter.Options().addDelegate(flexDelegate)

        // assets에서 모델 파일 로드
        val modelBuffer = loadModelFile(context, modelFileName)

        // Interpreter 초기화
        interpreter = Interpreter(modelBuffer, options)
    }

    // assets에서 tflite 모델을 ByteBuffer로 읽기
    private fun loadModelFile(context: Context, filename: String): ByteBuffer {
        val afd = context.assets.openFd(filename)
        val inputStream = FileInputStream(afd.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = afd.startOffset
        val declaredLength = afd.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    fun convertBitmapToByteBuffer(bitmap: Bitmap, buffer: ByteBuffer) {
        buffer.rewind()

        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        // CHW 순서를 위해 R, G, B 채널 데이터를 분리해서 넣습니다.
        // 1. 모든 R 채널 값 쓰기
        for (i in 0 until width * height) {
            val pixel = pixels[i]
            val r = ((pixel shr 16) and 0xFF) / 255.0f * 2f - 1f
            buffer.putFloat(r)
        }
        // 2. 모든 G 채널 값 쓰기
        for (i in 0 until width * height) {
            val pixel = pixels[i]
            val g = ((pixel shr 8) and 0xFF) / 255.0f * 2f - 1f
            buffer.putFloat(g)
        }
        // 3. 모든 B 채널 값 쓰기
        for (i in 0 until width * height) {
            val pixel = pixels[i]
            val b = (pixel and 0xFF) / 255.0f * 2f - 1f
            buffer.putFloat(b)
        }

        buffer.rewind()
    }

    fun convertByteBufferToBitmap(buffer: ByteBuffer, bitmap: Bitmap, width: Int, height: Int) {
        buffer.rewind()
        val size = width * height
        val pixels = IntArray(size)

        // ✨ 이전 코드의 핵심 로직을 복원합니다.
        // 메모리를 조금 더 사용하더라도, 정확한 결과가 훨씬 중요합니다.
        val r = FloatArray(size)
        val g = FloatArray(size)
        val b = FloatArray(size)

        // CHW 순서대로 채널별 값을 분리해서 읽습니다.
        for (i in 0 until size) r[i] = buffer.float
        for (i in 0 until size) g[i] = buffer.float
        for (i in 0 until size) b[i] = buffer.float

        // 각 채널별로 최소/최대값을 계산합니다.
        val rMin = r.minOrNull() ?: 0f
        val rMax = r.maxOrNull() ?: 1f
        val gMin = g.minOrNull() ?: 0f
        val gMax = g.maxOrNull() ?: 1f
        val bMin = b.minOrNull() ?: 0f
        val bMax = b.maxOrNull() ?: 1f

        // 이전과 동일한 정규화 공식을 사용하여 픽셀을 조합합니다.
        for (i in 0 until size) {
            val red = (((r[i] - rMin) / (rMax - rMin)) * 255f).toInt().coerceIn(0, 255)
            val green = (((g[i] - gMin) / (gMax - gMin)) * 255f).toInt().coerceIn(0, 255)
            val blue = (((b[i] - bMin) / (bMax - bMin)) * 255f).toInt().coerceIn(0, 255)

            pixels[i] = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
        }

        // 최종 픽셀 데이터를 비트맵에 설정합니다.
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    }

    // 모델 추론 함수 (입출력 포맷에 맞게 조정 필요)
    fun runInferenceWithLogging(bitmap: Bitmap, inputBuffer: ByteBuffer): Pair<ByteBuffer, Long> {
        val startTime = System.currentTimeMillis()

        // 1. Preprocessing
        Log.d("IATTimer", "Start preprocessing")
        // ✨ 수정: inputBuffer를 인자로 전달하고 재사용합니다.
        convertBitmapToByteBuffer(bitmap, inputBuffer)
        val preProcessEnd = System.currentTimeMillis()
        Log.d("IATTimer", "Preprocessing time: ${preProcessEnd - startTime} ms")

        // 2. Inference
        Log.d("IATTimer", "Start inference")
        val outputBuffer = ByteBuffer.allocateDirect(512 * 512 * 3 * 4)
        outputBuffer.order(ByteOrder.nativeOrder())
        interpreter.run(inputBuffer, outputBuffer)
        val inferenceEnd = System.currentTimeMillis()
        Log.d("IATTimer", "Inference time: ${inferenceEnd - preProcessEnd} ms")

        val totalTime = inferenceEnd - startTime
        Log.d("IATTimer", "Total time: $totalTime ms")

        outputBuffer.rewind()
        return Pair(outputBuffer, totalTime)
    }


    // 필요시 interpreter close 함수 추가
    fun close() {
        interpreter.close()
        flexDelegate.close()
    }
}
