// WebViewActivity.java — 增强版，集成 NativeBridge.downloadUrl (调用 DownloadManager 绕过 CORS)
package com.zapstore.goapk.runtime;

import android.annotation.SuppressLint;
import android.app.ActionBar;
import android.app.Activity;
import android.app.DownloadManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.media.AudioAttributes;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.MimeTypeMap;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;


import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class WebViewActivity extends Activity {

    private static final String TAG = "goapk";
    private static final String CONFIG_PATH = "config.json";
    private static final String ASSET_HOST = "https://appassets.androidplatform.net";
    private static final String ASSETS_INDEX = ASSET_HOST + "/";
    private static final String ASSET_PREFIX = "www";

    private static final int REQUEST_CODE_PERMISSIONS = 1001;
    private static final int REQUEST_CODE_FILE_CHOOSER = 1002;
    private static final int REQUEST_CODE_LEGACY_STORAGE = 1003;
    private static final int REQUEST_CODE_MEDIA_LIBRARY = 1004;
    private static final int REQUEST_CODE_MUSIC_TREE = 1005;
    private static final int REQUEST_CODE_NOTIFICATIONS = 1006;
    private static final String DOWNLOAD_DIR = Environment.DIRECTORY_MUSIC;
    private static final String DOWNLOAD_SUBDIR = "Molan Light Music";
    private static final String NATIVE_DOWNLOAD_PREFIX = "/__goapk_downloads/";
    private static final String NATIVE_MEDIA_PREFIX = "/__goapk_media/";
    private static final String NATIVE_ALBUM_PREFIX = "/__goapk_album/";
    private static final String NATIVE_DOCUMENT_PREFIX = "/__goapk_document/";
    private static final String CUSTOM_MUSIC_TREES_KEY = "custom_music_trees";
    private static final int MAX_CUSTOM_TREE_SONGS = 8000;
    private static final int MAX_CUSTOM_TREE_DEPTH = 24;
    private static final String MEDIA_CHANNEL_ID = "molan_playback";
    private static final int MEDIA_NOTIFICATION_ID = 16301;
    private static final String ACTION_MEDIA_PLAY = "im.molan.music.PLAY";
    private static final String ACTION_MEDIA_PAUSE = "im.molan.music.PAUSE";
    private static final String ACTION_MEDIA_NEXT = "im.molan.music.NEXT";
    private static final String ACTION_MEDIA_PREV = "im.molan.music.PREV";

    private static final Map<String, String[]> WEBKIT_TO_ANDROID = new HashMap<>();
    static {
        WEBKIT_TO_ANDROID.put(PermissionRequest.RESOURCE_VIDEO_CAPTURE,
            new String[]{"android.permission.CAMERA"});
        WEBKIT_TO_ANDROID.put(PermissionRequest.RESOURCE_AUDIO_CAPTURE,
            new String[]{"android.permission.RECORD_AUDIO"});
    }

    private WebView webView;
    private PermissionRequest pendingPermissionRequest;
    private GeolocationPermissions.Callback pendingGeoCallback;
    private String pendingGeoOrigin;
    private ValueCallback<Uri[]> pendingFileChooser;
    private final Map<String, Uri> customMusicDocuments = new HashMap<>();
    private MediaSession mediaSession;
    private NotificationManager notificationManager;
    private BroadcastReceiver mediaControlReceiver;
    private boolean nativeMediaPlaying;
    private String nativeMediaTitle = "轻音";
    private String nativeMediaArtist = "";
    private long nativeMediaPosition;
    private long nativeMediaDuration;
    private PendingIntent mediaSessionActivityIntent;
    // Local audio is played directly by Android, not through the WebView's HTTP interceptor.
    private MediaPlayer nativeLocalPlayer;
    private String nativeLocalSongId = "";
    private boolean nativeLocalPrepared;
    private String nativeLocalTitle = "轻音";
    private String nativeLocalArtist = "";
    private final Handler nativeLocalHandler = new Handler(Looper.getMainLooper());
    private QQMusicSession qqMusicSession;
    private final Runnable nativeLocalProgressTicker = new Runnable() {
        @Override public void run() {
            MediaPlayer player = nativeLocalPlayer;
            if (player == null || !nativeLocalPrepared) return;
            try {
                long position = player.getCurrentPosition();
                long duration = player.getDuration();
                boolean playing = player.isPlaying();
                updateNativeMediaSession(nativeLocalTitle, nativeLocalArtist, playing, position, duration);
                dispatchNativeLocalPlayerEvent("time", position, duration, "");
                if (playing) nativeLocalHandler.postDelayed(this, 500);
            } catch (Exception e) {
                dispatchNativeLocalPlayerEvent("error", 0, 0, "本地播放器状态读取失败");
            }
        }
    };

    @Override
    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Request no title before any content is attached. This removes the default
        // theme title slot instead of only hiding an ActionBar after it has reserved space.
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        // goapk uses a plain Activity theme. Hide any remaining default ActionBar so the
        // WebView owns the entire visual hierarchy instead of showing the app label.
        ActionBar actionBar = getActionBar();
        if (actionBar != null) actionBar.hide();

        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        // Never allow visual window tuning to prevent WebView startup on a device.
        try {
            applyImmersiveMode();
        } catch (Throwable e) {
            Log.w(TAG, "Window layout fallback activated", e);
        }
        initNativeMediaSession();
        qqMusicSession = new QQMusicSession(this);

        webView = new WebView(this);
        // Matches the web shell before the first paint, preventing OEM default colors from flashing.
        webView.setBackgroundColor(Color.rgb(116, 20, 47));
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setGeolocationEnabled(true);

        webView.setNetworkAvailable(true);

        // 注册增强的 NativeBridge
        webView.addJavascriptInterface(new NativeBridge(this), "NativeBridge");

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                String[] resources = request.getResources();
                List<String> needed = new ArrayList<>();
                for (String res : resources) {
                    String[] androidPerms = WEBKIT_TO_ANDROID.get(res);
                    if (androidPerms != null) {
                        for (String perm : androidPerms) {
                            if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
                                needed.add(perm);
                            }
                        }
                    }
                }
                if (needed.isEmpty()) {
                    request.grant(resources);
                } else {
                    pendingPermissionRequest = request;
                    requestPermissions(needed.toArray(new String[0]), REQUEST_CODE_PERMISSIONS);
                }
            }

            @Override
            public void onPermissionRequestCanceled(PermissionRequest request) {
                if (pendingPermissionRequest == request) {
                    pendingPermissionRequest = null;
                }
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(String origin,
                    GeolocationPermissions.Callback callback) {
                String perm = "android.permission.ACCESS_FINE_LOCATION";
                if (checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED) {
                    callback.invoke(origin, true, false);
                } else {
                    pendingGeoCallback = callback;
                    pendingGeoOrigin = origin;
                    requestPermissions(new String[]{perm}, REQUEST_CODE_PERMISSIONS);
                }
            }

            // Android WebView does not open <input type="file"> unless the host
            // implements this callback. The Storage Access Framework gives the app
            // a durable URI grant without broad media-library permission.
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             WebChromeClient.FileChooserParams params) {
                if (pendingFileChooser != null) pendingFileChooser.onReceiveValue(null);
                pendingFileChooser = callback;
                try {
                    Intent intent = params.createIntent();
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,
                        params.getMode() == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE);
                    startActivityForResult(Intent.createChooser(intent, "选择音频文件"),
                        REQUEST_CODE_FILE_CHOOSER);
                    return true;
                } catch (Exception e) {
                    Log.e(TAG, "Unable to launch file chooser", e);
                    pendingFileChooser.onReceiveValue(null);
                    pendingFileChooser = null;
                    return false;
                }
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view,
                                                              WebResourceRequest request) {
                return maybeServeAsset(request.getUrl(), request.getRequestHeaders());
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                view.evaluateJavascript(
                    "if(!navigator.onLine){" +
                    "Object.defineProperty(navigator,'onLine'," +
                    "{get:function(){return true},configurable:true});" +
                    "window.dispatchEvent(new Event('online'));" +
                    "}",
                    null
                );
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        WebResourceError error) {
                Log.e(TAG, "WebView error: " + error.getDescription()
                    + " url=" + request.getUrl());
            }
        });

        if (savedInstanceState != null && webView.restoreState(savedInstanceState) != null) {
            Log.i(TAG, "Restored existing WebView state");
        } else {
            String startUrl = resolveStartUrl();
            Log.i(TAG, "Loading: " + startUrl);
            webView.loadUrl(startUrl);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        if (webView != null) webView.saveState(outState);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (webView != null) {
            webView.onResume();
            webView.requestFocus();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        if (requestCode == REQUEST_CODE_NOTIFICATIONS) {
            if (canPostMediaNotification()) refreshNativeMediaPresentation();
            return;
        }
        if (requestCode == REQUEST_CODE_MEDIA_LIBRARY) {
            boolean granted = grantResults.length > 0;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) { granted = false; break; }
            }
            final boolean result = granted;
            if (webView != null) {
                webView.post(() -> webView.evaluateJavascript(
                    "window.dispatchEvent(new CustomEvent('nativeMediaPermission'," +
                    "{detail:{granted:" + result + "}}));", null));
            }
            return;
        }
        if (requestCode != REQUEST_CODE_PERMISSIONS) {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
            return;
        }

        boolean allGranted = true;
        for (int result : grantResults) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }

        if (pendingPermissionRequest != null) {
            if (allGranted) {
                pendingPermissionRequest.grant(pendingPermissionRequest.getResources());
            } else {
                pendingPermissionRequest.deny();
            }
            pendingPermissionRequest = null;
        }

        if (pendingGeoCallback != null) {
            pendingGeoCallback.invoke(pendingGeoOrigin, allGranted, false);
            pendingGeoCallback = null;
            pendingGeoOrigin = null;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_MUSIC_TREE) {
            boolean added = false;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                Uri uri = data.getData();
                int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                try {
                    getContentResolver().takePersistableUriPermission(uri, flags);
                    addCustomMusicTree(uri);
                    added = true;
                } catch (SecurityException e) {
                    Log.w(TAG, "Cannot persist selected music tree", e);
                }
            }
            dispatchCustomTreeResult(added);
            return;
        }
        if (requestCode != REQUEST_CODE_FILE_CHOOSER || pendingFileChooser == null) return;
        Uri[] result = null;
        if (resultCode == RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                int count = data.getClipData().getItemCount();
                result = new Uri[count];
                for (int i = 0; i < count; i++) result[i] = data.getClipData().getItemAt(i).getUri();
            } else if (data.getData() != null) {
                result = new Uri[]{data.getData()};
            }
        }
        pendingFileChooser.onReceiveValue(result);
        pendingFileChooser = null;
    }

    private WebResourceResponse maybeServeAsset(Uri uri, Map<String, String> requestHeaders) {
        if (!ASSET_HOST.equals(uri.getScheme() + "://" + uri.getHost())) {
            return null;
        }
        String path = uri.getPath();
        if (path != null && path.startsWith(NATIVE_DOWNLOAD_PREFIX)) {
            return serveCompletedDownload(path, requestHeaders);
        }
        if (path != null && path.startsWith(NATIVE_MEDIA_PREFIX)) {
            return serveLocalMedia(path, requestHeaders);
        }
        if (path != null && path.startsWith(NATIVE_ALBUM_PREFIX)) {
            return serveLocalAlbumArt(path);
        }
        if (path != null && path.startsWith(NATIVE_DOCUMENT_PREFIX)) {
            return serveCustomDocument(path, requestHeaders);
        }
        if (path == null || path.isEmpty() || path.equals("/")) {
            path = "/index.html";
        }
        String assetPath = ASSET_PREFIX + path;
        AssetManager am = getAssets();
        try {
            InputStream is = am.open(assetPath);
            String mimeType = guessMimeType(path);
            return new WebResourceResponse(mimeType, "utf-8", is);
        } catch (IOException e) {
            String leaf = path.substring(path.lastIndexOf('/') + 1);
            if (!leaf.contains(".")) {
                try {
                    InputStream is = am.open(ASSET_PREFIX + "/index.html");
                    return new WebResourceResponse("text/html", "utf-8", is);
                } catch (IOException e2) {
                    Log.e(TAG, "SPA fallback failed — index.html missing");
                }
            }
            Log.w(TAG, "Asset not found: " + assetPath);
            return null;
        }
    }

    private static String guessMimeType(String path) {
        int dot = path.lastIndexOf('.');
        if (dot >= 0) {
            String ext = path.substring(dot + 1).toLowerCase();
            switch (ext) {
                case "html": return "text/html";
                case "js":   return "application/javascript";
                case "mjs":  return "application/javascript";
                case "css":  return "text/css";
                case "json": return "application/json";
                case "png":  return "image/png";
                case "jpg":
                case "jpeg": return "image/jpeg";
                case "svg":  return "image/svg+xml";
                case "ico":  return "image/x-icon";
                case "woff": return "font/woff";
                case "woff2":return "font/woff2";
                case "webmanifest": return "application/manifest+json";
                case "wasm": return "application/wasm";
            }
            String fromMap = MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(ext);
            if (fromMap != null) return fromMap;
        }
        return "application/octet-stream";
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && event.getRepeatCount() == 0 && webView != null) {
            // The app is a single-page WebView.  Delegate BACK to its page-level stack
            // first so sheets, player, details and tabs close instead of minimizing.
            webView.evaluateJavascript("window.dispatchEvent(new Event('nativeAppBack'));", null);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
        try { applyImmersiveMode(); } catch (Throwable e) { Log.w(TAG, "Window resume fallback", e); }
    }

    @Override
    protected void onPause() {
        if (webView != null) webView.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (notificationManager != null) notificationManager.cancel(MEDIA_NOTIFICATION_ID);
        if (mediaControlReceiver != null) { try { unregisterReceiver(mediaControlReceiver); } catch (Exception ignored) {} }
        releaseNativeLocalPlayer(false);
        if (mediaSession != null) { mediaSession.setActive(false); mediaSession.release(); mediaSession = null; }
        if (webView != null) webView.destroy();
        super.onDestroy();
    }

    private String resolveStartUrl() {
        try {
            AssetManager am = getAssets();
            InputStream is = am.open(CONFIG_PATH);
            byte[] buf = new byte[is.available()];
            is.read(buf);
            is.close();
            String json = new String(buf, StandardCharsets.UTF_8);
            JSONObject cfg = new JSONObject(json);
            String url = cfg.optString("start_url", "");
            if (!url.isEmpty() && (url.startsWith("http://") || url.startsWith("https://"))) {
                return url;
            }
        } catch (Exception e) {
            Log.d(TAG, "No config.json or no start_url, loading local assets");
        }
        return ASSETS_INDEX;
    }

    private void initNativeMediaSession() {
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && notificationManager != null) {
            NotificationChannel channel = new NotificationChannel(MEDIA_CHANNEL_ID, "轻音播放控制",
                NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("显示当前播放歌曲与控制按钮");
            channel.setShowBadge(false);
            notificationManager.createNotificationChannel(channel);
        }
        mediaSession = new MediaSession(this, "Molan Light Music");
        Intent appIntent = new Intent(this, WebViewActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        mediaSessionActivityIntent = PendingIntent.getActivity(this, 0, appIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        mediaSession.setSessionActivity(mediaSessionActivityIntent);
        mediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { dispatchWebPlayerCommand("play"); }
            @Override public void onPause() { dispatchWebPlayerCommand("pause"); }
            @Override public void onSkipToNext() { dispatchWebPlayerCommand("next"); }
            @Override public void onSkipToPrevious() { dispatchWebPlayerCommand("previous"); }
            @Override public void onSeekTo(long position) { dispatchWebPlayerCommand("seek:" + Math.max(0, position)); }
        });
        mediaSession.setActive(true);
        mediaControlReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                String action = intent == null ? "" : intent.getAction();
                if (ACTION_MEDIA_PLAY.equals(action)) dispatchWebPlayerCommand("play");
                else if (ACTION_MEDIA_PAUSE.equals(action)) dispatchWebPlayerCommand("pause");
                else if (ACTION_MEDIA_NEXT.equals(action)) dispatchWebPlayerCommand("next");
                else if (ACTION_MEDIA_PREV.equals(action)) dispatchWebPlayerCommand("previous");
            }
        };
        IntentFilter controls = new IntentFilter();
        controls.addAction(ACTION_MEDIA_PLAY); controls.addAction(ACTION_MEDIA_PAUSE);
        controls.addAction(ACTION_MEDIA_NEXT); controls.addAction(ACTION_MEDIA_PREV);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) registerReceiver(mediaControlReceiver, controls, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(mediaControlReceiver, controls);
    }

    private void dispatchWebPlayerCommand(String command) {
        if (command == null) return;
        if (hasNativeLocalPlayer()) {
            if ("play".equals(command)) { resumeNativeLocalPlayer(); return; }
            if ("pause".equals(command)) { pauseNativeLocalPlayer(); return; }
            if (command.startsWith("seek:")) {
                try { seekNativeLocalPlayer(Long.parseLong(command.substring("seek:".length()))); return; }
                catch (Exception ignored) {}
            }
        }
        if (webView == null) return;
        final String escaped = command.replace("\\", "\\\\").replace("'", "\\'");
        webView.post(() -> webView.evaluateJavascript(
            "window.dispatchEvent(new CustomEvent('nativeMediaControl',{detail:{action:'" + escaped + "'}}));", null));
    }

    private boolean hasNativeLocalPlayer() { return nativeLocalPlayer != null && nativeLocalPrepared; }

    private void dispatchNativeLocalPlayerEvent(String type, long position, long duration, String message) {
        if (webView == null) return;
        try {
            JSONObject event = new JSONObject();
            event.put("type", type == null ? "" : type);
            event.put("songId", nativeLocalSongId == null ? "" : nativeLocalSongId);
            event.put("position", Math.max(0, position));
            event.put("duration", Math.max(0, duration));
            event.put("message", message == null ? "" : message);
            String payload = event.toString().replace("</", "<\\/");
            webView.post(() -> webView.evaluateJavascript(
                "window.dispatchEvent(new CustomEvent('nativeLocalPlayer',{detail:" + payload + "}));", null));
        } catch (Exception ignored) {}
    }

    private void releaseNativeLocalPlayer(boolean resetPresentation) {
        nativeLocalHandler.removeCallbacks(nativeLocalProgressTicker);
        MediaPlayer player = nativeLocalPlayer;
        nativeLocalPlayer = null; nativeLocalPrepared = false; nativeLocalSongId = "";
        if (player != null) {
            try { player.reset(); } catch (Exception ignored) {}
            try { player.release(); } catch (Exception ignored) {}
        }
        if (resetPresentation) updateNativeMediaSession("轻音", "", false, 0, 0);
    }

    private Uri resolveNativeLocalUri(String rawUri) {
        if (rawUri == null || rawUri.trim().isEmpty()) return null;
        String value = rawUri.trim();
        try {
            if (value.startsWith("media:")) {
                String rawId = value.substring("media:".length());
                if (!rawId.matches("[0-9]+") || !hasMediaReadPermission()) return null;
                return ContentUris.withAppendedId(audioCollectionUri(), Long.parseLong(rawId));
            }
            Uri uri = Uri.parse(value);
            return "content".equalsIgnoreCase(uri.getScheme()) ? uri : null;
        } catch (Exception ignored) { return null; }
    }

    private void startNativeLocalPlayer(String songId, String rawUri, String title, String artist) {
        Uri uri = resolveNativeLocalUri(rawUri);
        if (uri == null) {
            dispatchNativeLocalPlayerEvent("error", 0, 0, "本地媒体 URI 无效，请重新扫描本地音乐");
            return;
        }
        releaseNativeLocalPlayer(false);
        nativeLocalSongId = songId == null ? "" : songId;
        nativeLocalTitle = title == null || title.trim().isEmpty() ? "未知歌曲" : title;
        nativeLocalArtist = artist == null ? "" : artist;
        try {
            final MediaPlayer player = new MediaPlayer();
            nativeLocalPlayer = player;
            player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            player.setOnPreparedListener(mp -> {
                if (mp != nativeLocalPlayer) return;
                nativeLocalPrepared = true;
                long duration = Math.max(0, mp.getDuration());
                updateNativeMediaSession(nativeLocalTitle, nativeLocalArtist, true, 0, duration);
                dispatchNativeLocalPlayerEvent("prepared", 0, duration, "");
                try {
                    mp.start();
                    dispatchNativeLocalPlayerEvent("play", 0, duration, "");
                    nativeLocalHandler.removeCallbacks(nativeLocalProgressTicker);
                    nativeLocalHandler.post(nativeLocalProgressTicker);
                } catch (Exception e) {
                    dispatchNativeLocalPlayerEvent("error", 0, duration, "本地音源无法开始播放");
                }
            });
            player.setOnSeekCompleteListener(mp -> {
                if (mp != nativeLocalPlayer || !nativeLocalPrepared) return;
                long position = Math.max(0, mp.getCurrentPosition()), duration = Math.max(0, mp.getDuration());
                updateNativeMediaSession(nativeLocalTitle, nativeLocalArtist, mp.isPlaying(), position, duration);
                dispatchNativeLocalPlayerEvent("seeked", position, duration, "");
            });
            player.setOnCompletionListener(mp -> {
                if (mp != nativeLocalPlayer) return;
                long duration = nativeLocalPrepared ? Math.max(0, mp.getDuration()) : 0;
                nativeLocalHandler.removeCallbacks(nativeLocalProgressTicker);
                updateNativeMediaSession(nativeLocalTitle, nativeLocalArtist, false, duration, duration);
                dispatchNativeLocalPlayerEvent("ended", duration, duration, "");
            });
            player.setOnErrorListener((mp, what, extra) -> {
                if (mp == nativeLocalPlayer) {
                    nativeLocalHandler.removeCallbacks(nativeLocalProgressTicker);
                    dispatchNativeLocalPlayerEvent("error", 0, 0, "原生本地播放器错误 " + what + "/" + extra);
                }
                return true;
            });
            player.setDataSource(this, uri);
            player.prepareAsync();
            dispatchNativeLocalPlayerEvent("loading", 0, 0, "正在准备本地音源");
        } catch (Exception e) {
            Log.w(TAG, "Cannot start native local player uri=" + uri, e);
            releaseNativeLocalPlayer(false);
            dispatchNativeLocalPlayerEvent("error", 0, 0, "无法打开本地音源：" + (e.getMessage() == null ? "未知错误" : e.getMessage()));
        }
    }

    private void pauseNativeLocalPlayer() {
        MediaPlayer player = nativeLocalPlayer;
        if (player == null || !nativeLocalPrepared) return;
        try {
            if (player.isPlaying()) player.pause();
            long position = Math.max(0, player.getCurrentPosition()), duration = Math.max(0, player.getDuration());
            nativeLocalHandler.removeCallbacks(nativeLocalProgressTicker);
            updateNativeMediaSession(nativeLocalTitle, nativeLocalArtist, false, position, duration);
            dispatchNativeLocalPlayerEvent("pause", position, duration, "");
        } catch (Exception e) { dispatchNativeLocalPlayerEvent("error", 0, 0, "本地播放器暂停失败"); }
    }

    private void resumeNativeLocalPlayer() {
        MediaPlayer player = nativeLocalPlayer;
        if (player == null || !nativeLocalPrepared) return;
        try {
            player.start();
            long position = Math.max(0, player.getCurrentPosition()), duration = Math.max(0, player.getDuration());
            updateNativeMediaSession(nativeLocalTitle, nativeLocalArtist, true, position, duration);
            dispatchNativeLocalPlayerEvent("play", position, duration, "");
            nativeLocalHandler.removeCallbacks(nativeLocalProgressTicker);
            nativeLocalHandler.post(nativeLocalProgressTicker);
        } catch (Exception e) { dispatchNativeLocalPlayerEvent("error", 0, 0, "本地播放器恢复失败"); }
    }

    private void seekNativeLocalPlayer(long targetPosition) {
        MediaPlayer player = nativeLocalPlayer;
        if (player == null || !nativeLocalPrepared) return;
        try {
            long duration = Math.max(0, player.getDuration());
            int safePosition = (int) Math.min(Integer.MAX_VALUE, Math.max(0, Math.min(targetPosition, duration)));
            player.seekTo(safePosition);
        } catch (Exception e) { dispatchNativeLocalPlayerEvent("error", 0, 0, "本地音源定位失败"); }
    }

    private boolean canPostMediaNotification() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
            || checkSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED;
    }

    private PendingIntent mediaControlIntent(String action, int requestCode) {
        Intent intent = new Intent(action).setPackage(getPackageName());
        return PendingIntent.getBroadcast(this, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void refreshNativeMediaPresentation() {
        if (mediaSession == null) return;
        String title = nativeMediaTitle == null || nativeMediaTitle.trim().isEmpty() ? "轻音" : nativeMediaTitle;
        String artist = nativeMediaArtist == null ? "" : nativeMediaArtist;
        MediaMetadata metadata = new MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, Math.max(0, nativeMediaDuration))
            .build();
        mediaSession.setMetadata(metadata);
        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
            | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS
            | PlaybackState.ACTION_SEEK_TO;
        int state = nativeMediaPlaying ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED;
        float speed = nativeMediaPlaying ? 1f : 0f;
        mediaSession.setPlaybackState(new PlaybackState.Builder().setActions(actions)
            .setState(state, Math.max(0, nativeMediaPosition), speed, SystemClock.elapsedRealtime()).build());
        if (notificationManager == null) return;
        if (!canPostMediaNotification()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQUEST_CODE_NOTIFICATIONS);
            }
            return;
        }
        PendingIntent previous = mediaControlIntent(ACTION_MEDIA_PREV, 1);
        PendingIntent primary = mediaControlIntent(nativeMediaPlaying ? ACTION_MEDIA_PAUSE : ACTION_MEDIA_PLAY, 2);
        PendingIntent next = mediaControlIntent(ACTION_MEDIA_NEXT, 3);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? new Notification.Builder(this, MEDIA_CHANNEL_ID) : new Notification.Builder(this);
        builder.setSmallIcon(nativeMediaPlaying ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play)
            .setContentIntent(mediaSessionActivityIntent).setContentTitle(title).setContentText(artist).setOnlyAlertOnce(true).setShowWhen(false)
            .setOngoing(nativeMediaPlaying).setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(android.R.drawable.ic_media_previous, "上一首", previous)
            .addAction(nativeMediaPlaying ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                nativeMediaPlaying ? "暂停" : "播放", primary)
            .addAction(android.R.drawable.ic_media_next, "下一首", next)
            .setStyle(new Notification.MediaStyle().setMediaSession(mediaSession.getSessionToken())
                .setShowActionsInCompactView(0, 1, 2));
        notificationManager.notify(MEDIA_NOTIFICATION_ID, builder.build());
    }

    private void updateNativeMediaSession(String title, String artist, boolean playing,
                                          long position, long duration) {
        nativeMediaTitle = title == null ? "轻音" : title;
        nativeMediaArtist = artist == null ? "" : artist;
        nativeMediaPlaying = playing;
        nativeMediaPosition = Math.max(0, position);
        nativeMediaDuration = Math.max(0, duration);
        refreshNativeMediaPresentation();
    }

    private void applyImmersiveMode() {
        // Conservative API 24+ edge-to-edge layout. The window and WebView share a wine
        // first-paint color, so transparent bars never expose an OEM blue default surface.
        final int firstPaintWine = Color.rgb(116, 20, 47);
        getWindow().setBackgroundDrawable(new ColorDrawable(firstPaintWine));
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        );
    }

    private boolean hasMediaReadPermission() {
        String permission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            ? "android.permission.READ_MEDIA_AUDIO"
            : "android.permission.READ_EXTERNAL_STORAGE";
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private Uri audioCollectionUri() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL);
        }
        return MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
    }

    private static String headerValue(Map<String, String> headers, String name) {
        if (headers == null) return null;
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (name.equalsIgnoreCase(entry.getKey())) return entry.getValue();
        }
        return null;
    }

    private static final class BoundedInputStream extends FilterInputStream {
        private long remaining;
        BoundedInputStream(InputStream input, long length) { super(input); remaining = Math.max(0, length); }
        @Override public int read() throws IOException { if (remaining <= 0) return -1; int value = in.read(); if (value >= 0) remaining--; return value; }
        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            if (remaining <= 0) return -1;
            int read = in.read(buffer, offset, (int) Math.min(length, remaining));
            if (read > 0) remaining -= read;
            return read;
        }
        @Override public long skip(long amount) throws IOException {
            long skipped = in.skip(Math.min(amount, remaining)); remaining -= skipped; return skipped;
        }
        @Override public int available() throws IOException { return (int) Math.min(in.available(), remaining); }
    }

    private long queryContentSize(Uri uri) {
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri, new String[]{OpenableColumns.SIZE}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (column >= 0 && !cursor.isNull(column)) return Math.max(0, cursor.getLong(column));
            }
        } catch (Exception ignored) {
        } finally { if (cursor != null) cursor.close(); }
        return 0;
    }

    // WebView's media renderer issues HTTP Range requests while seeking. Each request
    // gets an independently opened descriptor, a true file-channel seek and a strict
    // byte-bounded response body so its Content-Range cannot be over-read.
    private WebResourceResponse streamAudio(ParcelFileDescriptor fd, String mime,
                                             Map<String, String> requestHeaders, long totalHint) throws IOException {
        long descriptorSize = fd.getStatSize();
        final long total = descriptorSize > 0 ? descriptorSize : Math.max(0, totalHint);
        ParcelFileDescriptor.AutoCloseInputStream stream = new ParcelFileDescriptor.AutoCloseInputStream(fd);
        Map<String, String> headers = new HashMap<>();
        headers.put("Accept-Ranges", "bytes");
        String range = headerValue(requestHeaders, "Range");
        if (range == null || !range.startsWith("bytes=") || total <= 0) {
            if (total > 0) headers.put("Content-Length", String.valueOf(total));
            return new WebResourceResponse(mime, null, 200, "OK", headers, stream);
        }
        try {
            String value = range.substring("bytes=".length()).split(",", 2)[0].trim();
            String[] bounds = value.split("-", 2);
            if (bounds.length != 2) throw new IllegalArgumentException("Malformed Range");
            long start;
            long end;
            if (bounds[0].isEmpty()) {
                long suffix = Long.parseLong(bounds[1]);
                if (suffix <= 0) throw new IllegalArgumentException("Invalid suffix Range");
                start = Math.max(0, total - suffix); end = total - 1;
            } else {
                start = Long.parseLong(bounds[0]);
                end = bounds[1].isEmpty() ? total - 1 : Math.min(total - 1, Long.parseLong(bounds[1]));
            }
            if (start < 0 || start >= total || end < start) throw new IllegalArgumentException("Unsatisfiable Range");
            stream.getChannel().position(start);
            long length = end - start + 1;
            headers.put("Content-Range", "bytes " + start + "-" + end + "/" + total);
            headers.put("Content-Length", String.valueOf(length));
            return new WebResourceResponse(mime, null, 206, "Partial Content", headers,
                new BoundedInputStream(stream, length));
        } catch (Exception e) {
            try { stream.close(); } catch (Exception ignored) {}
            headers.put("Content-Range", "bytes */" + total);
            return new WebResourceResponse(mime, null, 416, "Range Not Satisfiable", headers,
                new ByteArrayInputStream(new byte[0]));
        }
    }

    // Serves a MediaStore audio content URI through the same virtual HTTPS origin as
    // the bundled app. JavaScript never needs broad file-path access or a content:// URL.
    private WebResourceResponse serveLocalMedia(String path, Map<String, String> requestHeaders) {
        String file = path.substring(NATIVE_MEDIA_PREFIX.length());
        int dot = file.indexOf('.');
        String rawId = dot >= 0 ? file.substring(0, dot) : file;
        if (!rawId.matches("[0-9]+") || !hasMediaReadPermission()) return null;
        try {
            Uri uri = ContentUris.withAppendedId(audioCollectionUri(), Long.parseLong(rawId));
            ContentResolver resolver = getContentResolver();
            ParcelFileDescriptor fd = resolver.openFileDescriptor(uri, "r");
            if (fd == null) return null;
            String mime = resolver.getType(uri);
            return streamAudio(fd, mime == null ? "audio/mpeg" : mime, requestHeaders, queryContentSize(uri));
        } catch (Exception e) {
            Log.w(TAG, "Cannot serve local media", e);
            return null;
        }
    }

    private WebResourceResponse serveCustomDocument(String path, Map<String, String> requestHeaders) {
        String file = path.substring(NATIVE_DOCUMENT_PREFIX.length());
        int dot = file.indexOf('.');
        String token = dot >= 0 ? file.substring(0, dot) : file;
        Uri uri = customMusicDocuments.get(token);
        if (uri == null) return null;
        try {
            ContentResolver resolver = getContentResolver();
            ParcelFileDescriptor fd = resolver.openFileDescriptor(uri, "r");
            if (fd == null) return null;
            String mime = resolver.getType(uri);
            return streamAudio(fd, mime == null ? "audio/mpeg" : mime, requestHeaders, queryContentSize(uri));
        } catch (Exception e) {
            Log.w(TAG, "Cannot serve custom music document", e);
            return null;
        }
    }

    private WebResourceResponse serveLocalAlbumArt(String path) {
        String file = path.substring(NATIVE_ALBUM_PREFIX.length());
        int dot = file.indexOf('.');
        String rawId = dot >= 0 ? file.substring(0, dot) : file;
        if (!rawId.matches("[0-9]+") || !hasMediaReadPermission()) return null;
        try {
            Uri base = Uri.parse("content://media/external/audio/albumart");
            InputStream stream = getContentResolver().openInputStream(
                ContentUris.withAppendedId(base, Long.parseLong(rawId)));
            return stream == null ? null : new WebResourceResponse("image/jpeg", null, stream);
        } catch (Exception e) {
            return null;
        }
    }

    // Streams a completed DownloadManager file through the app's virtual HTTPS origin.
    // This makes an offline file playable by <audio> without exposing file:// or content:// to JS.
    private WebResourceResponse serveCompletedDownload(String path, Map<String, String> requestHeaders) {
        String file = path.substring(NATIVE_DOWNLOAD_PREFIX.length());
        int dot = file.indexOf('.');
        String rawId = dot >= 0 ? file.substring(0, dot) : file;
        if (!rawId.matches("[0-9]+")) return null;
        try {
            long id = Long.parseLong(rawId);
            DownloadManager manager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            JSONObject status = manager == null ? null : getDownloadStatusObject(manager, id);
            if (status == null || status.optString("state").equals("missing")) return null;
            ParcelFileDescriptor fd = manager.openDownloadedFile(id);
            String mime = status.optString("mime", "audio/mpeg");
            return streamAudio(fd, mime, requestHeaders, status.optLong("total", 0));
        } catch (Exception e) {
            Log.w(TAG, "Cannot serve completed download", e);
            return null;
        }
    }

    private JSONObject getDownloadStatusObject(DownloadManager manager, long id) {
        JSONObject out = new JSONObject();
        try {
            DownloadManager.Query query = new DownloadManager.Query().setFilterById(id);
            Cursor cursor = manager.query(query);
            try {
                if (!cursor.moveToFirst()) {
                    out.put("state", "missing");
                    return out;
                }
                int rawStatus = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                long bytes = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                long total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                String state;
                switch (rawStatus) {
                    case DownloadManager.STATUS_PENDING: state = "queued"; break;
                    case DownloadManager.STATUS_RUNNING: state = "downloading"; break;
                    case DownloadManager.STATUS_PAUSED: state = "paused"; break;
                    case DownloadManager.STATUS_SUCCESSFUL: state = "completed"; break;
                    case DownloadManager.STATUS_FAILED: state = "failed"; break;
                    default: state = "unknown";
                }
                out.put("state", state);
                out.put("bytes", Math.max(0, bytes));
                out.put("total", Math.max(0, total));
                out.put("reason", cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)));
                int mimeIndex = cursor.getColumnIndex(DownloadManager.COLUMN_MEDIA_TYPE);
                if (mimeIndex >= 0 && !cursor.isNull(mimeIndex)) out.put("mime", cursor.getString(mimeIndex));
                if (rawStatus == DownloadManager.STATUS_SUCCESSFUL) {
                    out.put("playbackUrl", ASSET_HOST + NATIVE_DOWNLOAD_PREFIX + id + ".audio");
                }
            } finally {
                cursor.close();
            }
        } catch (Exception e) {
            try { out.put("state", "failed"); out.put("message", e.getMessage()); } catch (Exception ignored) {}
        }
        return out;
    }

    private static String safeAudioFileName(String value) {
        String clean = value == null ? "" : value.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (clean.isEmpty()) clean = "song";
        if (!clean.toLowerCase().endsWith(".mp3")) clean += ".mp3";
        return clean.length() > 96 ? clean.substring(0, 96) : clean;
    }

    private SharedPreferences customMusicPreferences() {
        return getSharedPreferences("molan_local_music", MODE_PRIVATE);
    }

    private ArrayList<String> getCustomMusicTreeUris() {
        ArrayList<String> result = new ArrayList<>();
        try {
            JSONArray saved = new JSONArray(customMusicPreferences().getString(CUSTOM_MUSIC_TREES_KEY, "[]"));
            for (int i = 0; i < saved.length(); i++) {
                String uri = saved.optString(i, "");
                if (!uri.isEmpty()) result.add(uri);
            }
        } catch (Exception e) {
            Log.w(TAG, "Cannot read custom music folders", e);
        }
        return result;
    }

    private void saveCustomMusicTreeUris(ArrayList<String> uris) {
        JSONArray out = new JSONArray();
        for (String uri : uris) out.put(uri);
        customMusicPreferences().edit().putString(CUSTOM_MUSIC_TREES_KEY, out.toString()).apply();
    }

    private void addCustomMusicTree(Uri uri) {
        ArrayList<String> trees = getCustomMusicTreeUris();
        String raw = uri.toString();
        if (!trees.contains(raw)) trees.add(raw);
        saveCustomMusicTreeUris(trees);
    }

    private String customTreeDisplayName(Uri treeUri) {
        try {
            String documentId = DocumentsContract.getTreeDocumentId(treeUri);
            int slash = documentId.lastIndexOf('/');
            String name = slash >= 0 ? documentId.substring(slash + 1) : documentId;
            int colon = name.indexOf(':');
            if (colon >= 0 && colon + 1 < name.length()) name = name.substring(colon + 1);
            return name.isEmpty() ? "自定义音乐文件夹" : name;
        } catch (Exception e) {
            return "自定义音乐文件夹";
        }
    }

    private static boolean isSupportedAudioDocument(String displayName, String mime) {
        if (mime != null && mime.toLowerCase().startsWith("audio/")) return true;
        String value = displayName == null ? "" : displayName.toLowerCase();
        return value.endsWith(".mp3") || value.endsWith(".flac") || value.endsWith(".wav")
            || value.endsWith(".m4a") || value.endsWith(".aac") || value.endsWith(".ogg")
            || value.endsWith(".opus") || value.endsWith(".wma");
    }

    private static String localTitleFromFileName(String displayName) {
        String title = displayName == null ? "未知歌曲" : displayName.trim();
        int dot = title.lastIndexOf('.');
        if (dot > 0) title = title.substring(0, dot);
        return title.isEmpty() ? "未知歌曲" : title;
    }

    private void dispatchCustomTreeResult(boolean added) {
        if (webView == null) return;
        webView.post(() -> webView.evaluateJavascript(
            "window.dispatchEvent(new CustomEvent('nativeCustomMusicTree'," +
            "{detail:{added:" + added + "}}));", null));
    }

    private JSONObject scanCustomMusicTrees() {
        JSONObject response = new JSONObject();
        JSONArray songs = new JSONArray();
        customMusicDocuments.clear();
        int scanned = 0;
        try {
            ArrayList<String> trees = getCustomMusicTreeUris();
            ContentResolver resolver = getContentResolver();
            for (int treeIndex = 0; treeIndex < trees.size() && scanned < MAX_CUSTOM_TREE_SONGS; treeIndex++) {
                Uri treeUri = Uri.parse(trees.get(treeIndex));
                String treeName = customTreeDisplayName(treeUri);
                ArrayDeque<JSONObject> folders = new ArrayDeque<>();
                JSONObject root = new JSONObject();
                root.put("uri", treeUri.toString());
                root.put("depth", 0);
                folders.add(root);
                while (!folders.isEmpty() && scanned < MAX_CUSTOM_TREE_SONGS) {
                    JSONObject folder = folders.removeFirst();
                    Uri folderUri = Uri.parse(folder.optString("uri"));
                    int depth = folder.optInt("depth", 0);
                    String documentId = DocumentsContract.getDocumentId(folderUri);
                    Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId);
                    Cursor cursor = null;
                    try {
                        cursor = resolver.query(childrenUri, new String[]{
                            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                            DocumentsContract.Document.COLUMN_MIME_TYPE,
                            DocumentsContract.Document.COLUMN_SIZE
                        }, null, null, null);
                        if (cursor == null) continue;
                        int idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
                        int nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                        int mimeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE);
                        int sizeColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE);
                        while (cursor.moveToNext() && scanned < MAX_CUSTOM_TREE_SONGS) {
                            String childId = cursor.getString(idColumn);
                            String displayName = cursor.getString(nameColumn);
                            String mime = cursor.getString(mimeColumn);
                            Uri childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId);
                            if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                                if (depth < MAX_CUSTOM_TREE_DEPTH) {
                                    JSONObject next = new JSONObject();
                                    next.put("uri", childUri.toString());
                                    next.put("depth", depth + 1);
                                    folders.addLast(next);
                                }
                                continue;
                            }
                            if (!isSupportedAudioDocument(displayName, mime)) continue;
                            String title = localTitleFromFileName(displayName);
                            String artist = "未知歌手";
                            int separator = title.indexOf(" - ");
                            if (separator > 0 && separator < title.length() - 3) {
                                artist = title.substring(0, separator).trim();
                                title = title.substring(separator + 3).trim();
                            }
                            String token = UUID.randomUUID().toString();
                            customMusicDocuments.put(token, childUri);
                            JSONObject song = new JSONObject();
                            song.put("id", "local-doc:" + Integer.toHexString(treeUri.toString().hashCode()) + ":" + Integer.toHexString(childId.hashCode()));
                            song.put("mediaId", "custom:" + token);
                            song.put("name", title);
                            song.put("artists", artist);
                            song.put("album", "");
                            song.put("duration", 0);
                            song.put("fileSize", sizeColumn >= 0 && !cursor.isNull(sizeColumn) ? cursor.getLong(sizeColumn) : 0);
                            song.put("mimeType", mime == null ? "audio/mpeg" : mime);
                            song.put("folder", treeName);
                            song.put("fileName", displayName == null ? title : displayName);
                            song.put("localUrl", ASSET_HOST + NATIVE_DOCUMENT_PREFIX + token + ".audio");
                            song.put("nativeUri", childUri.toString());
                            song.put("customFolder", true);
                            songs.put(song);
                            scanned++;
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Cannot scan custom music folder " + treeName, e);
                    } finally {
                        if (cursor != null) cursor.close();
                    }
                }
            }
            response.put("state", "ready");
            response.put("songs", songs);
            response.put("scanned", scanned);
            response.put("truncated", scanned >= MAX_CUSTOM_TREE_SONGS);
        } catch (Exception e) {
            Log.e(TAG, "Custom music directory scan failed", e);
            try { response.put("state", "failed"); response.put("message", e.getMessage()); response.put("songs", songs); } catch (Exception ignored) {}
        }
        return response;
    }

    // NativeBridge is intentionally synchronous for status calls. DownloadManager itself
    // runs the network transfer in the Android system process and does not depend on CORS.
    public class NativeBridge {
        private final Context context;

        public NativeBridge(Context context) {
            this.context = context;
        }

        @JavascriptInterface
        public String getPlatform() {
            return "android";
        }

        @JavascriptInterface
        public void updateMediaSession(String title, String artist, boolean playing,
                                       long position, long duration) {
            runOnUiThread(() -> updateNativeMediaSession(title, artist, playing, position, duration));
        }

        @JavascriptInterface
        public void clearMediaSession() {
            runOnUiThread(() -> {
                nativeMediaPlaying = false; nativeMediaPosition = 0; nativeMediaDuration = 0;
                if (notificationManager != null) notificationManager.cancel(MEDIA_NOTIFICATION_ID);
                if (mediaSession != null) mediaSession.setPlaybackState(new PlaybackState.Builder()
                    .setState(PlaybackState.STATE_NONE, 0, 0f).build());
            });
        }

        @JavascriptInterface
        public String qqQrCreate() {
            return qqMusicSession == null ? "{\"state\":\"failed\",\"message\":\"QQ 会话未初始化\"}" : qqMusicSession.createQr();
        }

        @JavascriptInterface
        public String qqQrCheck(String sessionId) {
            return qqMusicSession == null ? "{\"state\":\"failed\",\"message\":\"QQ 会话未初始化\"}" : qqMusicSession.checkQr(sessionId);
        }

        @JavascriptInterface
        public String qqAccount() {
            return qqMusicSession == null ? "{\"loggedIn\":false}" : qqMusicSession.account();
        }

        @JavascriptInterface
        public String qqMyPlaylists() {
            return qqMusicSession == null ? "{\"state\":\"failed\",\"playlists\":[]}" : qqMusicSession.myPlaylists();
        }

        @JavascriptInterface
        public String qqLogout() {
            return qqMusicSession == null ? "{\"ok\":true}" : qqMusicSession.logout();
        }

        @JavascriptInterface
        public String qqPlaylistDetail(String playlistId) {
            return qqMusicSession == null ? "{\"state\":\"failed\",\"message\":\"QQ 会话未初始化\"}" : qqMusicSession.playlistDetail(playlistId);
        }

        @JavascriptInterface
        public void playLocalMusic(String songId, String rawUri, String title, String artist) {
            runOnUiThread(() -> startNativeLocalPlayer(songId, rawUri, title, artist));
        }

        @JavascriptInterface
        public void pauseLocalMusic() { runOnUiThread(() -> pauseNativeLocalPlayer()); }

        @JavascriptInterface
        public void resumeLocalMusic() { runOnUiThread(() -> resumeNativeLocalPlayer()); }

        @JavascriptInterface
        public void seekLocalMusic(long positionMs) { runOnUiThread(() -> seekNativeLocalPlayer(positionMs)); }

        @JavascriptInterface
        public void stopLocalMusic() { runOnUiThread(() -> releaseNativeLocalPlayer(true)); }

        @JavascriptInterface
        public String getLocalMusicDirectory() {
            return "系统音乐库与已授权的自定义文件夹";
        }

        @JavascriptInterface
        public String scanLocalMusic() {
            JSONObject response = new JSONObject();
            if (!hasMediaReadPermission()) {
                String permission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    ? "android.permission.READ_MEDIA_AUDIO"
                    : "android.permission.READ_EXTERNAL_STORAGE";
                runOnUiThread(() -> requestPermissions(new String[]{permission}, REQUEST_CODE_MEDIA_LIBRARY));
                try {
                    response.put("state", "permission_required");
                    response.put("message", "请允许访问音乐和音频文件，以扫描本地歌曲");
                    response.put("songs", new JSONArray());
                } catch (Exception ignored) {}
                return response.toString();
            }

            JSONArray songs = new JSONArray();
            Cursor cursor = null;
            try {
                ArrayList<String> columns = new ArrayList<>();
                columns.add(MediaStore.Audio.Media._ID);
                columns.add(MediaStore.Audio.Media.TITLE);
                columns.add(MediaStore.Audio.Media.ARTIST);
                columns.add(MediaStore.Audio.Media.ALBUM);
                columns.add(MediaStore.Audio.Media.ALBUM_ID);
                columns.add(MediaStore.Audio.Media.DURATION);
                columns.add(MediaStore.Audio.Media.SIZE);
                columns.add(MediaStore.Audio.Media.MIME_TYPE);
                columns.add(MediaStore.MediaColumns.DISPLAY_NAME);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    columns.add(MediaStore.MediaColumns.RELATIVE_PATH);
                } else {
                    columns.add(MediaStore.MediaColumns.DATA);
                }
                String selection = MediaStore.Audio.Media.IS_MUSIC + " != 0 AND "
                    + MediaStore.Audio.Media.DURATION + " > 0 AND "
                    + MediaStore.Audio.Media.SIZE + " > 0";
                cursor = getContentResolver().query(audioCollectionUri(),
                    columns.toArray(new String[0]), selection, null,
                    MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC");
                if (cursor != null) {
                    int idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
                    int titleCol = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE);
                    int artistCol = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST);
                    int albumCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM);
                    int albumIdCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID);
                    int durationCol = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION);
                    int sizeCol = cursor.getColumnIndex(MediaStore.Audio.Media.SIZE);
                    int mimeCol = cursor.getColumnIndex(MediaStore.Audio.Media.MIME_TYPE);
                    int displayNameCol = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME);
                    int folderCol = cursor.getColumnIndex(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                        ? MediaStore.MediaColumns.RELATIVE_PATH : MediaStore.MediaColumns.DATA);
                    while (cursor.moveToNext()) {
                        long mediaId = cursor.getLong(idCol);
                        String title = titleCol >= 0 ? cursor.getString(titleCol) : "";
                        String displayName = displayNameCol >= 0 ? cursor.getString(displayNameCol) : "";
                        if (title == null || title.trim().isEmpty()) title = displayName == null ? "未知歌曲" : displayName;
                        String artist = artistCol >= 0 ? cursor.getString(artistCol) : "";
                        if (artist == null || artist.trim().isEmpty() || "<unknown>".equalsIgnoreCase(artist)) artist = "未知歌手";
                        String album = albumCol >= 0 ? cursor.getString(albumCol) : "";
                        String folder = folderCol >= 0 ? cursor.getString(folderCol) : "";
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && folder != null && !folder.isEmpty()) {
                            File parent = new File(folder).getParentFile();
                            folder = parent == null ? "本地音乐" : parent.getName();
                        }
                        JSONObject song = new JSONObject();
                        song.put("id", "local-media:" + mediaId);
                        song.put("mediaId", String.valueOf(mediaId));
                        song.put("name", title);
                        song.put("artists", artist);
                        song.put("album", album == null ? "" : album);
                        song.put("duration", durationCol >= 0 ? cursor.getLong(durationCol) : 0);
                        song.put("fileSize", sizeCol >= 0 ? cursor.getLong(sizeCol) : 0);
                        song.put("mimeType", mimeCol >= 0 ? cursor.getString(mimeCol) : "audio/mpeg");
                        song.put("folder", folder == null || folder.isEmpty() ? "本地音乐" : folder);
                        song.put("fileName", displayName == null ? title : displayName);
                        song.put("localUrl", ASSET_HOST + NATIVE_MEDIA_PREFIX + mediaId + ".audio");
                        song.put("nativeUri", ContentUris.withAppendedId(audioCollectionUri(), mediaId).toString());
                        long albumId = albumIdCol >= 0 ? cursor.getLong(albumIdCol) : 0;
                        if (albumId > 0) song.put("picUrl", ASSET_HOST + NATIVE_ALBUM_PREFIX + albumId + ".jpg");
                        songs.put(song);
                    }
                }
                response.put("state", "ready");
                response.put("songs", songs);
                response.put("directory", getLocalMusicDirectory());
            } catch (Exception e) {
                Log.e(TAG, "MediaStore scan failed", e);
                try { response.put("state", "failed"); response.put("message", e.getMessage()); response.put("songs", songs); } catch (Exception ignored) {}
            } finally {
                if (cursor != null) cursor.close();
            }
            return response.toString();
        }

        @JavascriptInterface
        public void chooseCustomMusicFolder() {
            runOnUiThread(() -> {
                try {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
                    startActivityForResult(intent, REQUEST_CODE_MUSIC_TREE);
                } catch (Exception e) {
                    Log.e(TAG, "Cannot open custom music folder picker", e);
                    dispatchCustomTreeResult(false);
                }
            });
        }

        @JavascriptInterface
        public String getCustomMusicFolders() {
            JSONArray folders = new JSONArray();
            for (String raw : getCustomMusicTreeUris()) {
                try {
                    Uri uri = Uri.parse(raw);
                    JSONObject folder = new JSONObject();
                    folder.put("uri", raw);
                    folder.put("name", customTreeDisplayName(uri));
                    folders.put(folder);
                } catch (Exception ignored) {}
            }
            JSONObject response = new JSONObject();
            try { response.put("folders", folders); } catch (Exception ignored) {}
            return response.toString();
        }

        @JavascriptInterface
        public String scanCustomMusicFolders() {
            return scanCustomMusicTrees().toString();
        }

        @JavascriptInterface
        public boolean removeCustomMusicFolder(String rawUri) {
            if (rawUri == null || rawUri.isEmpty()) return false;
            ArrayList<String> trees = getCustomMusicTreeUris();
            if (!trees.remove(rawUri)) return false;
            try {
                getContentResolver().releasePersistableUriPermission(Uri.parse(rawUri),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (Exception ignored) {}
            saveCustomMusicTreeUris(trees);
            return true;
        }

        @JavascriptInterface
        public String getDownloadDirectory() {
            return "Music/Molan Light Music";
        }

        @JavascriptInterface
        public String downloadUrl(String url, String fileName) {
            JSONObject out = new JSONObject();
            try {
                if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) {
                    out.put("state", "failed");
                    out.put("message", "下载地址无效");
                    return out.toString();
                }
                // Android 9 and lower require a runtime legacy storage grant for a public destination.
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                    checkSelfPermission("android.permission.WRITE_EXTERNAL_STORAGE") != PackageManager.PERMISSION_GRANTED) {
                    runOnUiThread(() -> requestPermissions(
                        new String[]{"android.permission.WRITE_EXTERNAL_STORAGE"}, REQUEST_CODE_LEGACY_STORAGE));
                    out.put("state", "permission_required");
                    out.put("message", "请允许存储权限后重新开始下载");
                    return out.toString();
                }
                String cleanName = safeAudioFileName(fileName);
                DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                request.setTitle(cleanName);
                request.setDescription("轻音正在下载");
                request.setMimeType("audio/mpeg");
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                request.setVisibleInDownloadsUi(true);
                request.setDestinationInExternalPublicDir(DOWNLOAD_DIR, DOWNLOAD_SUBDIR + "/" + cleanName);
                DownloadManager manager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
                if (manager == null) throw new IllegalStateException("系统下载服务不可用");
                long id = manager.enqueue(request);
                out.put("state", "queued");
                out.put("downloadId", String.valueOf(id));
                out.put("fileName", cleanName);
                out.put("directory", "Music/Molan Light Music");
            } catch (Exception e) {
                Log.e(TAG, "DownloadManager enqueue failed", e);
                try { out.put("state", "failed"); out.put("message", e.getMessage()); } catch (Exception ignored) {}
            }
            return out.toString();
        }

        @JavascriptInterface
        public String getDownloadStatus(String rawId) {
            try {
                long id = Long.parseLong(rawId);
                DownloadManager manager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
                if (manager == null) return "{\"state\":\"failed\",\"message\":\"系统下载服务不可用\"}";
                return getDownloadStatusObject(manager, id).toString();
            } catch (Exception e) {
                return "{\"state\":\"missing\"}";
            }
        }

        @JavascriptInterface
        public boolean removeDownload(String rawId) {
            try {
                long id = Long.parseLong(rawId);
                DownloadManager manager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
                return manager != null && manager.remove(id) > 0;
            } catch (Exception e) {
                return false;
            }
        }
    }
}
