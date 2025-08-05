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
import org.tensorflow.lite.flex.FlexDelegate
import java.io.FileInputStream
import java.nio.channels.FileChannel
import android.util.Log
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.File



@ReactModule(name = IATModelModule.NAME)
class IATModelModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext), LifecycleEventListener {

    private var interpreter: Interpreter? = null
    private var flexDelegate: FlexDelegate? = null
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

            flexDelegate = FlexDelegate()
            val options = Interpreter.Options().addDelegate(flexDelegate)

            interpreter = Interpreter(modelBuffer, options)

            // IATModelLoader 생성 시 Context 인자가 필요하니까 전달해 줘야 해
            dataConverter = IATModelLoader(reactContext)

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
                val rotatedBitmap = rotateBitmapIfRequired(inputBitmap, inputBytes)
                val inputBuffer = dataConverter!!.convertBitmapToByteBuffer(rotatedBitmap)

                val outputShape = interpreter!!.getOutputTensor(1).shape()
                val outputDataType = interpreter!!.getOutputTensor(1).dataType()

                Log.d("IATModel", "Output tensor shape: ${outputShape.joinToString(", ")}")
                Log.d("IATModel", "Output tensor dtype: $outputDataType")


                val outputChannels = outputShape[1]  // 3
                val outputHeight = outputShape[2]    // 256
                val outputWidth = outputShape[3]     // 256

                // float = 4 bytes
                val outputBufferSize = 1 * outputHeight * outputWidth * outputChannels * 4

                val outputBuffer = ByteBuffer.allocateDirect(outputBufferSize).order(ByteOrder.nativeOrder())

                outputBuffer.rewind()

                interpreter!!.run(inputBuffer, outputBuffer)
                outputBuffer.rewind()

                val outputBitmap = dataConverter!!.convertByteBufferToBitmap(outputBuffer, outputWidth, outputHeight)


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

    private fun rotateBitmapIfRequired(bitmap: Bitmap, imageBytes: ByteArray): Bitmap {
        return try {
            // 임시 파일로 저장
            val tempFile = File.createTempFile("temp_image", ".jpg", reactContext.cacheDir)
            tempFile.writeBytes(imageBytes)

            val exif = ExifInterface(tempFile.absolutePath)
            val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            val matrix = Matrix()

            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            }

            if (matrix.isIdentity) bitmap else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } catch (e: Exception) {
            bitmap
        }
    }


    override fun onHostResume() {
        // 빈 구현
    }

    override fun onHostPause() {
        // 빈 구현
    }

    override fun onHostDestroy() {
        interpreter?.close()
        flexDelegate?.close()
        scope.cancel()
        reactContext.removeLifecycleEventListener(this)
    }

    override fun invalidate() {
        super.invalidate()
        interpreter?.close()
        flexDelegate?.close()
        scope.cancel()
    }
}
