package com.github.jing332.tts_server_android.ui.systts.plugin

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.jing332.tts_server_android.data.entities.plugin.Plugin
import com.github.jing332.tts_server_android.help.plugin.EditUiJsEngine
import com.github.jing332.tts_server_android.help.plugin.LogOutputter
import com.github.jing332.tts_server_android.model.tts.PluginTTS
import com.github.jing332.tts_server_android.ui.LogLevel
import com.github.jing332.tts_server_android.util.readableString
import com.github.jing332.tts_server_android.util.rootCause
import com.script.ScriptException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import tts_server_lib.PluginSyncServer

class PluginEditViewModel : ViewModel() {
    companion object {
        private const val TAG = "PluginEditViewModel"
    }

    lateinit var pluginEngine: EditUiJsEngine
    internal lateinit var pluginInfo: Plugin private set
    internal lateinit var pluginTTS: PluginTTS private set

    private var server: PluginSyncServer? = null

    private val _updateCodeLiveData = MutableLiveData<String>()

    val codeLiveData: LiveData<String>
        get() = _updateCodeLiveData

    fun init(plugin: Plugin, defaultCode: String) {
        pluginInfo = plugin
        pluginTTS = PluginTTS(plugin = pluginInfo)
        updateTTS(pluginTTS)

        _updateCodeLiveData.postValue(pluginInfo.code.ifBlank { defaultCode })
    }

    fun updateTTS(tts: PluginTTS) {
        pluginTTS = tts
        pluginEngine = EditUiJsEngine(tts)
    }

   /* // 代码同步服务器
    fun startSyncServer() {
        server = tts_server_lib.PluginSyncServer()
        server?.init(object : PluginCodeSyncServerCallback {
            override fun log(level: Int, msg: String?) {
                Log.i(TAG, "$level $msg")
            }

            override fun pull(): String {
                return pluginEngine.pluginTts.plugin?.code!!
            }

            override fun push(code: String?) {
                _updateCodeLiveData.postValue(code!!)
            }

        })
        server?.start(4566)
    }

    override fun onCleared() {
        super.onCleared()

        server?.close()
        server = null
    }*/

    fun debug() {
        val plugin = try {
            pluginEngine.evalPluginInfo()
        } catch (e: Exception) {
            writeErrorLog(e)
            return
        }
        LogOutputter.writeLine(plugin.toString().replace(", ", "\n"))

        viewModelScope.launch(Dispatchers.IO) {
            kotlin.runCatching {
                val sampleRate = pluginEngine.getSampleRate(pluginTTS.locale, pluginTTS.voice)
                LogOutputter.writeLine("采样率: $sampleRate")
            }.onFailure {
                writeErrorLog(it)
            }

            kotlin.runCatching {
                pluginEngine.eval()
                val audio = pluginEngine.getAudio(
                    "测试文本", pluginTTS.locale, pluginTTS.voice, pluginTTS.rate,
                    pluginTTS.volume, pluginTTS.pitch
                )
                if (audio == null)
                    LogOutputter.writeLine("\n音频为空！", LogLevel.ERROR)
                else
                    LogOutputter.writeLine("\n音频大小: ${bytesToHumanReadable(audio.size.toLong())}")

            }.onFailure {
                writeErrorLog(it)
            }

        }
    }

    private fun writeErrorLog(t: Throwable) {
        val errStr = if (t is ScriptException) {
            "第 ${t.lineNumber} 行错误：${t.rootCause?.message ?: t}"
        } else {
            t.message + "(${t.readableString})"
        }
        LogOutputter.writeLine(errStr, LogLevel.ERROR)
    }

    fun bytesToHumanReadable(bytes: Long): String {
        val kb = 1024
        val mb = kb * 1024
        val gb = mb * 1024

        return when {
            bytes < mb -> "${"%.2f".format(bytes.toDouble() / kb)} KB"
            bytes < gb -> "${"%.2f".format(bytes.toDouble() / mb)} MB"
            else -> "$bytes B"
        }
    }
}