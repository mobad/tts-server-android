package com.github.jing332.tts_server_android.help.audio.exo

import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.Format
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.AudioSink.SINK_FORMAT_SUPPORTED_WITH_TRANSCODING
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import java.nio.ByteBuffer

@UnstableApi
/**
 * 用于接收从 ExoPlayer 解码后的 PCM 数据，而不是播放到 AudioTrack。
 */
class DecoderAudioSink(private val onPcmBuffer: (ByteBuffer) -> Unit) : AudioSink {
    private var timeUs: Long = 0L
    private var skippingAudioProcessor =
        SilenceSkippingAudioProcessor(
            SilenceSkippingAudioProcessor.DEFAULT_MINIMUM_SILENCE_DURATION_US,
            SilenceSkippingAudioProcessor.DEFAULT_SILENCE_RETENTION_RATIO,
            SilenceSkippingAudioProcessor.DEFAULT_MAX_SILENCE_TO_KEEP_DURATION_US,
            SilenceSkippingAudioProcessor.DEFAULT_MIN_VOLUME_TO_KEEP_PERCENTAGE,
            SilenceSkippingAudioProcessor.DEFAULT_SILENCE_THRESHOLD_LEVEL
        )

    companion object {
        const val TAG = "DecoderAudioSink"
    }

    override fun setListener(listener: AudioSink.Listener) {
    }

    override fun supportsFormat(format: Format): Boolean {
        return format.sampleMimeType == "audio/raw" // 只接收 PCM 格式
    }

    override fun getFormatSupport(format: Format): Int = SINK_FORMAT_SUPPORTED_WITH_TRANSCODING

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long = timeUs

    override fun configure(
        inputFormat: Format,
        specifiedBufferSize: Int,
        outputChannels: IntArray?
    ) {
        skippingAudioProcessor.setEnabled(true)
        skippingAudioProcessor.configure(AudioProcessor.AudioFormat(inputFormat))
        skippingAudioProcessor.flush()
    }

    override fun play() {
    }

    override fun handleDiscontinuity() {

    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int
    ): Boolean {
        while (!skippingAudioProcessor.isEnded) {
            val outBuf = skippingAudioProcessor.output
            if (outBuf.hasRemaining()) {
                onPcmBuffer.invoke(outBuf)
            }
            if (!buffer.hasRemaining()) {
                break
            }
            skippingAudioProcessor.queueInput(buffer)
        }
        timeUs += presentationTimeUs
        return true
    }

    override fun playToEndOfStream() {
        skippingAudioProcessor.queueEndOfStream()
        while (!skippingAudioProcessor.isEnded) {
            val outBuf = skippingAudioProcessor.output
            if (outBuf.hasRemaining()) {
                onPcmBuffer.invoke(outBuf)
            }
            skippingAudioProcessor.queueEndOfStream()
        }
    }

    override fun isEnded(): Boolean {
        return skippingAudioProcessor.isEnded
    }

    override fun hasPendingData(): Boolean
    {
        return !skippingAudioProcessor.isEnded
    }

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {

    }

    override fun getPlaybackParameters(): PlaybackParameters = PlaybackParameters(1f)


    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) {

    }

    override fun getSkipSilenceEnabled(): Boolean = true

    override fun setAudioAttributes(audioAttributes: AudioAttributes) {

    }

    override fun getAudioAttributes(): AudioAttributes? = null

    override fun setAudioSessionId(audioSessionId: Int) {

    }

    override fun setAuxEffectInfo(auxEffectInfo: AuxEffectInfo) {

    }

    override fun enableTunnelingV21() {

    }

    override fun disableTunneling() {

    }

    override fun setVolume(volume: Float) {

    }

    override fun pause() {
    }

    override fun flush() {
        timeUs = 0
        skippingAudioProcessor.flush()
    }

    override fun reset() {
        flush()
        skippingAudioProcessor.reset()
    }
}
