360 Video Player for Android
============================

Android application that demonstrates how to
playback 360 video, specifically equirectangular video, using MediaPlayer,
TextureView, and OpenGL ES. Touch and drag is supported to adjust the yaw and
the pitch to see more of the 360 video.

The launch activity will load a sample 360 video included in:
res/raw/sample360.mp4

Today's 360 cameras generally either output directly in equirectangular format
or provide software to convert to equirectangular. Facebook ingests 360 videos
in equirectangular format.

## Usage

### Android Studio

Open the directory in Android Studio and the gradle project should be imported.
Run & Debug options should allow you to build the project.

### Command Line

Assure you have a local.properties file in the top level directory with:
*sdk.dir=/path/to/your/Android/sdk*


    $ gradlew installDebug
    $ adb shell am start -n com.oculus.sample/.SphericalPlayerActivity
