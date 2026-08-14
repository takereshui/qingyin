# Third-Party Notices

## jaudiotagger-android

This application includes `jaudiotagger-android.jar`, compiled from the Android-compatible source of [hexise/jaudiotagger-android](https://github.com/hexise/jaudiotagger-android) at commit `112a415b7d7b9833085c4d411052b0542ed630fd`.

The upstream project is a mobile-compatible adaptation of JAudioTagger. It removes desktop-only `javax.imageio` and `java.awt` calls and provides tag and artwork writing for Android. Qingyin applies a minimal local patch to `AndroidArtwork`: image dimensions for FLAC/Ogg picture blocks are read with Android `BitmapFactory` in bounds-only mode, instead of throwing an unsupported-operation error.

Upstream project: https://github.com/hexise/jaudiotagger-android

License: GNU Lesser General Public License (LGPL), as stated by the upstream project.

Source code and any license terms remain available from the upstream project.
