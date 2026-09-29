# Video Wallpaper (intro + loop)

1. Install Android Studio (Koala or newer): https://developer.android.com/studio
2. File > Open > select this VideoWallpaper folder. Let Gradle sync.
3. Build > Build Bundle(s) / APK(s) > Build APK(s).
   APK: app/build/outputs/apk/debug/app-debug.apk
4. Copy the APK to your phone and install it (allow "install unknown apps").
5. Open the app: pick the intro video, pick the wallpaper video, tap "Set as live wallpaper".

Tips: use H.264 MP4s, no audio needed, match your phone's resolution (e.g. 1080x2400).

## Build without Android Studio (GitHub)
1. Make a free account at github.com and create a new repository.
2. Upload ALL files/folders inside this folder (including .github) to it.
3. Open the Actions tab > "Build APK" > wait for the green tick.
4. Open the finished run > Artifacts > download VideoWallpaper-apk > unzip > app-debug.apk
