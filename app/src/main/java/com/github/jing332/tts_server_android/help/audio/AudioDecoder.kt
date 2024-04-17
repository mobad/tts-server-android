package com.github.jing332.tts_server_android.help.audio

import android.media.MediaCodec
import android.media.MediaCodec.BufferInfo
import android.media.MediaCodecList
import android.media.MediaCodecList.ALL_CODECS
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import android.text.TextUtils
import android.util.Log
import com.github.jing332.tts_server_android.help.audio.AudioDecoderException.Companion.ERROR_CODE_NO_AUDIO_TRACK
import com.github.jing332.tts_server_android.utils.GcManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext


class AudioDecoder {
    companion object {
        const val TAG = "AudioDecode"

        suspend fun InputStream.readPcmChunk(
            bufferSize: Int = 4096,
            chunkSize: Int = 2048,
            onRead: suspend (ByteArray) -> Unit
        ) {
            var bufferFilledCount = 0
            val buffer = ByteArray(bufferSize)

            while (coroutineContext.isActive) {
                val readLen =
                    this.read(buffer, bufferFilledCount, chunkSize - bufferFilledCount)
                if (readLen == -1) {
                    if (bufferFilledCount > 0) {
                        val chunkData = buffer.copyOfRange(0, bufferFilledCount)
                        onRead.invoke(chunkData)
                    }
                    break
                }
                if (readLen == 0) {
                    delay(100)
                    continue
                }

                bufferFilledCount += readLen
                if (bufferFilledCount >= chunkSize) {
                    val chunkData = buffer.copyOfRange(0, chunkSize)

                    onRead.invoke(chunkData)
                    bufferFilledCount = 0
                }
            }
        }

        /**
         * 获取音频采样率
         */
        private fun getFormats(srcData: ByteArray): List<MediaFormat> {
            kotlin.runCatching {
                val mediaExtractor = MediaExtractor()
                mediaExtractor.setDataSource(ByteArrayMediaDataSource(srcData))

                val formats = mutableListOf<MediaFormat>()
                for (i in 0 until mediaExtractor.trackCount) {
                    formats.add(mediaExtractor.getTrackFormat(i))
                }
                return formats
            }
            return emptyList()
        }

        /**
         * 获取采样率和MIME
         */
        fun getSampleRateAndMime(audio: ByteArray): Pair<Int, String> {
            val formats = getFormats(audio)

            var sampleRate = 0
            var mime = ""
            if (formats.isNotEmpty()) {
                sampleRate = formats[0].getInteger(MediaFormat.KEY_SAMPLE_RATE)
                mime = formats[0].getString(MediaFormat.KEY_MIME) ?: ""
            }

            return Pair(sampleRate, mime)
        }

    }

    private val currentMime: String = ""
    private var mediaCodec: MediaCodec? = null
    private var oldMime: String? = null

    private fun getMediaCodec(mime: String, mediaFormat: MediaFormat): MediaCodec {
        if (mediaCodec == null || mime != oldMime) {
            if (null != mediaCodec) {
                mediaCodec!!.release()
                GcManager.doGC()
            }
            try {
                val codec = MediaCodecList(ALL_CODECS).findDecoderForFormat(mediaFormat)
                mediaCodec = MediaCodec.createByCodecName(codec)
                oldMime = mime
            } catch (ioException: IOException) {
                //设备无法创建，直接抛出
                ioException.printStackTrace()
                throw RuntimeException(ioException)
            }
        }
        mediaCodec!!.reset()
        mediaFormat.setInteger(MediaFormat.KEY_PRIORITY, 0)
        mediaCodec!!.configure(mediaFormat, null, null, 0)
        return mediaCodec as MediaCodec
    }

    suspend fun doDecode(
        srcData: ByteArray,
        sampleRate: Int,
        onRead: suspend (pcmData: ByteArray) -> Unit,
    ) {
        val mediaExtractor = MediaExtractor()
        try {
            // RIFF WAVEfmt直接去除文件头即可
            if (srcData.size > 15 && srcData.copyOfRange(0, 15).decodeToString()
                    .endsWith("WAVEfmt")
            ) {
                val data = srcData.copyOfRange(44, srcData.size)
                onRead.invoke(data)
                return
            }
            mediaExtractor.setDataSource(ByteArrayMediaDataSource(srcData))

            decodeInternal(mediaExtractor, sampleRate) {
                onRead.invoke(it)
            }
        } catch (e: Exception) {
            mediaCodec?.reset()
            throw AudioDecoderException(cause = e, message = "音频解码失败")
        } finally {
            mediaExtractor.release()
        }
    }


    /**
     * @param sampleRate opus音频必须设置采样率
     */
    suspend fun doDecode(
        ins: InputStream,
        sampleRate: Int = 0,
        timeoutUs: Long = 5000L,
        onRead: suspend (pcmData: ByteArray) -> Unit,
    ) {
        val mediaExtractor = MediaExtractor()
        try {
            mediaExtractor.setDataSource(InputStreamMediaDataSource(ins))

            decodeInternal(mediaExtractor, sampleRate, timeoutUs) {
                onRead.invoke(it)
            }
        } catch (e: Exception) {
            mediaCodec?.reset()
            throw AudioDecoderException(cause = e, message = "音频解码失败")
        } finally {
            mediaExtractor.release()
        }
    }

    private suspend fun decodeInternal(
        mediaExtractor: MediaExtractor,
        sampleRate: Int,
        timeoutUs: Long = 5000L,
        onRead: suspend (pcmData: ByteArray) -> Unit
    ) {
        val trackFormat = mediaExtractor.selectAudioTrack()
        val mime = trackFormat.mime

        //创建解码器
        val mediaCodec = getMediaCodec(mime, trackFormat)
        mediaCodec.start()

        val bufferInfo = BufferInfo()
        var inputBuffer: ByteBuffer?
        val startNanos = SystemClock.elapsedRealtimeNanos()
        while (coroutineContext.isActive) {
            //获取可用的inputBuffer，输入参数-1代表一直等到，0代表不等待，10*1000代表10秒超时
            val inputIndex = mediaCodec.dequeueInputBuffer(timeoutUs)
            if (inputIndex < 0) break

            inputBuffer = mediaCodec.getInputBuffer(inputIndex)
            if (inputBuffer == null) {
                continue
            }

            //从流中读取的采样数量
            val sampleSize = mediaExtractor.readSampleData(inputBuffer, 0)
            if (sampleSize > 0) {
                //入队解码
                mediaCodec.queueInputBuffer(inputIndex, 0, sampleSize, mediaExtractor.sampleTime, 0)
                //移动到下一个采样点
                if (!mediaExtractor.nextSample(startNanos)) {
                    Log.e(TAG, "nextSample(): 已到达流末尾EOF")
                }
            } else {
                mediaCodec.queueInputBuffer(
                    inputIndex,
                    0,
                    0,
                    0,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                )
            }

            //取解码后的数据/
            val outputIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, timeoutUs)
            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                break
            }

            //不一定能一次取完，所以要循环取
            var outputBuffer: ByteBuffer?
            val pcmData = ByteArray(bufferInfo.size)

            if (outputIndex >= 0) {
                outputBuffer = mediaCodec.getOutputBuffer(outputIndex)
                if (outputBuffer != null) {
                    outputBuffer.get(pcmData)
                    outputBuffer.clear() //用完后清空，复用
                }

                mediaCodec.releaseOutputBuffer(/* index = */ outputIndex, /* render = */ false)
                onRead.invoke(pcmData)
            }
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun MediaExtractor.nextSample(startNanos: Long): Boolean {
        return advance()
    }

    private val MediaFormat.mime: String
        get() = getString(MediaFormat.KEY_MIME) ?: ""


    private fun MediaExtractor.selectAudioTrack(): MediaFormat {
        var audioTrackIndex = -1
        var mime: String?
        var trackFormat: MediaFormat? = null
        for (i in 0 until trackCount) {
            trackFormat = getTrackFormat(i)
            mime = trackFormat.getString(MediaFormat.KEY_MIME)
            if (!TextUtils.isEmpty(mime) && mime!!.startsWith("audio/")) {
                audioTrackIndex = i
                break
            }
        }
        if (audioTrackIndex == -1)
            throw AudioDecoderException(ERROR_CODE_NO_AUDIO_TRACK, "没有找到音频流")

        Log.e(TAG, "tf: $trackFormat")

        selectTrack(audioTrackIndex)
        return trackFormat!!
    }
}