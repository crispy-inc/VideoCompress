package com.example.video_compress.sink;

import android.media.MediaFormat;

import com.otaliastudios.transcoder.common.TrackType;
import com.otaliastudios.transcoder.internal.utils.Logger;
import com.otaliastudios.transcoder.internal.media.MediaFormatConstants;
import com.otaliastudios.transcoder.internal.utils.AvcCsdUtils;
import com.otaliastudios.transcoder.internal.utils.AvcSpsUtils;

import java.nio.ByteBuffer;

import androidx.annotation.NonNull;

class DefaultDataSinkChecks {
    private static final Logger LOG = new Logger("DefaultDataSinkChecks");

    void checkOutputFormat(@NonNull TrackType type, @NonNull MediaFormat format) {
        if (type == TrackType.VIDEO) {
            checkVideoOutputFormat(format);
        } else if (type == TrackType.AUDIO) {
            checkAudioOutputFormat(format);
        }
    }

    private void checkVideoOutputFormat(@NonNull MediaFormat format) {
        String mime = format.getString(MediaFormat.KEY_MIME);
        // Refer: http://developer.android.com/guide/appendix/media-formats.html#core
        // Refer: http://en.wikipedia.org/wiki/MPEG-4_Part_14#Data_streams
        if (!MediaFormatConstants.MIMETYPE_VIDEO_AVC.equals(mime)) {
            throw new InvalidOutputFormatException("Video codecs other than AVC is not supported, actual mime type: " + mime);
        }

        // The original lib by ypresto was throwing when detected a non-baseline profile.
        // But recent Android versions appear to have at least Main Profile support, although it's still
        // not enforced by Android CDD. See 2016 comment by Google employee (about decoding):
        // https://github.com/google/ExoPlayer/issues/1952#issuecomment-254206222
        // So instead of throwing, we prefer to just log the profile name and let the device try to handle.
        ByteBuffer spsBuffer = AvcCsdUtils.getSpsBuffer(format);
        byte profileIdc = AvcSpsUtils.getProfileIdc(spsBuffer);
        String profileName = AvcSpsUtils.getProfileName(profileIdc);
        if (profileIdc == AvcSpsUtils.PROFILE_IDC_BASELINE) {
            LOG.i("Output H.264 profile: " + profileName);
        } else {
            LOG.w("Output H.264 profile: " + profileName + ". This might not be supported.");
        }
    }

    private void checkAudioOutputFormat(@NonNull MediaFormat format) {
        String mime = format.getString(MediaFormat.KEY_MIME);
        if (!MediaFormatConstants.MIMETYPE_AUDIO_AAC.equals(mime)) {
            throw new InvalidOutputFormatException("Audio codecs other than AAC is not supported, actual mime type: " + mime);
        }
    }
}
