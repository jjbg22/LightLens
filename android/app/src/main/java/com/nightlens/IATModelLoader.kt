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

    // 기존의 Bitmap <-> ByteBuffer 변환 함수들 (생략하지 말고 그대로 둬도 됨)
    fun convertBitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, 256, 256, true)
        val byteBuffer = ByteBuffer.allocateDirect(1 * 3 * 256 * 256 * 4)
        byteBuffer.order(ByteOrder.nativeOrder())

        val intValues = IntArray(256 * 256)
        resizedBitmap.getPixels(intValues, 0, 256, 0, 0, 256, 256)
        val r = FloatArray(256 * 256)
        val g = FloatArray(256 * 256)
        val b = FloatArray(256 * 256)

        for (i in intValues.indices) {
            val pixel = intValues[i]
            r[i] = ((pixel shr 16) and 0xFF) / 255.0f * 2f - 1f // R: 0~255 → -1~1
            g[i] = ((pixel shr 8) and 0xFF) / 255.0f * 2f - 1f // G: 0~255 → -1~1
            b[i] = (pixel and 0xFF) / 255.0f * 2f - 1f // B: 0~255 → -1~1
        }

        for (i in 0 until 256 * 256) byteBuffer.putFloat(r[i])
        for (i in 0 until 256 * 256) byteBuffer.putFloat(g[i])
        for (i in 0 until 256 * 256) byteBuffer.putFloat(b[i])

        byteBuffer.rewind()
        return byteBuffer
    }

    fun convertByteBufferToBitmap(buffer: ByteBuffer, width: Int, height: Int): Bitmap {
        buffer.rewind()
        val size = width * height
        val r = FloatArray(size)
        val g = FloatArray(size)
        val b = FloatArray(size)



        // 먼저 채널별로 값을 분리해서 읽음 (CHW)
        for (i in 0 until size) r[i] = buffer.float
        for (i in 0 until size) g[i] = buffer.float
        for (i in 0 until size) b[i] = buffer.float

        // convertByteBufferToBitmap 함수 내, 채널별로 값을 읽어온 후 추가
        Log.d("IATDebug", "R channel first value: ${r[0]}")
        Log.d("IATDebug", "G channel first value: ${g[0]}")
        Log.d("IATDebug", "B channel first value: ${b[0]}")

        // RGB로 결합
        val pixels = IntArray(size)
        for (i in 0 until size) {
            val red = (r[i] * 255f).toInt().coerceIn(0, 255)
            val green = (g[i] * 255f).toInt().coerceIn(0, 255)
            val blue = (b[i] * 255f).toInt().coerceIn(0, 255)

            pixels[i] = (0xFF shl 24) or (blue shl 16) or (green shl 8) or red
        }

        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    // 모델 추론 함수 (입출력 포맷에 맞게 조정 필요)
    fun runInferenceWithLogging(bitmap: Bitmap): Pair<ByteBuffer, Long> {
        val startTime = System.currentTimeMillis()

        // 1. Preprocessing
        Log.d("IATTimer", "Start preprocessing")
        val inputBuffer = convertBitmapToByteBuffer(bitmap)
        val preProcessEnd = System.currentTimeMillis()
        Log.d("IATTimer", "Preprocessing time: ${preProcessEnd - startTime} ms")

        // 2. Inference
        Log.d("IATTimer", "Start inference")
        val outputBuffer = ByteBuffer.allocateDirect(1 * 256 * 256 * 3 * 4)
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
