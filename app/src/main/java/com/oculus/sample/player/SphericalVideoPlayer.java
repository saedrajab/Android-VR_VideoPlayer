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

package com.oculus.sample.player;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Choreographer;
import android.view.GestureDetector;
import android.view.GestureDetector.SimpleOnGestureListener;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;

import static com.oculus.sample.SphericalPlayerActivity.toast;

import com.oculus.sample.gles.EGLRenderTarget;
import com.oculus.sample.gles.GLHelpers;
import com.oculus.sample.gles.SphericalSceneRenderer;

import java.io.IOException;

/**
 * A TextureView that can playback 360 video with support for drag to rotate.
 */
public class SphericalVideoPlayer extends TextureView {
    private static final String TAG = SphericalVideoPlayer.class.getSimpleName();
    private static final String RENDER_THREAD_NAME = "360RenderThread";

    private MediaPlayer videoPlayerInternal;
    private RenderThread renderThread;

    private String videoPath;

    private boolean readyToPlay;

    private class ScrollDeltaHolder {
        float deltaX, deltaY;

        ScrollDeltaHolder(float dx, float dy) {
            deltaX = dx;
            deltaY = dy;
        }
    }

    private SimpleOnGestureListener dragListener = new SimpleOnGestureListener() {
        @Override
        public boolean onDown(MotionEvent e) {
            return true;
        }

        @Override
        public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
            if (renderThread == null) {
                return false;
            }

            Message msg = Message.obtain();
            msg.what = RenderThread.MSG_ON_SCROLL;
            msg.obj = new ScrollDeltaHolder(distanceX, distanceY);
            renderThread.handler.sendMessage(msg);
            return true;
        }
    };

    private GestureDetector gestureDetector;

    public SphericalVideoPlayer(Context context) {
        this(context, null);
    }

    public SphericalVideoPlayer(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public SphericalVideoPlayer(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        gestureDetector = new GestureDetector(getContext(), dragListener);

        setOnTouchListener(new OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                return gestureDetector.onTouchEvent(event);
            }
        });
    }

    public void initRenderThread(SurfaceTexture surface, int width, int height) {
        renderThread = new RenderThread(RENDER_THREAD_NAME);
        renderThread.start();

        Message msg = Message.obtain();
        msg.what = RenderThread.MSG_SURFACE_AVAILABLE;
        msg.obj = surface;
        msg.arg1 = width;
        msg.arg2 = height;
        renderThread.handler.sendMessage(msg);
    }

    public void setVideoURIPath(String path) {
        videoPath = path;
    }

    public void playWhenReady() {
        // Wait for render surface creation to start preparing the video.
        readyToPlay = true;
    }

    private void prepareVideo(String videoPath) {
        if (renderThread == null) {
            throw new IllegalStateException("RenderThread has not been initialized");
        }

        if (TextUtils.isEmpty(videoPath)) {
            throw new RuntimeException("Cannot begin playback: video path is empty");
        }

        try {
            videoPlayerInternal = new MediaPlayer();
            videoPlayerInternal.setSurface(renderThread.getVideoDecodeSurface());
            videoPlayerInternal.setAudioStreamType(AudioManager.STREAM_MUSIC);
            videoPlayerInternal.setDataSource(getContext(), Uri.parse(videoPath), null);
            videoPlayerInternal.setLooping(true);

            toast(getContext(), "Preparing video...");

            videoPlayerInternal.setOnPreparedListener(
                    new MediaPlayer.OnPreparedListener() {
                        @Override
                        public void onPrepared(MediaPlayer mp) {
                            toast(getContext(), "Prepared video");
                            play();
                        }
                    });
            videoPlayerInternal.setOnBufferingUpdateListener(
                    new MediaPlayer.OnBufferingUpdateListener() {
                        @Override
                        public void onBufferingUpdate(MediaPlayer mp, int percent) {
                            toast(getContext(), "Buffered video" + percent + "%");
                        }
                    });
            videoPlayerInternal.prepareAsync();
        } catch (IOException e) {
            Log.e(TAG, e.toString(), e);
            toast(getContext(), e.toString());
        }
    }

    public void play() {
        if (!videoPlayerInternal.isPlaying()) {
            videoPlayerInternal.start();
        }
    }

    public void releaseResources() {
        renderThread.handler.sendEmptyMessage(RenderThread.MSG_SURFACE_DESTROYED);
    }

    /**
     * RenderThread waits for the SphericalVideoPlayer's SurfaceTexture to be
     * available then sets up a GL rendering context, creates an external
     * texture for the video decoder to output to, asks for vsync updates from
     * the Choreographer, attaches a frame available listener the video decode
     * SurfaceTexture, then begins video playback.
     *
     * Drag events from the main thread will be forwarded to the RenderThread's
     * message queue so that it may update the view state.
     *
     * SphericalSceneRenderer draws the 360 video scene each frame using the
     * latest latched video texture frame.
     */
    private class RenderThread extends HandlerThread {
        private static final int MSG_SURFACE_AVAILABLE = 0x1;
        private static final int MSG_VSYNC = 0x2;
        private static final int MSG_FRAME_AVAILABLE = 0x3;
        private static final int MSG_SURFACE_DESTROYED = 0x4;
        private static final int MSG_ON_SCROLL = 0x5;

        private static final float FOVY = 70f;
        private static final float Z_NEAR = 1f;
        private static final float Z_FAR = 1000f;
        private static final float DRAG_FRICTION = 0.1f;
        private static final float INITIAL_PITCH_DEGREES = 90.f;

        private Handler handler;
        private Choreographer.FrameCallback frameCallback = new ChoreographerCallback();

        private EGLRenderTarget eglRenderTarget;
        private SurfaceTexture videoSurfaceTexture;
        private int videoDecodeTextureId = -1;

        private float[] videoTextureMatrix = new float[16];
        private float[] modelMatrix = new float[16];
        private float[] viewMatrix = new float[16];
        private float[] projectionMatrix = new float[16];

        private float[] camera = new float[3];

        private float lon;
        private float lat;

        private boolean frameAvailable;
        private boolean pendingCameraUpdate;

        private SphericalSceneRenderer renderer;

        private class ChoreographerCallback implements Choreographer.FrameCallback {
            @Override
            public void doFrame(long frameTimeNanos) {
                handler.sendEmptyMessage(MSG_VSYNC);
            }
        }

        public RenderThread(String name) {
            super(name);
            eglRenderTarget = new EGLRenderTarget();
        }

        @Override
        public synchronized void start() {
                super.start();

                handler = new Handler(getLooper()) {
                    @Override
                    public void handleMessage(Message msg) {
                        switch (msg.what) {
                            case MSG_SURFACE_AVAILABLE:
                                onSurfaceAvailable((SurfaceTexture)msg.obj, msg.arg1, msg.arg2);
                                break;
                            case MSG_VSYNC:
                                onVSync();
                                break;
                            case MSG_FRAME_AVAILABLE:
                                onFrameAvailable();
                                break;
                           case MSG_SURFACE_DESTROYED:
                               onSurfaceDestroyed();
                               break;
                           case MSG_ON_SCROLL:
                               onScroll((ScrollDeltaHolder)msg.obj);
                               break;
                        }
                    }
                };
        }

        private Surface getVideoDecodeSurface() {
            if (!eglRenderTarget.hasValidContext()) {
                throw new IllegalStateException(
                        "Cannot get video decode surface without GL context");
            }

            videoDecodeTextureId = GLHelpers.generateExternalTexture();
            videoSurfaceTexture = new SurfaceTexture(videoDecodeTextureId);

            videoSurfaceTexture.setOnFrameAvailableListener(
                    new SurfaceTexture.OnFrameAvailableListener() {
                        @Override
                        public void onFrameAvailable(SurfaceTexture surfaceTexture) {
                            handler.sendEmptyMessage(RenderThread.MSG_FRAME_AVAILABLE);
                        }
                    });
            return new Surface(videoSurfaceTexture);
        }

        private void onSurfaceAvailable(SurfaceTexture surfaceTexture, int width, int height) {
            Log.d(TAG, "onSurfaceAvailable w: " + width + " h: " + height);

            eglRenderTarget.createRenderSurface(surfaceTexture);

            Choreographer.getInstance().postFrameCallback(frameCallback);

            GLES20.glViewport(0, 0, width, height);
            GLHelpers.checkGlError("glViewport");

            float aspectRatio = (float) width / height;
            Matrix.perspectiveM(projectionMatrix, 0, FOVY, aspectRatio, Z_NEAR, Z_FAR);
            Matrix.setIdentityM(viewMatrix, 0);
            // Apply initial rotation
            Matrix.setRotateM(modelMatrix, 0, INITIAL_PITCH_DEGREES, 1, 0, 0);

            GLES20.glClearColor(1.0f, 0.f, 0.f, 1.f);

            renderer = new SphericalSceneRenderer(getContext());

            if (readyToPlay) {
                prepareVideo(videoPath);
            }
        }

        private void onVSync() {
            if (!eglRenderTarget.hasValidContext()) {
                return;
            }

            Choreographer.getInstance().postFrameCallback(frameCallback);

            // We only redraw when there's a new video frame or if a drag event happened.
            if (!frameAvailable && !pendingCameraUpdate) {
                return;
            }

            eglRenderTarget.makeCurrent();
            // Have to be sure to balance onFrameAvailable and updateTexImage calls so that
            // the internal queue buffers will be freed. For this example, we can rely on the
            // display refresh rate being higher or equal to the video frame rate (sample is 30fps).
            videoSurfaceTexture.updateTexImage();
            videoSurfaceTexture.getTransformMatrix(videoTextureMatrix);

            updateCamera();

            renderer.onDrawFrame(
                    videoDecodeTextureId,
                    videoTextureMatrix,
                    modelMatrix,
                    viewMatrix,
                    projectionMatrix);

            eglRenderTarget.swapBuffers();

            if (frameAvailable) {
                frameAvailable = false;
            }

            if (pendingCameraUpdate) {
                pendingCameraUpdate = false;
            }
        }

        private void updateCamera() {
            lat = Math.max(-85, Math.min(85, lat));

            float phi = (float)Math.toRadians(90 - lat);
            float theta = (float)Math.toRadians(lon);

            camera[0] = (float)(100.f * Math.sin(phi) * Math.cos(theta));
            camera[1] = (float)(100.f * Math.cos(phi));
            camera[2] = (float)(100.f * Math.sin(phi) * Math.sin(theta));

            Matrix.setLookAtM(
                    viewMatrix, 0,
                    camera[0], camera[1], camera[2],
                    0, 0, 0,
                    0, 1, 0
            );
        }

        private void onFrameAvailable() {
            frameAvailable = true;
        }

        private void onSurfaceDestroyed() {
            if (videoPlayerInternal != null) {
                videoPlayerInternal.stop();
                videoPlayerInternal.release();
                videoPlayerInternal = null;
            }

            if (videoDecodeTextureId != -1) {
                int[] textures = new int[1];
                textures[0] = videoDecodeTextureId;
                GLES20.glDeleteTextures(1, textures, 0);
                videoDecodeTextureId = -1;
            }

            if (videoSurfaceTexture != null) {
                videoSurfaceTexture.release();
                videoSurfaceTexture = null;
                frameAvailable = false;
            }

            pendingCameraUpdate = false;

            eglRenderTarget.release();
            renderer.release();
        }

        private void onScroll(ScrollDeltaHolder deltaHolder) {
            lon = (deltaHolder.deltaX) * DRAG_FRICTION + lon;
            lat = -(deltaHolder.deltaY) * DRAG_FRICTION + lat;
            pendingCameraUpdate = true;
        }
    }
}
