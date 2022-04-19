package com.example.video_compress

import android.content.Context
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.util.Log
import android.util.Size
import com.example.video_compress.resize.Resizer
import com.linkedin.android.litr.MediaTransformer
import com.linkedin.android.litr.TrackTransform
import com.linkedin.android.litr.TransformationListener
import com.linkedin.android.litr.TransformationOptions
import com.linkedin.android.litr.analytics.TrackTransformationInfo
import com.linkedin.android.litr.codec.MediaCodecDecoder
import com.linkedin.android.litr.codec.MediaCodecEncoder
import com.linkedin.android.litr.io.*
import com.linkedin.android.litr.render.GlVideoRenderer
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.PluginRegistry.Registrar
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Future

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

                val mediaRange = if (startTime != null || duration != null) {
                    val start = (startTime ?: 0).toLong() * 1000
                    val end = if (duration != null) {
                        start + duration.toLong() * 1000
                    } else {
                        Long.MAX_VALUE
                    }
                    MediaRange(start, end)
                } else {
                    MediaRange(0, Long.MAX_VALUE)
                }

                val mediaSource: MediaSource = MediaExtractorMediaSource(
                    context.applicationContext,
                    Uri.parse(path),
                    mediaRange
                )
                val resizer = getTargetSizer(mediaSource = mediaSource, quality = quality)
                if (resizer == null) {
                    result.error(TAG, "target size is null", "")
                    return
                }

                val resizedVideoSize = mediaSource
                    .getVideoSize()
                    ?.resize(resizer = resizer)
                    ?.rotate(orientation)
                if (resizedVideoSize == null) {
                    result.error(TAG, "Video size is null.", "")
                    return
                }

                val videoMediaFormat = MediaFormat.createVideoFormat(
                    MediaFormat.MIMETYPE_VIDEO_AVC,
                    resizedVideoSize.width,
                    resizedVideoSize.height
                )

                if (orientation != 0) {
                    val videoRotation = (360 + orientation - mediaSource.orientationHint) % 360
                    val rotation = when(videoRotation) {
                        90 -> {
                            270
                        }
                        270 -> {
                            90
                        }
                        else -> {
                            videoRotation
                        }
                    }
                    videoMediaFormat.setInteger(KEY_ROTATION, rotation)
                }

                val audioMediaFormat = mediaSource.getAudioFormat(96 * 1024)

                if (bitrate != null) {
                    videoMediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                }

                videoMediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                videoMediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)

                val transformationOptionsBuilder = TransformationOptions.Builder()
                val requestId = UUID.randomUUID().toString()

                val trackCount = if (audioMediaFormat != null && includeAudio) 2 else 1

                val mediaTarget = MediaMuxerMediaTarget(
                    context.applicationContext,
                    Uri.fromFile(File(destPath)),
                    trackCount,
                    mediaSource.orientationHint,
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                )

                val options = transformationOptionsBuilder.build()
                val mediaTransformer = MediaTransformer(context.applicationContext)
                val videoIndex = mediaSource.getVideoTrackIndex()
                val trackTransformBuilder = TrackTransform
                    .Builder(mediaSource, videoIndex, mediaTarget)
                    .setTargetTrack(0)
                trackTransformBuilder.setDecoder(MediaCodecDecoder())
                    .setRenderer(GlVideoRenderer(options.videoFilters))
                    .setEncoder(MediaCodecEncoder())
                    .setTargetFormat(videoMediaFormat)
                val trackTransforms = ArrayList<TrackTransform>()
                trackTransforms.add(trackTransformBuilder.build())
                val audioTrackIndex = mediaSource.getAudioTrackIndex()
                if (audioMediaFormat != null && includeAudio) {
                    val audioTrackTransformBuilder = TrackTransform
                        .Builder(mediaSource, audioTrackIndex, mediaTarget)
                        .setTargetTrack(1)
                        .setDecoder(MediaCodecDecoder())
                        .setEncoder(MediaCodecEncoder())
                        .setTargetFormat(audioMediaFormat)
                    trackTransforms.add(audioTrackTransformBuilder.build())
                }

                mediaTransformer.transform(
                    requestId,
                    trackTransforms,
                    object : TransformationListener {
                        override fun onStarted(id: String) {
                        }

                        override fun onProgress(id: String, progress: Float) {
                            channel.invokeMethod("updateProgress", progress.toDouble() * 100.00)
                        }

                        override fun onCancelled(
                            id: String,
                            trackTransformationInfos: MutableList<TrackTransformationInfo>?
                        ) {
                            result.success(null)
                        }

                        override fun onCompleted(
                            id: String,
                            trackTransformationInfos: MutableList<TrackTransformationInfo>?
                        ) {
                            channel.invokeMethod("updateProgress", 100.00)
                            val json = Utility(channelName).getMediaInfoJson(context, destPath)
                            json.put("isCancel", false)
                            result.success(json.toString())
                            if (deleteOrigin) {
                                File(path).delete()
                            }
                        }

                        override fun onError(
                            id: String,
                            cause: Throwable?,
                            trackTransformationInfos: MutableList<TrackTransformationInfo>?
                        ) {
                            result.error(
                                "video_compress_failed",
                                cause?.message ?: "",
                                cause?.localizedMessage ?: ""
                            )
                        }
                    },
                    options.granularity,
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

    private fun getTargetSizer(mediaSource: MediaSource, quality: Int): Resizer? {
        val index = mediaSource.getVideoTrackIndex()
        if (index < 0) {
            return null
        }
        return getResizer(quality)
    }

    private fun getResizer(quality: Int): Resizer? {
        when (quality) {
            0 -> {
                return Resizer(720)
            }
            1 -> {
                return Resizer(360)
            }
            2 -> {
                return Resizer(640)
            }
            3 -> {
                return Resizer(720, 1280)
            }
            4 -> {
                return Resizer(480, 640)
            }
            5 -> {
                return Resizer(540, 960)
            }
            6 -> {
                return Resizer(720, 1280)
            }
            7 -> {
                return Resizer( 1080, 1920)
            }
        }
        return null
    }

    companion object {
        private const val TAG = "video_compress"

        private val KEY_ROTATION = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            MediaFormat.KEY_ROTATION
        } else {
            "rotation-degrees"
        }

        @JvmStatic
        fun registerWith(registrar: Registrar) {
            val instance = VideoCompressPlugin()
            instance.init(registrar.context(), registrar.messenger())
        }
    }

}

private fun Size.rotate(orientation: Int): Size {
    if (orientation == 90 || orientation == 270) {
        return Size(height, width)
    }
    return this
}

private fun Size.resize(resizer: Resizer): Size {
    return resizer.getOutputSize(this)
}

private fun MediaSource.getVideoTrackIndex(): Int {
    return getTrackFirstIndex(type = "video")
}

private fun MediaSource.getAudioTrackIndex(): Int {
    return getTrackFirstIndex(type = "audio")
}

private fun MediaSource.getTrackFirstIndex(type: String): Int {
    val count = trackCount
    for (i in 0..count) {
        val trackFormat = getTrackFormat(i)
        val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: continue
        if (mime.startsWith(type)) {
            return i
        }
    }
    return -1
}

private fun MediaSource.getAudioFormat(maxBitrate: Int): MediaFormat? {
    val index = getAudioTrackIndex()
    if (index < 0) {
        return null
    }
    val format = getTrackFormat(index)
    val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
    val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
    val mediaFormat = MediaFormat.createAudioFormat(
        MediaFormat.MIMETYPE_AUDIO_AAC,
        sampleRate,
        channelCount
    )

    val bitrate = if (format.containsKey(MediaFormat.KEY_BIT_RATE)) {
        format.getInteger(MediaFormat.KEY_BIT_RATE)
    } else {
        Int.MAX_VALUE
    }
    if (bitrate > maxBitrate) {
        mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, maxBitrate)
    } else {
        mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
    }
    return mediaFormat

}

private fun MediaSource.getVideoSize(): Size? {
    val index = getVideoTrackIndex()
    if (index < 0) {
        return null
    }
    val trackFormat = getTrackFormat(index)
    if (
        !trackFormat.containsKey(MediaFormat.KEY_WIDTH) ||
        !trackFormat.containsKey(MediaFormat.KEY_HEIGHT)
    ) {
        return null
    }
    val width = trackFormat.getInteger(MediaFormat.KEY_WIDTH)
    val height = trackFormat.getInteger(MediaFormat.KEY_HEIGHT)
    return Size(width, height)
}
