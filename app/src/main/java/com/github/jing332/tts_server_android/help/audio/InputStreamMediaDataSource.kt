package com.github.jing332.tts_server_android.help.audio

import android.media.MediaDataSource
import android.util.Log
import okio.buffer
import okio.source
import java.io.InputStream
import kotlin.math.max
import kotlin.math.min

class InputStreamMediaDataSource(private val inputStream: InputStream) : MediaDataSource() {
    companion object {
        const val TAG = "InputStreamDataSource"
    }

    private val bufferedInputStream = inputStream.source().buffer()
    private var cache = ByteArray(2048)
    private var limit = 0L
    private var finishedRead = false


    override fun close() {
        Log.d(TAG, "close")
        limit = 0
        bufferedInputStream.close()
    }

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        Log.d(TAG, "readAt: pos=$position, offset=$offset, size=$size")
        kotlin.runCatching {
            val newSize = position.toInt() + size
            if (!finishedRead && newSize > cache.size) {
                cache = cache.copyOf(1 shl (Integer.SIZE - Integer.numberOfLeadingZeros((newSize * 2) - 1)))
            }
            while (!finishedRead && limit <= position) {
                val readSize = bufferedInputStream.read(cache, limit.toInt(), size).apply {
                    Log.d(TAG, "readAt: readLen=$this")
                }
                if (readSize == -1) {
                    finishedRead = true
                } else {
                    limit += readSize
                }
            }
            val bytesToRead = max(min(limit, position + size) - position, -1)
            Log.d(TAG, "readAt: bytesToRead=$bytesToRead")
            if (bytesToRead > 0) {
                cache.copyInto(buffer, offset, position.toInt(), position.toInt() + bytesToRead.toInt())
            } else {
                return -1
            }

            return bytesToRead.toInt()

        }.onFailure {
            Log.d(TAG, it.stackTraceToString())
        }
        return -1
    }

    override fun getSize(): Long {
        return if (finishedRead) limit else -1
    }

}