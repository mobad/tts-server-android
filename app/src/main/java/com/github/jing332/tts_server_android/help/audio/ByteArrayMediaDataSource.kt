package com.github.jing332.tts_server_android.help.audio

import android.media.MediaDataSource
import android.util.Log

class ByteArrayMediaDataSource(var data: ByteArray) : MediaDataSource() {

    companion object {
        const val TAG = "ByteArrayMediaDataSource"
    }
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        Log.e(TAG, "readAt: pos=$position, offset=$offset, size=$size")
        if (position >= data.size) return -1

        val endPosition = (position + size).toInt()
        val size2 = if (endPosition > data.size) size - (endPosition - data.size) else size

        System.arraycopy(data, position.toInt(), buffer, offset, size2)
        return size2
    }

    override fun getSize(): Long {
        return data.size.toLong()
    }

    override fun close() {
    }
}