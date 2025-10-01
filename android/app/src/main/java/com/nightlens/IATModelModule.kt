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
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color
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
import java.io.ByteArrayOutputStream







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


    @ReactMethod
    fun runModelOnImage(base64Input: String, promise: Promise) {
        // dataConverter는 IATModelLoader의 인스턴스라고 가정합니다.
        if (interpreter == null || dataConverter == null) {
            promise.reject("MODEL_NOT_INITIALIZED", "Call initializeModel() first.")
            return
        }

        scope.launch {
            val localInterpreter = interpreter
            val localDataConverter = dataConverter

            if (localInterpreter == null || localDataConverter == null) {
                withContext(Dispatchers.Main) {
                    promise.reject("MODEL_NOT_INITIALIZED", "Model was de-initialized while processing.")
                }
                return@launch // 코루틴 종료
            }

            // --- 비트맵 변수 선언 ---
            var initialBitmap: Bitmap? = null
            var rotatedBitmap: Bitmap? = null
            var paddedInput: Bitmap? = null
            var modelInput: Bitmap? = null
            var croppedResult: Bitmap? = null
            var finalBitmap: Bitmap? = null

            try {
                Log.d("ImageProcessing", "--- 이미지 처리 시작 ---")
                val inputBytes = Base64.decode(base64Input, Base64.DEFAULT)
                initialBitmap = BitmapFactory.decodeByteArray(inputBytes, 0, inputBytes.size)
                    ?: throw Exception("Failed to decode base64 to bitmap.")
                Log.d("ImageProcessing", "초기 비트맵 로드 완료: ${initialBitmap.width}x${initialBitmap.height}")

                rotatedBitmap = rotateBitmapIfRequired(initialBitmap, inputBytes)
                if (initialBitmap != rotatedBitmap) {
                    initialBitmap.recycle()
                }
                val originalWidth = rotatedBitmap.width
                val originalHeight = rotatedBitmap.height
                Log.d("ImageProcessing", "회전 완료: ${originalWidth}x${originalHeight}")

                // 1. 패딩 적용
                paddedInput = padToSquare(rotatedBitmap)
                rotatedBitmap.recycle()
                Log.d("ImageProcessing", "패딩 적용 완료: ${paddedInput.width}x${paddedInput.height}")

                // 2. 모델 입력 크기로 스케일링
                modelInput = Bitmap.createScaledBitmap(paddedInput, 512, 512, true)
                paddedInput.recycle()
                Log.d("ImageProcessing", "모델 입력용 스케일링 완료.")

                // 3. ByteBuffer로 변환 및 모델 실행 (IATModelLoader에 맞게 수정)
                val inputBuffer = ByteBuffer.allocateDirect(512 * 512 * 3 * 4).order(ByteOrder.nativeOrder())
                val outputBuffer = ByteBuffer.allocateDirect(512 * 512 * 3 * 4).order(ByteOrder.nativeOrder())

                localDataConverter.convertBitmapToByteBuffer(modelInput, inputBuffer)
                modelInput.recycle()

                localInterpreter.run(inputBuffer, outputBuffer)
                Log.d("ImageProcessing", "모델 추론 완료.")

                // 4. 모델 결과물을 비트맵으로 변환 (IATModelLoader에 맞게 수정)
                val enhancedBitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
                localDataConverter.convertByteBufferToBitmap(outputBuffer, enhancedBitmap, 512, 512) // 반환값 없음
                Log.d("ImageProcessing", "결과 비트맵 변환 완료.")

                // 5. 패딩 제거 및 원본 비율로 복원
                if (originalWidth > originalHeight) {
                    val cropHeight = (512 * originalHeight.toFloat() / originalWidth.toFloat()).toInt()
                    val topOffset = (512 - cropHeight) / 2
                    croppedResult = Bitmap.createBitmap(enhancedBitmap, 0, topOffset, 512, cropHeight)
                    finalBitmap = Bitmap.createScaledBitmap(croppedResult, originalWidth, originalHeight, true)
                } else {
                    val cropWidth = (512 * originalWidth.toFloat() / originalHeight.toFloat()).toInt()
                    val leftOffset = (512 - cropWidth) / 2
                    croppedResult = Bitmap.createBitmap(enhancedBitmap, leftOffset, 0, cropWidth, 512)
                    finalBitmap = Bitmap.createScaledBitmap(croppedResult, originalWidth, originalHeight, true)
                }
                Log.d("ImageProcessing", "패딩 제거 및 최종 스케일링 완료.")

                enhancedBitmap.recycle()
                croppedResult?.recycle()

                // 6. Base64로 변환하여 반환
                val outputStream = ByteArrayOutputStream()
                finalBitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                val outputBase64 = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
                Log.d("ImageProcessing", "모든 처리 완료. 결과 반환.")

                finalBitmap.recycle() // 마지막 비트맵도 해제

                withContext(Dispatchers.Main) {
                    promise.resolve(outputBase64)
                }

            } catch (e: Exception) {
                Log.e("ImageProcessingError", "Error during image processing", e)
                withContext(Dispatchers.Main) {
                    val errorMessage = e.message ?: "Unknown Native Error (OOM or Threading)"
                    promise.reject("MODEL_RUN_ERROR", errorMessage, e)
                }
            } finally {
                // 예외 발생 시 남아있을 수 있는 비트맵들을 안전하게 해제
                initialBitmap?.recycle()
                rotatedBitmap?.recycle()
                paddedInput?.recycle()
                modelInput?.recycle()
                croppedResult?.recycle()
                finalBitmap?.recycle()
            }
        }
    }

    // 이 함수가 IATModelModule 클래스 내 또는 접근 가능한 곳에 있는지 확인해주세요.
    private fun padToSquare(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val size = kotlin.math.max(width, height)

        val paddedBitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(paddedBitmap)
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(
            bitmap,
            (size - width) / 2f,
            (size - height) / 2f,
            null
        )
        return paddedBitmap
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
            var capture: VideoCapture? = null
            var encoder: VideoEncoder? = null
            var completionDeferred: CompletableDeferred<String>? = null

            var matToBitmap: Bitmap? = null
            var rotatedBitmap: Bitmap? = null
            var modelInput: Bitmap? = null
            var enhancedBitmap: Bitmap? = null
            var finalBitmap: Bitmap? = null
            var outputBuffer: ByteBuffer? = null
            var inputBuffer: ByteBuffer? = null
            var finalPixels: IntArray? = null
            var rotatedCanvas: android.graphics.Canvas? = null
            var modelInputCanvas: android.graphics.Canvas? = null
            var finalCanvas: android.graphics.Canvas? = null

            try {
                if (!OpenCVLoader.initDebug()) {
                    withContext(Dispatchers.Main) {
                        promise.reject("OPENCV_LOAD_FAILED", "OpenCV failed to load")
                    }
                    return@launch
                }

                val cleanPath = inputPath.removePrefix("file://")
                val file = File(cleanPath)
                if (!file.exists()) {
                    withContext(Dispatchers.Main) {
                        promise.reject("VIDEO_FILE_NOT_FOUND", "Video file does not exist: $cleanPath")
                    }
                    return@launch
                }

                capture = VideoCapture(cleanPath)
                if (!capture.isOpened) {
                    withContext(Dispatchers.Main) {
                        promise.reject("VIDEO_CAPTURE_FAILED", "Cannot open video: $cleanPath")
                    }
                    return@launch
                }

                val rotationAngle = getVideoRotationAngle(cleanPath)
                val fps = capture.get(org.opencv.videoio.Videoio.CAP_PROP_FPS)
                var frameWidth = capture.get(org.opencv.videoio.Videoio.CAP_PROP_FRAME_WIDTH).toInt()
                var frameHeight = capture.get(org.opencv.videoio.Videoio.CAP_PROP_FRAME_HEIGHT).toInt()

                if (rotationAngle == 90 || rotationAngle == 270) {
                    val temp = frameWidth
                    frameWidth = frameHeight
                    frameHeight = temp
                }

                val outputPath = "${reactContext.cacheDir}/enhanced_video_${System.currentTimeMillis()}.mp4"
                completionDeferred = CompletableDeferred()
                encoder = VideoEncoder(outputPath, frameWidth, frameHeight, fps.toInt(), completionDeferred)
                encoder.start()

                val modelInputSize = 512
                val modelOutputSize = 512
                val matFrame = org.opencv.core.Mat()

                outputBuffer = ByteBuffer.allocateDirect(modelOutputSize * modelOutputSize * 3 * 4).order(ByteOrder.nativeOrder())
                inputBuffer = ByteBuffer.allocateDirect(modelInputSize * modelInputSize * 4).order(ByteOrder.nativeOrder())

                matToBitmap = Bitmap.createBitmap(frameWidth, frameHeight, Bitmap.Config.ARGB_8888)
                rotatedBitmap = Bitmap.createBitmap(frameWidth, frameHeight, Bitmap.Config.ARGB_8888)
                modelInput = Bitmap.createBitmap(modelInputSize, modelInputSize, Bitmap.Config.ARGB_8888)
                enhancedBitmap = Bitmap.createBitmap(modelOutputSize, modelOutputSize, Bitmap.Config.ARGB_8888)
                finalBitmap = Bitmap.createBitmap(frameWidth, frameHeight, Bitmap.Config.ARGB_8888)
                finalPixels = IntArray(frameWidth * frameHeight)
                val finalMatrix = Matrix()
                rotatedCanvas = android.graphics.Canvas(rotatedBitmap!!)
                modelInputCanvas = android.graphics.Canvas(modelInput!!)
                finalCanvas = android.graphics.Canvas(finalBitmap!!)


                while (capture.read(matFrame)) {
                    if (matFrame.dims() != 2) {
                        continue
                    }

                    Utils.matToBitmap(matFrame, matToBitmap!!)

                    val matrix = Matrix()
                    matrix.postRotate(rotationAngle.toFloat())
                    rotatedCanvas!!.drawBitmap(matToBitmap!!, matrix, null)

                    val modelInputMatrix = Matrix()
                    modelInputMatrix.setScale(modelInputSize.toFloat() / rotatedBitmap!!.width, modelInputSize.toFloat() / rotatedBitmap!!.height)
                    modelInputCanvas!!.drawBitmap(rotatedBitmap!!, modelInputMatrix, null)

                    // ✨ 수정 1: 기존에 할당된 inputBuffer를 재사용합니다.
                    dataConverter!!.convertBitmapToByteBuffer(modelInput!!, inputBuffer!!)
                    outputBuffer!!.rewind()
                    interpreter!!.run(inputBuffer!!, outputBuffer!!)
                    outputBuffer!!.rewind()

                    // ✨ 수정 2: dataConverter.convertByteBufferToBitmap 함수를 Bitmap을 재사용하도록 수정해야 합니다.
                    dataConverter!!.convertByteBufferToBitmap(outputBuffer!!, enhancedBitmap!!, modelOutputSize, modelOutputSize)

                    finalMatrix.setScale(frameWidth.toFloat() / enhancedBitmap!!.width, frameHeight.toFloat() / enhancedBitmap!!.height)
                    finalCanvas!!.drawBitmap(enhancedBitmap!!, finalMatrix, null)

                    finalBitmap!!.getPixels(finalPixels, 0, frameWidth, 0, 0, frameWidth, frameHeight)
                    encoder.enqueueFrame(finalPixels!!)
                }

                encoder.stop()
                val finalOutputPath = completionDeferred!!.await()

                withContext(Dispatchers.Main) {
                    promise.resolve(finalOutputPath)
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    promise.reject("VIDEO_ENCODING_ERROR", e.message, e)
                }
            } finally {
                capture?.release()
                matToBitmap?.recycle()
                rotatedBitmap?.recycle()
                modelInput?.recycle()
                enhancedBitmap?.recycle()
                finalBitmap?.recycle()
                outputBuffer?.clear()
                inputBuffer?.clear()
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
