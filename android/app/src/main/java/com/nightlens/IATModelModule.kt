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
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.Image
import android.view.Surface
import android.graphics.Canvas
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ArrayBlockingQueue
import android.media.MediaMuxer







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

    @ReactMethod
    fun runModelOnVideo(inputPath: String, outputPath: String, promise: Promise) {
        if (interpreter == null || dataConverter == null) {
            promise.reject("MODEL_NOT_INITIALIZED", "Call initializeModel() first.")
            return
        }

        // --- 수정됨: EOS 더미 비트맵 정의 ---
        val EOS = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

        scope.launch(Dispatchers.IO) {
            try {
                val extractor = MediaExtractor()
                extractor.setDataSource(inputPath)
                var videoTrackIndex = -1
                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME)
                    if (mime != null && mime.startsWith("video/")) {
                        videoTrackIndex = i
                        extractor.selectTrack(i)
                        break
                    }
                }
                if (videoTrackIndex == -1) throw Exception("Video track not found")

                val inputFormat = extractor.getTrackFormat(videoTrackIndex)
                val width = inputFormat.getInteger(MediaFormat.KEY_WIDTH)
                val height = inputFormat.getInteger(MediaFormat.KEY_HEIGHT)

                val outputFormat = MediaFormat.createVideoFormat("video/avc", width, height).apply {
                    setInteger(
                        MediaFormat.KEY_COLOR_FORMAT,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
                    )
                    setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
                    setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                }

                val encoder = MediaCodec.createEncoderByType("video/avc")
                encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                encoder.start()

                val decoder =
                    MediaCodec.createDecoderByType(inputFormat.getString(MediaFormat.KEY_MIME)!!)
                decoder.configure(inputFormat, null, null, 0)
                decoder.start()

                val frameQueue = LinkedBlockingQueue<Bitmap>(5)
                val resultQueue = LinkedBlockingQueue<Bitmap>(5)
                val bufferInfo = MediaCodec.BufferInfo()
                var isEOS = false

                // --- Decoder Job ---
                val decoderJob = launch {
                    try {
                        while (!isEOS) {
                            val inIndex = decoder.dequeueInputBuffer(10_000)
                            if (inIndex >= 0) {
                                val inputBuffer = decoder.getInputBuffer(inIndex)!!
                                val sampleSize = extractor.readSampleData(inputBuffer, 0)
                                if (sampleSize < 0) {
                                    decoder.queueInputBuffer(
                                        inIndex, 0, 0, 0,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                    )
                                    isEOS = true
                                } else {
                                    decoder.queueInputBuffer(
                                        inIndex, 0, sampleSize, extractor.sampleTime, 0
                                    )
                                    extractor.advance()
                                }
                            }

                            val outIndex = decoder.dequeueOutputBuffer(bufferInfo, 10_000)
                            if (outIndex >= 0) {
                                val image = decoder.getOutputImage(outIndex)
                                if (image != null) {
                                    val bmp256 = Bitmap.createScaledBitmap(
                                        convertYUVToBitmap(image),
                                        256, 256, true
                                    )
                                    image.close()
                                    // --- 수정됨: null 대신 EOS 사용 ---
                                    if (!frameQueue.offer(bmp256)) {
                                        frameQueue.poll()
                                        frameQueue.offer(bmp256)
                                    }
                                }
                                decoder.releaseOutputBuffer(outIndex, false)
                            }
                        }
                        // --- 수정됨: EOS 신호 ---
                        frameQueue.offer(EOS)
                    } catch (e: Exception) {
                        Log.e("runModelOnVideo", "Decoder error", e)
                    }
                }

                // --- Inference Job ---
                val inferenceJob = launch {
                    try {
                        while (true) {
                            val inputFrame = frameQueue.take()
                            // --- 수정됨: EOS 감지 시 종료 ---
                            if (inputFrame == EOS) break

                            val inputBuffer = dataConverter!!.convertBitmapToByteBuffer(inputFrame)
                            val outputBuffer = ByteBuffer.allocateDirect(256 * 256 * 3 * 4)
                                .order(ByteOrder.nativeOrder())
                            interpreter!!.run(inputBuffer, outputBuffer)
                            outputBuffer.rewind()
                            val enhancedBitmap =
                                dataConverter!!.convertByteBufferToBitmap(outputBuffer, 256, 256)
                            if (!resultQueue.offer(enhancedBitmap)) {
                                resultQueue.poll()
                                resultQueue.offer(enhancedBitmap)
                            }
                        }
                        // 종료 신호
                        resultQueue.offer(EOS)
                    } catch (e: Exception) {
                        Log.e("runModelOnVideo", "Inference error", e)
                    }
                }

                // --- Encoder + Muxer Job ---
                val muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                var trackIndex = -1
                val encoderJob = launch {
                    try {
                        while (true) {
                            val enhancedFrame = resultQueue.take()
                            if (enhancedFrame == EOS) break

                            val outputBitmap =
                                Bitmap.createScaledBitmap(enhancedFrame, width, height, true)
                            val yuvData = bitmapToNV12(outputBitmap)

                            val inIndex = encoder.dequeueInputBuffer(10_000)
                            if (inIndex >= 0) {
                                val encBuffer = encoder.getInputBuffer(inIndex)!!
                                encBuffer.clear()
                                encBuffer.put(yuvData)
                                encoder.queueInputBuffer(
                                    inIndex,
                                    0,
                                    yuvData.size,
                                    System.nanoTime() / 1000,
                                    0
                                )
                            }

                            // Encoder output
                            var outIndex = encoder.dequeueOutputBuffer(bufferInfo, 10_000)
                            while (outIndex >= 0) {
                                if (trackIndex == -1) {
                                    val format = encoder.outputFormat
                                    trackIndex = muxer.addTrack(format)
                                    muxer.start()
                                }
                                val encodedData = encoder.getOutputBuffer(outIndex)!!
                                muxer.writeSampleData(trackIndex, encodedData, bufferInfo)
                                encoder.releaseOutputBuffer(outIndex, false)
                                outIndex = encoder.dequeueOutputBuffer(bufferInfo, 0)
                            }
                        }

                        // EOS 처리
                        val inIndex = encoder.dequeueInputBuffer(10_000)
                        if (inIndex >= 0) {
                            encoder.queueInputBuffer(
                                inIndex,
                                0,
                                0,
                                0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                        }
                        encoder.signalEndOfInputStream()

                        var outIndex = encoder.dequeueOutputBuffer(bufferInfo, 10_000)
                        while (outIndex >= 0) {
                            val encodedData = encoder.getOutputBuffer(outIndex)!!
                            muxer.writeSampleData(trackIndex, encodedData, bufferInfo)
                            encoder.releaseOutputBuffer(outIndex, false)
                            outIndex = encoder.dequeueOutputBuffer(bufferInfo, 0)
                        }

                    } catch (e: Exception) {
                        Log.e("runModelOnVideo", "Encoder/Muxer error", e)
                    } finally {
                        muxer.stop()
                        muxer.release()
                    }
                }

                decoderJob.join()
                inferenceJob.join()
                encoderJob.join()

                decoder.stop()
                decoder.release()
                encoder.stop()
                encoder.release()
                extractor.release()

                withContext(Dispatchers.Main) {
                    promise.resolve(outputPath)
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    promise.reject("VIDEO_CPU_ERROR", e.message, e)
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


    fun renderBitmapToSurface(bitmap: Bitmap, surface: Surface) {
        val canvas: Canvas = surface.lockCanvas(null)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
        surface.unlockCanvasAndPost(canvas)
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
