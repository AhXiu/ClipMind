package com.clipmind.android.shizuku;

import android.os.Bundle;

interface IClipboardUserService {
    void configureUserId(int userId);
    Bundle readPrimaryClip();
}
