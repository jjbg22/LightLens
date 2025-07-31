package com.lightlens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.facebook.react.bridge.*
import com.facebook.react.module.annotations.ReactModule
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.channels.FileChannel


@ReactModule(name = IATModelModule.NAME)
class IATModelModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext), LifecycleEventListener {

    private var tflite: Interpreter? = null

    companion object {
        const val NAME = "IATModelModule"
    }

    private var model: IATModelLoader? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    override fun getName(): String = NAME

    init {
        reactContext.addLifecycleEventListener(this)
    }

    @ReactMethod
    fun initializeModel(promise: Promise) {
        try {
            val assetFileDescriptor = reactContext.assets.openFd("IAT.tflite")
            val fileInputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
            val fileChannel = fileInputStream.channel
            val startOffset = assetFileDescriptor.startOffset
            val declaredLength = assetFileDescriptor.declaredLength
            val modelBuffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)

            tflite = Interpreter(modelBuffer)

            model = IATModelLoader(reactContext)
            promise.resolve("Model initialized")
        } catch (e: Exception) {
            promise.reject("MODEL_INIT_FAILED", e.message, e)
        }
    }

    @ReactMethod
    fun runModel(base64Input: String, promise: Promise) {
        if (model == null) {
            promise.reject("MODEL_NOT_INITIALIZED", "Call initializeModel() first.")
            return
        }

        scope.launch {
            try {
                val inputBytes = Base64.decode(base64Input, Base64.DEFAULT)
                val inputBitmap = BitmapFactory.decodeByteArray(inputBytes, 0, inputBytes.size)

                val outputBitmap = model!!.runModel(inputBitmap)

                val outputStream = java.io.ByteArrayOutputStream()
                outputBitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                val outputBytes = outputStream.toByteArray()
                val outputBase64 = Base64.encodeToString(outputBytes, Base64.NO_WRAP)

                withContext(Dispatchers.Main) {
                    promise.resolve(outputBase64)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    promise.reject("MODEL_RUN_ERROR", e.message, e)
                }
            }
        }
    }

    // React Native Lifecycle events
    override fun onHostResume() {
        // 필요시 구현
    }

    override fun onHostPause() {
        // 필요시 구현
    }

    override fun onHostDestroy() {
        model?.close()
        scope.cancel()
        reactContext.removeLifecycleEventListener(this)
    }

    override fun invalidate() {
        super.invalidate()
        model?.close()
        scope.cancel()
    }

    @ReactMethod
    fun runInference(imageData: ReadableArray, promise: Promise) {
        try {
            if (tflite == null) {
                promise.reject("ModelNotInitialized", "모델이 초기화되지 않았습니다.")
                return
            }

            // 입력 이미지: [1, 3, 256, 256]
            val inputSize = 1 * 3 * 256 * 256
            if (imageData.size() != inputSize) {
                promise.reject("InvalidInput", "입력 데이터 크기가 올바르지 않습니다.")
                return
            }

            val inputBuffer = ByteBuffer.allocateDirect(4 * inputSize).order(ByteOrder.nativeOrder())
            for (i in 0 until inputSize) {
                inputBuffer.putFloat(imageData.getDouble(i).toFloat())
            }
            inputBuffer.rewind()

            // 출력 버퍼 초기화: 출력도 [1, 3, 256, 256]
            val outputSize = 1 * 3 * 256 * 256
            val outputBuffer = ByteBuffer.allocateDirect(4 * outputSize).order(ByteOrder.nativeOrder())
            outputBuffer.rewind()

            val outputs: MutableMap<Int, Any> = HashMap()
            outputs[0] = outputBuffer

            val inputs: Array<Any> = arrayOf(inputBuffer)

            tflite!!.runForMultipleInputsOutputs(inputs, outputs)

            outputBuffer.rewind()
            val resultArray = WritableNativeArray()
            for (i in 0 until outputSize) {
                resultArray.pushDouble(outputBuffer.getFloat().toDouble())
            }

            promise.resolve(resultArray)

        } catch (e: Exception) {
            promise.reject("InferenceFailed", e.message, e)
        }
    }

}
