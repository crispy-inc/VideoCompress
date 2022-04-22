package com.example.video_compress

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import io.flutter.plugin.common.MethodChannel
import org.json.JSONObject
import java.io.File

class Utility(private val channelName: String) {

    fun isLandscapeImage(orientation: Int) = orientation != 90 && orientation != 270

    fun deleteFile(file: File) {
        if (file.exists()) {
            file.delete()
        }
    }

    fun timeStrToTimestamp(time: String): Long {
        val timeArr = time.split(":")
        val hour = Integer.parseInt(timeArr[0])
        val min = Integer.parseInt(timeArr[1])
        val secArr = timeArr[2].split(".")
        val sec = Integer.parseInt(secArr[0])
        val mSec = Integer.parseInt(secArr[1])

        val timeStamp = (hour * 3600 + min * 60 + sec) * 1000 + mSec
        return timeStamp.toLong()
    }

    fun getMediaInfoJson(context: Context, path: String): JSONObject {
        val file = File(path)
        val retriever = MediaMetadataRetriever()

        retriever.setDataSource(context, Uri.fromFile(file))
        val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
        val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: ""
        val author = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_AUTHOR) ?: ""
        val widthStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
        val heightStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
        val bitrateStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
        val hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) != null
        val duration = durationStr?.toLongOrNull()
        var width = widthStr?.toIntOrNull()
        var height = heightStr?.toIntOrNull()
        val bitrate = bitrateStr?.toIntOrNull()
        val filesize = file.length()
        val orientation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
        val ori = orientation?.toIntOrNull()
        if (ori != null && isLandscapeImage(ori)) {
            val tmp = width
            width = height
            height = tmp
        }

        retriever.release()

        val json = JSONObject()

        json.put("path", path)
        json.put("title", title)
        json.put("author", author)
        json.put("width", width)
        json.put("height", height)
        json.put("duration", duration)
        json.put("filesize", filesize)
        json.put("has_audio", hasAudio)
        json.put("bitrate", bitrate)
        if (ori != null) {
            json.put("orientation", ori)
        }

        return json
    }

    fun getBitmap(path: String, position: Long, result: MethodChannel.Result): Bitmap {
        var bitmap: Bitmap? = null
        val retriever = MediaMetadataRetriever()

        try {
            retriever.setDataSource(path)
            bitmap = retriever.getFrameAtTime(position, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (ex: IllegalArgumentException) {
            result.error(channelName, "Assume this is a corrupt video file", null)
        } catch (ex: RuntimeException) {
            result.error(channelName, "Assume this is a corrupt video file", null)
        } finally {
            try {
                retriever.release()
            } catch (ex: RuntimeException) {
                result.error(channelName, "Ignore failures while cleaning up", null)
            }
        }

        if (bitmap == null) result.success(emptyArray<Int>())

        val width = bitmap!!.width
        val height = bitmap.height
        val max = Math.max(width, height)
        if (max > 512) {
            val scale = 512f / max
            val w = Math.round(scale * width)
            val h = Math.round(scale * height)
            bitmap = Bitmap.createScaledBitmap(bitmap, w, h, true)
        }

        return bitmap!!
    }

    fun getFileNameWithGifExtension(path: String): String {
        val file = File(path)
        var fileName = ""
        val gifSuffix = "gif"
        val dotGifSuffix = ".$gifSuffix"

        if (file.exists()) {
            val name = file.name
            fileName = name.replaceAfterLast(".", gifSuffix)

            if (!fileName.endsWith(dotGifSuffix)) {
                fileName += dotGifSuffix
            }
        }
        return fileName
    }

    fun deleteAllCache(context: Context, result: MethodChannel.Result) {
        val dir = context.getExternalFilesDir("video_compress")
        result.success(dir?.deleteRecursively())
    }
}