package com.example.video_compress.sink;

import androidx.annotation.NonNull;

public class InvalidOutputFormatException extends RuntimeException {
    InvalidOutputFormatException(@NonNull String detailMessage) {
        super(detailMessage);
    }
}

