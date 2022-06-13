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
import com.linkedin.android.litr.io.MediaExtractorMediaSource
import com.linkedin.android.litr.io.MediaMuxerMediaTarget
import com.linkedin.android.litr.io.MediaRange
import com.linkedin.android.litr.io.MediaSource
import com.linkedin.android.litr.render.AudioRenderer
import com.linkedin.android.litr.render.GlVideoRenderer
import java.io.File
import java.util.*
import java.util.concurrent.TimeUnit
import io.flutter.plugin.common.MethodChannel

class Transcoder(private val context: Context, private val channelName: String) {

    private val mediaTransformer = MediaTransformer(context.applicationContext)

    companion object {
        private const val TAG = "VideoCompressTranscoder"
        private val KEY_ROTATION = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            MediaFormat.KEY_ROTATION
        } else {
            "rotation-degrees"
        }
        private const val MAX_AUDIO_BITRATE = 96000
    }
    fun transcode(
        result: MethodChannel.Result,
        channel: MethodChannel,
        requestId: String,
        path: String,
        destPath: String,
        quality: Int,
        deleteOrigin: Boolean,
        startTime: Int?,
        duration: Int?,
        includeAudio: Boolean,
        frameRate: Int,
        bitrate: Int?,
        orientation: Int,
    ) {
        val mediaRange = getMediaRange(startTime, duration)
        val mediaSource = MediaExtractorMediaSource(
            context.applicationContext,
            Uri.parse(path),
            mediaRange
        )
        val audioTrackIndex = mediaSource.getAudioTrackIndex()
        mediaSource.release()

        if (includeAudio && audioTrackIndex >= 0 && mediaRange.start != 0L) {
            trimming(
                requestId,
                path,
                destPath,
                mediaRange,
                {
                    transcodeVideo(
                        result,
                        channel,
                        requestId,
                        it,
                        destPath,
                        quality,
                        true,
                        MediaRange(0, Long.MAX_VALUE),
                        includeAudio,
                        frameRate,
                        bitrate,
                        orientation,
                    )
                },
                {
                    result.error(
                        "video_compress_failed",
                        it?.message ?: "",
                        it?.localizedMessage ?: ""
                    )
                }
            )
        } else {
            transcodeVideo(
                result,
                channel,
                requestId,
                path,
                destPath,
                quality,
                deleteOrigin,
                mediaRange,
                includeAudio,
                frameRate,
                bitrate,
                orientation,
            )
        }
    }

    private fun transcodeVideo(
        result: MethodChannel.Result,
        channel: MethodChannel,
        requestId: String,
        path: String,
        destPath: String,
        quality: Int,
        deleteOrigin: Boolean,
        mediaRange: MediaRange,
        includeAudio: Boolean,
        frameRate: Int,
        bitrate: Int?,
        orientation: Int,
    ) {
        val mediaSource = MediaExtractorMediaSource(
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
            val rotation = when(val videoRotation = (360 + orientation - mediaSource.orientationHint) % 360) {
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

        if (bitrate != null) {
            videoMediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        }

        videoMediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
        videoMediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)

        val transformationOptionsBuilder = TransformationOptions.Builder()
            .setSourceMediaRange(mediaRange)

        val audioMediaFormat = mediaSource.getAudioFormat(MAX_AUDIO_BITRATE)
        val trackCount = if (audioMediaFormat != null && includeAudio) 2 else 1

        val mediaTarget = MediaMuxerMediaTarget(
            context.applicationContext,
            Uri.fromFile(File(destPath)),
            trackCount,
            mediaSource.orientationHint,
            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
        )

        val options = transformationOptionsBuilder.build()

        val trackTransforms = ArrayList<TrackTransform>()

        if (audioMediaFormat != null && includeAudio) {
            val audioTrackIndex = mediaSource.getAudioTrackIndex()
            val encoder = MediaCodecEncoder()
            val audioTrackTransform = TrackTransform
                .Builder(mediaSource, audioTrackIndex, mediaTarget)
                .setTargetTrack(trackTransforms.size)
                .setTargetFormat(audioMediaFormat)
                .setRenderer(AudioRenderer(encoder, options.audioFilters))
                .setDecoder(MediaCodecDecoder())
                .setEncoder(encoder)
                .build()
            trackTransforms.add(audioTrackTransform)
        }

        val videoIndex = mediaSource.getVideoTrackIndex()
        val videoTrackTransform = TrackTransform
            .Builder(mediaSource, videoIndex, mediaTarget)
            .setTargetTrack(trackTransforms.size)
            .setTargetFormat(videoMediaFormat)
            .setRenderer(GlVideoRenderer(options.videoFilters))
            .setDecoder(MediaCodecDecoder())
            .setEncoder(MediaCodecEncoder())
            .build()

        trackTransforms.add(videoTrackTransform)

        mediaTransformer.transform(
            requestId,
            trackTransforms,
            object: TransformationListener {
                override fun onStarted(id: String) {
                }

                override fun onCancelled(id: String, trackTransformationInfos: MutableList<TrackTransformationInfo>?) {
                    result.success(null)
                }

                override fun onCompleted(id: String, trackTransformationInfos: MutableList<TrackTransformationInfo>?) {
                    channel.invokeMethod("updateProgress", 100.00)
                    val json = Utility(channelName).getMediaInfoJson(context, destPath)
                    json.put("isCancel", false)
                    result.success(json.toString())
                    if (deleteOrigin) {
                        File(path).delete()
                    }
                }
                override fun onProgress(id: String, progress: Float) {
                    channel.invokeMethod("updateProgress", progress.toDouble() * 100.00)
                }
                override fun onError(id: String, cause: Throwable?, trackTransformationInfos: MutableList<TrackTransformationInfo>?) {
                    result.error(
                        "video_compress_failed",
                        cause?.message ?: "",
                        cause?.localizedMessage ?: ""
                    )
                }
            },
            MediaTransformer.GRANULARITY_DEFAULT,
        )
    }

    fun transcodeAudio(
        result: MethodChannel.Result,
        channel: MethodChannel,
        requestId: String,
        path: String,
        destPath: String,
        startTime: Int?,
        duration: Int?,
        bitrate: Int?,
        deleteOrigin: Boolean,
    ) {
        val mediaRange = getMediaRange(startTime, duration)
        val mediaSource = MediaExtractorMediaSource(
            context.applicationContext,
            Uri.parse(path),
            mediaRange
        )

        val trackFormat = mediaSource.getAudioFormat(bitrate ?: Int.MAX_VALUE)
        if (trackFormat == null) {
            result.error(TAG, "audio is not exists.", "")
            return
        }

        val mediaTarget = MediaMuxerMediaTarget(
            context.applicationContext,
            Uri.fromFile(File(destPath)),
            1,
            mediaSource.orientationHint,
            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
        )

        val audioTrackIndex = mediaSource.getAudioTrackIndex()
        val encoder = MediaCodecEncoder()
        val audioTrackTransform = TrackTransform
            .Builder(mediaSource, audioTrackIndex, mediaTarget)
            .setTargetTrack(0)
            .setTargetFormat(trackFormat)
            .setRenderer(AudioRenderer(encoder))
            .setDecoder(MediaCodecDecoder())
            .setEncoder(encoder)
            .build()
        mediaTransformer.transform(
            requestId,
            listOf(audioTrackTransform),
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
            MediaTransformer.GRANULARITY_DEFAULT,
        )
    }

    private fun trimming(
        requestId: String,
        path: String,
        destPath: String,
        mediaRange: MediaRange,
        onCompleted: (path: String) -> Unit,
        onError: (cause: Throwable?) -> Unit,
    ) {
        val trimmingPath = "$destPath.trimming.mp4"
        val mediaSource = MediaExtractorMediaSource(
            context.applicationContext,
            Uri.parse(path),
            mediaRange
        )
        val mediaTarget = MediaMuxerMediaTarget(
            context.applicationContext,
            Uri.fromFile(File(trimmingPath)),
            mediaSource.trackCount,
            mediaSource.orientationHint,
            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
        )
        val count = mediaSource.trackCount
        val transforms = (0 until count).map {
            TrackTransform
                .Builder(mediaSource, it, mediaTarget)
                .setTargetTrack(it)
                .build()
        }
        mediaTransformer.transform(
            requestId,
            transforms,
            object: TransformationListener {
                override fun onStarted(id: String) {
                }

                override fun onCancelled(id: String, trackTransformationInfos: MutableList<TrackTransformationInfo>?) {
                }

                override fun onCompleted(id: String, trackTransformationInfos: MutableList<TrackTransformationInfo>?) {
                    onCompleted(trimmingPath)
                }
                override fun onProgress(id: String, progress: Float) {
                }
                override fun onError(id: String, cause: Throwable?, trackTransformationInfos: MutableList<TrackTransformationInfo>?) {
                    onError(cause)
                }
            },
            MediaTransformer.GRANULARITY_DEFAULT,
        )
    }

    private fun getMediaRange(startTime: Int?, duration: Int?): MediaRange {
        return if (startTime != null || duration != null) {
            val start = TimeUnit.MILLISECONDS.toMicros((startTime ?: 0).toLong())
            val end = if (duration != null) {
                start + TimeUnit.MILLISECONDS.toMicros(duration.toLong())
            } else {
                Long.MAX_VALUE
            }

            MediaRange(
                start,
                end
            )
        } else {
            MediaRange(0, Long.MAX_VALUE)
        }
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
    for (i in 0 until count) {
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
    if (format.containsKey(MediaFormat.KEY_DURATION)) {
        val duration = format.getLong(MediaFormat.KEY_DURATION)
        val sourceDuration = selection.end - selection.start
        if (duration > sourceDuration) {
            mediaFormat.setLong(MediaFormat.KEY_DURATION, sourceDuration)
        } else {
            mediaFormat.setLong(MediaFormat.KEY_DURATION, duration)
        }
    }

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