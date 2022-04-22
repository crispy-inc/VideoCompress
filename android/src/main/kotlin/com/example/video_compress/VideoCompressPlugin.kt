package com.example.video_compress

import android.content.Context
import android.util.Log
import com.linkedin.android.litr.io.*
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.PluginRegistry.Registrar
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * VideoCompressPlugin
 */
class VideoCompressPlugin : MethodCallHandler, FlutterPlugin {

    private var _context: Context? = null
    private var _channel: MethodChannel? = null
    var channelName = "video_compress"

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        val context = _context
        val channel = _channel

        if (context == null || channel == null) {
            Log.w(TAG, "Calling VideoCompress plugin before initialization")
            return
        }

        when (call.method) {
            "getByteThumbnail" -> {
                val path = call.argument<String>("path")
                val quality = call.argument<Int>("quality")!!
                val position = call.argument<Int>("position")!! // to long
                ThumbnailUtility(channelName).getByteThumbnail(path!!, quality, position.toLong(), result)
            }
            "getFileThumbnail" -> {
                val path = call.argument<String>("path")
                val quality = call.argument<Int>("quality")!!
                val position = call.argument<Int>("position")!! // to long
                ThumbnailUtility("video_compress").getFileThumbnail(context, path!!, quality,
                        position.toLong(), result)
            }
            "getMediaInfo" -> {
                val path = call.argument<String>("path")
                result.success(Utility(channelName).getMediaInfoJson(context, path!!).toString())
            }
            "deleteAllCache" -> {
                result.success(Utility(channelName).deleteAllCache(context, result))
            }
            "setLogLevel" -> {
//                val logLevel = call.argument<Int>("logLevel")!!
//                Logger.setLogLevel(logLevel)
                result.success(true)
            }
            "cancelCompression" -> {
                result.success(false)
            }
            "compressVideo" -> {
                val path = call.argument<String>("path")!!
                val quality = call.argument<Int>("quality")!!
                val deleteOrigin = call.argument<Boolean>("deleteOrigin")!!
                val startTime = call.argument<Int>("startTime")
                val duration = call.argument<Int>("duration")
                val includeAudio = call.argument<Boolean>("includeAudio") ?: true
                val frameRate = call.argument<Int>("frameRate") ?: 30
                val bitrate = call.argument<Int>("bitrate")
                val orientation = call.argument<Int>("orientation") ?: 0

                val tempDir: String = context.getExternalFilesDir("video_compress")!!.absolutePath
                val out = SimpleDateFormat("yyyy-MM-dd hh-mm-ss", Locale.JAPANESE).format(Date())

                val destPath: String = tempDir + File.separator + "VID_" + out + ".mp4"
                val transcoder = Transcoder(context, channelName)
                val requestId = UUID.randomUUID().toString()
                transcoder.transcode(
                    result,
                    channel,
                    requestId,
                    path,
                    destPath,
                    quality,
                    deleteOrigin,
                    startTime,
                    duration,
                    includeAudio,
                    frameRate,
                    bitrate,
                    orientation,
                )
            }
            "compressAudio" -> {
                val path = call.argument<String>("path")!!
                val startTime = call.argument<Int>("startTime")
                val duration = call.argument<Int>("duration")
                val bitrate = call.argument<Int>("bitrate")
                val deleteOrigin = call.argument<Boolean>("deleteOrigin") ?: false

                val tempDir: String = context.getExternalFilesDir("video_compress")!!.absolutePath
                val out = SimpleDateFormat("yyyy-MM-dd hh-mm-ss", Locale.JAPANESE).format(Date())

                val destPath: String = tempDir + File.separator + "VID_" + out + ".mp4"

                val transcoder = Transcoder(context, channelName)
                val requestId = UUID.randomUUID().toString()

                transcoder.transcodeAudio(
                    result,
                    channel,
                    requestId,
                    path,
                    destPath,
                    startTime,
                    duration,
                    bitrate,
                    deleteOrigin,
                )
            }
            else -> {
                result.notImplemented()
            }
        }
    }

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        init(binding.applicationContext, binding.binaryMessenger)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        _channel?.setMethodCallHandler(null)
        _context = null
        _channel = null
    }

    private fun init(context: Context, messenger: BinaryMessenger) {
        val channel = MethodChannel(messenger, channelName)
        channel.setMethodCallHandler(this)
        _context = context
        _channel = channel
    }

    companion object {
        private const val TAG = "video_compress"

        @JvmStatic
        fun registerWith(registrar: Registrar) {
            val instance = VideoCompressPlugin()
            instance.init(registrar.context(), registrar.messenger())
        }
    }

}
