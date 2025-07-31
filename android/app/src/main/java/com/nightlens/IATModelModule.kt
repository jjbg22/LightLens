package com.nightlens

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

    private var interpreter: Interpreter? = null
    private var dataConverter: IATModelLoader? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    companion object {
        const val NAME = "IATModelModule"
    }

    override fun getName(): String = NAME

    init {
        reactContext.addLifecycleEventListener(this)
    }

    @ReactMethod
    fun initializeModel(promise: Promise) {
        if (interpreter != null) {
            promise.resolve("Model already initialized")
            return
        }
        try {
            val assetFileDescriptor = reactContext.assets.openFd("IAT.tflite")
            val fileInputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
            val fileChannel = fileInputStream.channel
            val startOffset = assetFileDescriptor.startOffset
            val declaredLength = assetFileDescriptor.declaredLength
            val modelBuffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)

            interpreter = Interpreter(modelBuffer)
            dataConverter = IATModelLoader()
            
            promise.resolve("Model initialized successfully")
        } catch (e: Exception) {
            promise.reject("MODEL_INIT_FAILED", e.message, e)
        }
    }

    @ReactMethod
    fun runModelOnImage(base64Input: String, promise: Promise) {
        if (interpreter == null || dataConverter == null) {
            promise.reject("MODEL_NOT_INITIALIZED", "Call initializeModel() first.")
            return
        }

        scope.launch {
            try {
                val inputBytes = Base64.decode(base64Input, Base64.DEFAULT)
                val inputBitmap = BitmapFactory.decodeByteArray(inputBytes, 0, inputBytes.size)
                val inputBuffer = dataConverter!!.convertBitmapToByteBuffer(inputBitmap)
                val outputBuffer = ByteBuffer.allocateDirect(1 * 256 * 256 * 3 * 4).order(ByteOrder.nativeOrder())

                interpreter!!.run(inputBuffer, outputBuffer)

                val outputBitmap = dataConverter!!.convertByteBufferToBitmap(outputBuffer, 256, 256)
                val outputStream = java.io.ByteArrayOutputStream()
                outputBitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                val outputBase64 = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)

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

    // ❗️ 아래 함수들이 누락되어 있었습니다.
    override fun onHostResume() {
        // 필수 구현 (내용은 없어도 됨)
    }

    override fun onHostPause() {
        // 필수 구현 (내용은 없어도 됨)
    }

    override fun onHostDestroy() {
        interpreter?.close()
        scope.cancel()
        reactContext.removeLifecycleEventListener(this)
    }

    override fun invalidate() {
        super.invalidate()
        interpreter?.close()
        scope.cancel()
    }
}