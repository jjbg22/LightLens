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
import android.media.Image
import org.opencv.android.OpenCVLoader
import org.opencv.videoio.VideoCapture
import org.opencv.android.Utils
import android.content.ContentValues
import android.provider.MediaStore
import com.nightlens.VideoEncoder
import android.media.MediaMetadataRetriever







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
        if (!OpenCVLoader.initDebug()) {
            Log.e("OpenCV", "Unable to load OpenCV")
        } else {
            Log.d("OpenCV", "OpenCV loaded successfully")
        }

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

    private fun padToSquare(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val maxSide = kotlin.math.max(width, height)
        val paddedBitmap = Bitmap.createBitmap(maxSide, maxSide, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(paddedBitmap)
        canvas.drawColor(android.graphics.Color.BLACK)
        val left = (maxSide - width) / 2f
        val top = (maxSide - height) / 2f
        canvas.drawBitmap(bitmap, left, top, null)
        return paddedBitmap
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
                val originalWidth = rotatedBitmap.width
                val originalHeight = rotatedBitmap.height

                // 1. 패딩 적용: 원본 비율을 유지하며 정사각형으로 만듭니다.
                val paddedInput = padToSquare(rotatedBitmap)
                val modelInput = Bitmap.createScaledBitmap(paddedInput, 256, 256, true)

                val inputBuffer = dataConverter!!.convertBitmapToByteBuffer(modelInput)

                // 모델 실행
                val outputShape = interpreter!!.getOutputTensor(1).shape()
                val outputHeight = outputShape[2] // 256
                val outputWidth = outputShape[3]  // 256
                val outputChannels = outputShape[1]
                val outputBufferSize = 1 * outputHeight * outputWidth * outputChannels * 4
                val outputBuffer = ByteBuffer.allocateDirect(outputBufferSize).order(ByteOrder.nativeOrder())
                outputBuffer.rewind()

                interpreter!!.run(inputBuffer, outputBuffer)
                outputBuffer.rewind()

                // 2. 모델 결과물로부터 패딩을 제거하고 원본 비율로 되돌립니다.
                val enhancedBitmap = dataConverter!!.convertByteBufferToBitmap(outputBuffer, outputWidth, outputHeight)

                val finalBitmap: Bitmap
                if (originalWidth > originalHeight) { // 가로가 긴 이미지일 경우
                    val cropHeight = (256 * originalHeight.toFloat() / originalWidth.toFloat()).toInt()
                    val topOffset = (256 - cropHeight) / 2
                    val croppedResult = Bitmap.createBitmap(enhancedBitmap, 0, topOffset, 256, cropHeight)
                    finalBitmap = Bitmap.createScaledBitmap(croppedResult, originalWidth, originalHeight, true)
                    croppedResult.recycle()
                } else { // 세로가 긴 이미지일 경우
                    val cropWidth = (256 * originalWidth.toFloat() / originalHeight.toFloat()).toInt()
                    val leftOffset = (256 - cropWidth) / 2
                    val croppedResult = Bitmap.createBitmap(enhancedBitmap, leftOffset, 0, cropWidth, 256)
                    finalBitmap = Bitmap.createScaledBitmap(croppedResult, originalWidth, originalHeight, true)
                    croppedResult.recycle()
                }

                // Base64로 변환하여 반환
                val outputStream = java.io.ByteArrayOutputStream()
                finalBitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                val outputBase64 = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)

                withContext(Dispatchers.Main) {
                    promise.resolve(outputBase64)
                }

                // 메모리 해제
                inputBitmap.recycle()
                rotatedBitmap.recycle()
                paddedInput.recycle()
                modelInput.recycle()
                enhancedBitmap.recycle()
                finalBitmap.recycle()

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

    private fun getVideoRotationAngle(path: String): Int {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            rotation?.toIntOrNull() ?: 0
        } catch (e: Exception) {
            Log.e("VideoDebug", "Error getting video rotation: ${e.message}")
            0 // 오류 발생 시 기본값 0으로 설정
        } finally {
            retriever.release()
        }
    }

    @ReactMethod
    fun runModelOnVideo(inputPath: String, promise: Promise) {
        if (dataConverter == null || interpreter == null) {
            promise.reject("MODEL_NOT_INITIALIZED", "Call initializeModel() first.")
            return
        }

        scope.launch(Dispatchers.IO) {
            try {
                if (!OpenCVLoader.initDebug()) {
                    withContext(Dispatchers.Main) {
                        promise.reject("OPENCV_LOAD_FAILED", "OpenCV failed to load")
                    }
                    return@launch
                }

                val cleanPath = inputPath.removePrefix("file://")
                val file = File(cleanPath)
                Log.d("VideoDebug", "InputPath=$inputPath, CleanPath=$cleanPath, Exists=${file.exists()}")
                if (!file.exists()) {
                    withContext(Dispatchers.Main) {
                        promise.reject("VIDEO_FILE_NOT_FOUND", "Video file does not exist: $cleanPath")
                    }
                    return@launch
                }

                val capture = VideoCapture(cleanPath)
                if (!capture.isOpened) {
                    withContext(Dispatchers.Main) {
                        promise.reject("VIDEO_CAPTURE_FAILED", "Cannot open video: $cleanPath")
                    }
                    return@launch
                }

                val rotationAngle = getVideoRotationAngle(cleanPath)
                Log.d("VideoDebug", "Detected rotation angle from MediaMetadataRetriever: $rotationAngle")

                val fps = capture.get(org.opencv.videoio.Videoio.CAP_PROP_FPS)
                var frameWidth = capture.get(org.opencv.videoio.Videoio.CAP_PROP_FRAME_WIDTH).toInt()
                var frameHeight = capture.get(org.opencv.videoio.Videoio.CAP_PROP_FRAME_HEIGHT).toInt()
                Log.d("VideoDebug", "Captured FPS: $fps") // 이 로그를 확인하세요.

                val matFrame = org.opencv.core.Mat()
                var tmpBitmap: Bitmap? = null

                // ⭐️ 수정: 비디오가 세로 방향일 경우, 인코더의 너비와 높이를 바꿉니다.
                if (rotationAngle == 90 || rotationAngle == 270) {
                    val temp = frameWidth
                    frameWidth = frameHeight
                    frameHeight = temp
                }

                val outputPath = "${reactContext.cacheDir}/enhanced_video_${System.currentTimeMillis()}.mp4"

                val completionDeferred = CompletableDeferred<String>()
                val encoder = VideoEncoder(outputPath, frameWidth, frameHeight, fps.toInt(), completionDeferred)
                encoder.start()

                while (capture.read(matFrame)) {
                    tmpBitmap = Bitmap.createBitmap(matFrame.cols(), matFrame.rows(), Bitmap.Config.ARGB_8888)

                    Utils.matToBitmap(matFrame, tmpBitmap)

                    // ⭐️ 수정: 감지된 각도에 따라 프레임 비트맵을 회전시킵니다.
                    val matrix = Matrix()
                    matrix.postRotate(rotationAngle.toFloat())
                    val rotatedBitmap = Bitmap.createBitmap(tmpBitmap!!, 0, 0, tmpBitmap.width, tmpBitmap.height, matrix, true)
                    tmpBitmap!!.recycle() // 원본 비트맵 메모리 해제

                    val inputBuffer = dataConverter!!.convertBitmapToByteBuffer(rotatedBitmap)
                    val outputBuffer = ByteBuffer.allocateDirect(256 * 256 * 3 * 4).order(ByteOrder.nativeOrder())
                    interpreter!!.run(inputBuffer, outputBuffer)
                    outputBuffer.rewind()

                    val enhancedBitmap = dataConverter!!.convertByteBufferToBitmap(outputBuffer, 256, 256)
                    val finalBitmap = Bitmap.createScaledBitmap(enhancedBitmap, frameWidth, frameHeight, true)

                    encoder.enqueueFrame(finalBitmap)

                    rotatedBitmap.recycle()
                    enhancedBitmap.recycle()
                }

                capture.release()

                encoder.stop()
                val finalOutputPath = completionDeferred.await()

                withContext(Dispatchers.Main) {
                    promise.resolve(finalOutputPath)
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    promise.reject("VIDEO_ENCODING_ERROR", e.message, e)
                }
            }
        }
    }




    // Bitmap -> NV12
    fun bitmapToNV12(bitmap: Bitmap): ByteArray {
        val width = bitmap.width
        val height = bitmap.height
        val argb = IntArray(width * height)
        bitmap.getPixels(argb, 0, width, 0, 0, width, height)
        val yuv = ByteArray(width * height * 3 / 2)
        var yIndex = 0
        var uvIndex = width * height
        var a: Int
        var r: Int
        var g: Int
        var b: Int
        var y: Int
        var u: Int
        var v: Int

        for (j in 0 until height) {
            for (i in 0 until width) {
                a = argb[j * width + i] shr 24 and 0xFF
                r = argb[j * width + i] shr 16 and 0xFF
                g = argb[j * width + i] shr 8 and 0xFF
                b = argb[j * width + i] and 0xFF

                y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128

                yuv[yIndex++] = y.coerceIn(0, 255).toByte()
                if (j % 2 == 0 && i % 2 == 0) {
                    yuv[uvIndex++] = u.coerceIn(0, 255).toByte()
                    yuv[uvIndex++] = v.coerceIn(0, 255).toByte()
                }
            }
        }
        return yuv
    }


    fun convertYUVToBitmap(image: Image): Bitmap {
        val width = image.width
        val height = image.height
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val yRowStride = yPlane.rowStride
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride

        val pixels = IntArray(width * height)
        val uRow = ByteArray(width / 2)
        val vRow = ByteArray(width / 2)

        var index = 0
        for (j in 0 until height) {
            // Y row 읽기
            yBuffer.position(j * yRowStride)
            val yRow = ByteArray(width)
            yBuffer.get(yRow, 0, width)

            // UV row 읽기
            val uvRowStart = (j / 2) * uvRowStride
            for (i in 0 until width / 2) {
                uRow[i] = uBuffer.get(uvRowStart + i * uvPixelStride)
                vRow[i] = vBuffer.get(uvRowStart + i * uvPixelStride)
            }

            for (i in 0 until width) {
                val y = yRow[i].toInt() and 0xFF
                val u = (uRow[i / 2].toInt() and 0xFF) - 128
                val v = (vRow[i / 2].toInt() and 0xFF) - 128

                val r = (y + 1.402f * v).toInt().coerceIn(0, 255)
                val g = (y - 0.344136f * u - 0.714136f * v).toInt().coerceIn(0, 255)
                val b = (y + 1.772f * u).toInt().coerceIn(0, 255)

                pixels[index++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }


    @ReactMethod
    fun saveVideoToGallery(fileName: String, videoPath: String, promise: Promise) {
        try {
            val context = reactApplicationContext
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/NightLens")
            }

            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)

            if (uri != null) {
                resolver.openOutputStream(uri).use { outStream ->
                    File(videoPath).inputStream().use { inStream ->
                        inStream.copyTo(outStream!!)
                    }
                }
                promise.resolve("Saved to gallery: $uri")
            } else {
                promise.reject("SAVE_ERROR", "Failed to insert into MediaStore")
            }
        } catch (e: Exception) {
            promise.reject("SAVE_ERROR", e.message, e)
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
