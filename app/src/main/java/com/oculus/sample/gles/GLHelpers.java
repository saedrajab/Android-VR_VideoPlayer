/**
 * Copyright 2016-present, Facebook, Inc.
 * All rights reserved.
 *
 * Licensed under the Creative Commons CC BY-NC 4.0 Attribution-NonCommercial
 * License (the "License"). You may obtain a copy of the License at
 * https://creativecommons.org/licenses/by-nc/4.0/.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.oculus.sample.gles;

import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.util.Log;

public class GLHelpers {
    private static final String TAG = GLHelpers.class.getSimpleName();

    public static int generateExternalTexture() {
        int externalTextureId = -1;
        int[] textures = new int[1];
        try {
            GLES20.glGenTextures(1, textures, 0);
            externalTextureId = textures[0];
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(
                    GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    externalTextureId);
            GLES20.glTexParameterf(
                    GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_MIN_FILTER,
                    GLES20.GL_LINEAR);
            GLES20.glTexParameterf(
                    GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_MAG_FILTER,
                    GLES20.GL_LINEAR);
            GLES20.glTexParameteri(
                    GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_WRAP_S,
                    GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(
                    GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_WRAP_T,
                    GLES20.GL_CLAMP_TO_EDGE);
        } catch (RuntimeException e) {
            Log.e(TAG, e.toString(), e);
            if (externalTextureId != -1) {
                GLES20.glDeleteTextures(1, textures, 0);
            }
            return -1;
        }
        return externalTextureId;
    }

    public static void checkGlError(String op) {
        int error = GLES20.glGetError();
        if (error == GLES20.GL_NO_ERROR) {
            return;
        }
        String msg = op + ": glError 0x" + Integer.toHexString(error);
        Log.e(TAG, msg);
        throw new RuntimeException(msg);
    }
}
