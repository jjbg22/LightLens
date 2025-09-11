package com.nightlens

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import kotlinx.coroutines.*
import kotlinx.coroutines.CompletableDeferred
import com.facebook.react.bridge.Promise



class VideoEncoder(
    private val outputPath: String,
    private val width: Int,
    private val height: Int,
    private val frameRate: Int,
    private val completionDeferred: CompletableDeferred<String>
) {
    private var encoder: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var videoTrackIndex = -1
    private var isMuxerStarted = false
    private var presentationTimeUs: Long = 0
    private val bufferInfo = MediaCodec.BufferInfo()
    private val TIMEOUT_US = 10000L
    private val frameQueue = ArrayBlockingQueue<Bitmap>(10) // 10개 버퍼로 충분
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isRunning = false


    private fun bitmapToNV12(bitmap: Bitmap): ByteArray {
        val yuv = ByteArray(width * height * 3 / 2)
        val argb = IntArray(width * height)

        bitmap.getPixels(argb, 0, width, 0, 0, width, height)

        var yIndex = 0
        var uvIndex = width * height

        for (j in 0 until height) {
            for (i in 0 until width) {
                val pixel = argb[j * width + i]
                val r = (pixel shr 16) and 0xff
                val g = (pixel shr 8) and 0xff
                val b = pixel and 0xff

                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128

                yuv[yIndex++] = y.coerceIn(0, 255).toByte()

                if (j % 2 == 0 && i % 2 == 0) {
                    yuv[uvIndex++] = u.coerceIn(0, 255).toByte()
                    yuv[uvIndex++] = v.coerceIn(0, 255).toByte()
                }
            }
        }
        return yuv
    }


    fun start() {
        Log.d("VideoEncoder", "Starting encoder with path: $outputPath")
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)
                setInteger(MediaFormat.KEY_BIT_RATE, 2000000)
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                // ⭐️ 호환성을 위한 프로파일 및 레벨 추가
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileMain)
                setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)
            }

            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder!!.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

            encoder!!.start()
            muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            isRunning = true
            scope.launch {
                processFrames()
            }

        } catch (e: Exception) {
            completionDeferred.completeExceptionally(e)
            Log.e("VideoEncoder", "ENCODER_START_FAILED", e)
            stop()
        }
    }

    fun enqueueFrame(bitmap: Bitmap) {
        if (isRunning) {
            frameQueue.offer(bitmap) // 큐가 가득차면 버림
        }
    }

    // ⭐️ 수정: 비동기적으로 프레임을 처리하는 로직을 이 함수에 통합
    private suspend fun processFrames() {
        val frameIntervalUs = (1000000 / frameRate).toLong()
        var presentationTimeUs: Long = 0

        while (isRunning || frameQueue.isNotEmpty()) {
            val bitmap = frameQueue.poll()
            if (bitmap == null) {
                if (!isRunning) break
                delay(10)
                continue
            }

            val inputBufferIndex = encoder!!.dequeueInputBuffer(TIMEOUT_US)

            if (inputBufferIndex >= 0) {
                val inputBuffer = encoder!!.getInputBuffer(inputBufferIndex)
                inputBuffer?.let {
                    val yuvData = bitmapToNV12(bitmap)
                    it.clear()
                    it.put(yuvData)
                    // ⭐️ presentationTimeUs를 수동으로 전달
                    encoder!!.queueInputBuffer(inputBufferIndex, 0, yuvData.size, presentationTimeUs, 0)
                    presentationTimeUs += frameIntervalUs
                }
            }

            drainEncoder(false)
        }
    }

    private fun drainEncoder(endOfStream: Boolean) {
        if (encoder == null || muxer == null) return

        if (endOfStream) {
            encoder!!.signalEndOfInputStream()
        }

        while (true) {
            val outputBufferIndex = encoder!!.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) break else Log.d("VideoEncoder", "No output available, still waiting")
            } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (isMuxerStarted) {
                    throw RuntimeException("Format changed after muxer start")
                }
                videoTrackIndex = muxer!!.addTrack(encoder!!.outputFormat)
                muxer!!.start()
                isMuxerStarted = true
            } else if (outputBufferIndex < 0) {
                Log.w("VideoEncoder", "Unexpected result from dequeueOutputBuffer: $outputBufferIndex")
            } else {
                Log.d("VideoEncoderDebug", "Output frame presentationTimeUs: ${bufferInfo.presentationTimeUs}")

                val outputBuffer = encoder!!.getOutputBuffer(outputBufferIndex)
                outputBuffer?.let {
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        bufferInfo.size = 0
                    }
                    if (bufferInfo.size != 0) {
                        if (!isMuxerStarted) {
                            throw RuntimeException("Muxer not started!")
                        }
                        it.position(bufferInfo.offset)
                        it.limit(bufferInfo.offset + bufferInfo.size)
                        muxer!!.writeSampleData(videoTrackIndex, it, bufferInfo)
                    }
                }
                encoder!!.releaseOutputBuffer(outputBufferIndex, false)
                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break
                }
            }
        }
    }

    fun stop() {
        isRunning = false

        // ⭐️ scope를 취소하여 processFrames 코루틴에 종료를 알림
        scope.cancel()

        runBlocking {
            // 이 시점에서 processFrames()는 남아있는 프레임을 모두 처리하고
            // drainEncoder(true)를 호출한 후 안전하게 종료될 것임
            scope.coroutineContext.job.join()
        }
        try {
            encoder?.stop()
            encoder?.release()
            muxer?.stop()
            muxer?.release()
            completionDeferred.complete(outputPath)
        } catch (e: Exception) {
            Log.e("VideoEncoder", "Error stopping encoder/muxer", e)
            completionDeferred.completeExceptionally(e)
        }
    }
}