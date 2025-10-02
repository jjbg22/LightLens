package com.nightlens

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import kotlinx.coroutines.*
import java.util.concurrent.ArrayBlockingQueue

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
    private val bufferInfo = MediaCodec.BufferInfo()
    private val TIMEOUT_US = 10000L

    // ✨ 1. 코루틴 Job을 관리할 변수 추가
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var processFramesJob: Job? = null

    // ✨ 2. isRunning을 Volatile로 만들어 여러 스레드에서 안전하게 접근하도록 변경
    @Volatile
    private var isRunning = false

    private val frameQueue = ArrayBlockingQueue<IntArray>(10)
    // ✨ 3. 루프를 깨우고 종료 신호를 보낼 특별한 객체 (독약)
    private val POISON_PILL = IntArray(0)

    // YUV 변환 함수 (기존과 동일)
    private fun intArrayToNV12(pixels: IntArray, width: Int, height: Int): ByteArray {
        val yuv = ByteArray(width * height * 3 / 2)
        var yIndex = 0
        var uvIndex = width * height

        for (j in 0 until height) {
            for (i in 0 until width) {
                val pixel = pixels[j * width + i]
                // ... (나머지 YUV 변환 로직은 동일) ...
                val r = (pixel shr 16) and 0xff
                val g = (pixel shr 8) and 0xff
                val b = pixel and 0xff

                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128

                yuv[yIndex++] = y.coerceIn(0, 255).toByte()

                if (j % 2 == 0 && i % 2 == 0) {
                    yuv[uvIndex++] = v.coerceIn(0, 255).toByte()
                    yuv[uvIndex++] = u.coerceIn(0, 255).toByte()
                }
            }
        }
        return yuv
    }


    fun start() {
        if (isRunning) {
            Log.w("VideoEncoder", "Encoder is already running.")
            return
        }
        Log.d("VideoEncoder", "Starting encoder...")
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)
                setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000) // 비트레이트 약간 상향
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }

            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder!!.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder!!.start()

            muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            isRunning = true
            // ✨ 4. 시작된 코루틴 Job을 변수에 저장
            processFramesJob = scope.launch {
                processFrames()
            }

        } catch (e: Exception) {
            Log.e("VideoEncoder", "ENCODER_START_FAILED", e)
            completionDeferred.completeExceptionally(e)
            stop() // 시작 실패 시 정리
        }
    }

    fun enqueueFrame(pixels: IntArray): Boolean {
        if (!isRunning) return false
        try {
            // 원본 배열이 재사용될 수 있으므로, 큐에 넣기 전에 복사본을 만듭니다.
            return frameQueue.offer(pixels.clone())
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Log.w("VideoEncoder", "Enqueue frame interrupted")
        }
        return false
    }

    private fun processFrames() {
        val frameIntervalUs = (1_000_000 / frameRate).toLong()
        var presentationTimeUs: Long = 0

        try {
            while (true) {
                // ✨ 5. poll 대신 take()를 사용해 큐가 비어있으면 대기 (효율적)
                val pixels = frameQueue.take()

                // 큐에서 '독약'을 받으면 루프 종료
                if (pixels.isEmpty()) { // POISON_PILL 체크
                    break
                }

                val inputBufferIndex = encoder!!.dequeueInputBuffer(TIMEOUT_US)
                if (inputBufferIndex >= 0) {
                    val yuvData = intArrayToNV12(pixels, width, height)
                    val inputBuffer = encoder!!.getInputBuffer(inputBufferIndex)!!
                    inputBuffer.clear()
                    inputBuffer.put(yuvData)
                    encoder!!.queueInputBuffer(inputBufferIndex, 0, yuvData.size, presentationTimeUs, 0)
                    presentationTimeUs += frameIntervalUs
                }
                drainEncoder(false)
            }
            // 루프가 정상적으로 끝나면 마지막으로 인코더를 비워줌
            drainEncoder(true)

        } catch (e: Exception) {
            Log.e("VideoEncoder", "Error in processFrames loop", e)
            completionDeferred.completeExceptionally(e)
        }
    }

    private fun drainEncoder(endOfStream: Boolean) {
        if (endOfStream) {
            try {
                encoder?.signalEndOfInputStream()
            } catch (e: IllegalStateException) {
                Log.e("VideoEncoder", "signalEndOfInputStream failed, encoder might be in a wrong state.", e)
                return // 여기서 중단
            }
        }

        while (true) {
            val outputBufferIndex = try {
                encoder!!.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            } catch (e: Exception) {
                Log.e("VideoEncoder", "dequeueOutputBuffer failed", e)
                break
            }

            when (outputBufferIndex) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) break
                }
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (isMuxerStarted) throw RuntimeException("Format changed after muxer was started.")
                    videoTrackIndex = muxer!!.addTrack(encoder!!.outputFormat)
                    muxer!!.start()
                    isMuxerStarted = true
                }
                else -> {
                    if (outputBufferIndex < 0) {
                        Log.w("VideoEncoder", "Unexpected result from dequeueOutputBuffer: $outputBufferIndex")
                        continue
                    }

                    val outputBuffer = encoder!!.getOutputBuffer(outputBufferIndex) ?: continue

                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && bufferInfo.size != 0) {
                        if (!isMuxerStarted) {
                            // 포맷이 변경되기 전에 데이터가 나오는 경우가 있어 무시
                        } else {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            muxer!!.writeSampleData(videoTrackIndex, outputBuffer, bufferInfo)
                        }
                    }

                    encoder!!.releaseOutputBuffer(outputBufferIndex, false)

                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        break
                    }
                }
            }
        }
    }

    fun stop() {
        // ✨ 6. 중복 호출을 막는 가드
        if (!isRunning) return
        Log.d("VideoEncoder", "Stopping encoder...")

        isRunning = false
        // ✨ 7. 큐에 종료 신호를 보내 processFrames 루프를 깨움
        frameQueue.put(POISON_PILL)

        // ✨ 8. 코루틴이 작업을 마칠 때까지 안전하게 기다림 (cancel() 대신 join())
        runBlocking {
            processFramesJob?.join()
        }

        try {
            // ✨ 9. 모든 리소스를 순서대로 안전하게 해제
            encoder?.stop()
            encoder?.release()
            muxer?.stop()
            muxer?.release()

            Log.d("VideoEncoder", "Encoder stopped successfully.")
            if (!completionDeferred.isCompleted) {
                completionDeferred.complete(outputPath)
            }
        } catch (e: Exception) {
            Log.e("VideoEncoder", "Error stopping encoder/muxer", e)
            if (!completionDeferred.isCompleted) {
                completionDeferred.completeExceptionally(e)
            }
        }
    }
}