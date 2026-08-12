package com.zapstore.goapk.runtime;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Minimal, app-private QQ Music session implementation. It does not expose raw QQ
 * cookies to JavaScript. This class is independently implemented around public web
 * request flows and only returns QR images, state, profile and normalized playlists.
 */
final class QQMusicSession {
    private static final String TAG = "MolanQQ";
    private static final String PREFS = "molan_qq_session";
    private static final String PREF_COOKIE = "cipher_cookie";
    private static final String PREF_PROFILE = "cipher_profile";
    private static final String KEY_ALIAS = "molan_qq_session_key_v1";
    private static final long QR_TTL_MS = 4 * 60 * 1000L;
    private static final String UA = "Mozilla/5.0 (Linux; Android 12; Molan Light Music) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36";

    private final Context context;
    private final SharedPreferences prefs;
    private final Map<String, QrSession> qrSessions = new ConcurrentHashMap<>();

    private static final class QrSession {
        final String qrsig;
        final long createdAt;
        QrSession(String qrsig) { this.qrsig = qrsig; this.createdAt = System.currentTimeMillis(); }
    }

    private static final class Response {
        final int code;
        final byte[] bytes;
        final Map<String, List<String>> headers;
        Response(int code, byte[] bytes, Map<String, List<String>> headers) {
            this.code = code; this.bytes = bytes; this.headers = headers == null ? Collections.emptyMap() : headers;
        }
        String text() { return new String(bytes, StandardCharsets.UTF_8); }
    }

    QQMusicSession(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    synchronized String createQr() {
        JSONObject out = new JSONObject();
        try {
            String endpoint = "https://ssl.ptlogin2.qq.com/ptqrshow?appid=716027609&e=2&l=M&s=3&d=72&v=4&daid=383&pt_3rd_aid=100497308&t=" + System.currentTimeMillis();
            Response response = request("GET", endpoint, null, null, null, true);
            if (response.code < 200 || response.code >= 300 || response.bytes.length < 32) throw new IllegalStateException("二维码请求失败 HTTP " + response.code);
            String qrsig = cookiesFrom(response.headers).get("qrsig");
            if (qrsig == null || qrsig.isEmpty()) throw new IllegalStateException("QQ 未返回二维码会话");
            String sessionId = UUID.randomUUID().toString();
            qrSessions.put(sessionId, new QrSession(qrsig));
            pruneQrSessions();
            out.put("state", "ready");
            out.put("sessionId", sessionId);
            out.put("qrImage", "data:image/png;base64," + Base64.encodeToString(response.bytes, Base64.NO_WRAP));
            return out.toString();
        } catch (Exception e) {
            Log.w(TAG, "QQ QR creation failed", e);
            try { out.put("state", "failed"); out.put("message", safeMessage(e)); } catch (Exception ignored) {}
            return out.toString();
        }
    }

    synchronized String checkQr(String sessionId) {
        JSONObject out = new JSONObject();
        try {
            QrSession session = qrSessions.get(sessionId == null ? "" : sessionId);
            if (session == null || System.currentTimeMillis() - session.createdAt > QR_TTL_MS) {
                if (sessionId != null) qrSessions.remove(sessionId);
                out.put("state", "expired"); out.put("message", "二维码已过期，请重新获取"); return out.toString();
            }
            String poll = "https://ssl.ptlogin2.qq.com/ptqrlogin?u1=" + enc("https://graph.qq.com/oauth2.0/login_jump")
                + "&ptqrtoken=" + hash33(session.qrsig)
                + "&ptredirect=0&h=1&t=1&g=1&from_ui=1&ptlang=2502&action=0-0-" + System.currentTimeMillis()
                + "&js_ver=22080914&js_type=1&login_sig=&pt_uistyle=40&aid=716027609&daid=383&pt_3rd_aid=100497308";
            Map<String, String> headers = new HashMap<>();
            headers.put("Referer", "https://xui.ptlogin2.qq.com/"); headers.put("User-Agent", UA);
            Response response = request("GET", poll, null, "qrsig=" + session.qrsig, headers, false);
            String body = response.text();
            String code = callbackCode(body);
            if ("66".equals(code)) { out.put("state", "waiting"); return out.toString(); }
            if ("67".equals(code)) { out.put("state", "scanned"); return out.toString(); }
            if ("65".equals(code)) { qrSessions.remove(sessionId); out.put("state", "expired"); return out.toString(); }
            if (!"0".equals(code)) { out.put("state", "failed"); out.put("message", "QQ 登录状态异常：" + (code.isEmpty() ? "未知" : code)); return out.toString(); }
            String redirectUrl = callbackRedirectUrl(body);
            if (redirectUrl.isEmpty()) throw new IllegalStateException("QQ 未返回登录跳转地址");
            String cookie = finishLogin(session.qrsig, response.headers, redirectUrl);
            JSONObject profile = fetchProfile(cookie);
            if (profile == null || profile.optString("id").isEmpty()) throw new IllegalStateException("QQ 登录成功但未能读取账户资料");
            saveSession(cookie, profile);
            qrSessions.remove(sessionId);
            out.put("state", "success"); out.put("profile", profile);
            return out.toString();
        } catch (Exception e) {
            Log.w(TAG, "QQ QR check failed", e);
            try { out.put("state", "failed"); out.put("message", safeMessage(e)); } catch (Exception ignored) {}
            return out.toString();
        }
    }

    synchronized String account() {
        JSONObject out = new JSONObject();
        try {
            String cookie = loadCookie();
            if (cookie.isEmpty()) { out.put("loggedIn", false); return out.toString(); }
            JSONObject profile = loadProfile();
            if (profile == null || profile.optString("id").isEmpty()) profile = fetchProfile(cookie);
            if (profile == null || profile.optString("id").isEmpty()) {
                clearSession(); out.put("loggedIn", false); out.put("expired", true); return out.toString();
            }
            saveSession(cookie, profile);
            out.put("loggedIn", true); out.put("profile", profile); return out.toString();
        } catch (Exception e) {
            try { out.put("loggedIn", false); out.put("message", safeMessage(e)); } catch (Exception ignored) {}
            return out.toString();
        }
    }

    synchronized String myPlaylists() {
        JSONObject out = new JSONObject();
        try {
            String cookie = loadCookie();
            if (cookie.isEmpty()) { out.put("state", "login_required"); out.put("playlists", new JSONArray()); return out.toString(); }
            Response response = request("GET", "https://c.y.qq.com/rsc/fcgi-bin/fcg_get_profile_homepage.fcg?cid=205360838&reqfrom=1", null, cookie, refererHeaders(), true);
            JSONObject root = new JSONObject(response.text());
            JSONObject data = root.optJSONObject("data");
            if (data == null) throw new IllegalStateException("QQ 未返回歌单数据");
            JSONArray playlists = new JSONArray();
            appendPlaylists(playlists, data.optJSONArray("mymusic"));
            JSONObject mydiss = data.optJSONObject("mydiss");
            if (mydiss != null) appendPlaylists(playlists, mydiss.optJSONArray("list"));
            JSONObject profile = profileFromData(data);
            if (profile != null && !profile.optString("id").isEmpty()) saveSession(cookie, profile);
            out.put("state", "ready"); out.put("playlists", playlists); return out.toString();
        } catch (Exception e) {
            Log.w(TAG, "QQ playlists failed", e);
            try { out.put("state", "failed"); out.put("message", safeMessage(e)); out.put("playlists", new JSONArray()); } catch (Exception ignored) {}
            return out.toString();
        }
    }

    synchronized String playlistDetail(String rawId) {
        JSONObject out = new JSONObject();
        try {
            String id = rawId == null ? "" : rawId.replaceFirst("^qq:", "").trim();
            if (!id.matches("[0-9]+")) throw new IllegalArgumentException("QQ 歌单 ID 无效");
            String cookie = loadCookie();
            String endpoint = "https://i.y.qq.com/qzone-music/fcg-bin/fcg_ucc_getcdinfo_byids_cp.fcg?type=1&json=1&utf8=1&onlysong=0&nosign=1&disstid=" + id + "&g_tk=5381&loginUin=0&hostUin=0&format=json&inCharset=GB2312&outCharset=utf-8&notice=0&platform=yqq&needNewCode=0";
            Response response = request("GET", endpoint, null, cookie, refererHeaders(), true);
            String body = response.text().trim();
            if (body.startsWith("callback(")) body = body.substring("callback(".length(), body.length() - 1);
            JSONObject root = new JSONObject(body); JSONArray lists = root.optJSONArray("cdlist");
            JSONObject source = lists == null ? null : lists.optJSONObject(0);
            if (source == null) throw new IllegalStateException("QQ 未返回歌单详情");
            JSONObject playlist = new JSONObject();
            playlist.put("id", "qq:" + id); playlist.put("rawId", id); playlist.put("source", "qq"); playlist.put("sourceLabel", "QQ音乐");
            playlist.put("name", source.optString("dissname", "QQ 歌单")); playlist.put("coverImgUrl", https(source.optString("logo", "")));
            playlist.put("trackCount", source.optInt("total_song_num", 0)); playlist.put("creator", source.optString("nickname", "QQ音乐"));
            JSONArray tracks = new JSONArray(), songList = source.optJSONArray("songlist");
            if (songList != null) for (int i = 0; i < songList.length(); i++) {
                JSONObject song = songList.optJSONObject(i); if (song == null) continue;
                String mid = song.optString("songmid", song.optString("mid", "")); if (mid.isEmpty()) continue;
                JSONArray singers = song.optJSONArray("singer"); ArrayList<String> names = new ArrayList<>();
                if (singers != null) for (int j = 0; j < singers.length(); j++) { JSONObject singer = singers.optJSONObject(j); if (singer != null && !singer.optString("name").isEmpty()) names.add(singer.optString("name")); }
                JSONObject album = song.optJSONObject("album"); String albumMid = album == null ? "" : album.optString("mid", "");
                JSONObject track = new JSONObject(); track.put("id", "qq:" + mid); track.put("qqMid", mid); track.put("source", "qq"); track.put("name", song.optString("songname", song.optString("name", "未知歌曲")));
                track.put("artists", android.text.TextUtils.join(" / ", names)); track.put("album", album == null ? "" : album.optString("name", ""));
                track.put("duration", Math.max(0, song.optLong("interval", 0)) * 1000L);
                track.put("picUrl", albumMid.isEmpty() ? "" : "https://y.gtimg.cn/music/photo_new/T002R300x300M000" + albumMid + ".jpg"); tracks.put(track);
            }
            playlist.put("tracks", tracks); if (playlist.optInt("trackCount", 0) <= 0) playlist.put("trackCount", tracks.length());
            out.put("state", "ready"); out.put("playlist", playlist); return out.toString();
        } catch (Exception e) {
            Log.w(TAG, "QQ playlist detail failed", e);
            try { out.put("state", "failed"); out.put("message", safeMessage(e)); } catch (Exception ignored) {}
            return out.toString();
        }
    }

    synchronized String logout() {
        clearSession();
        JSONObject out = new JSONObject();
        try { out.put("ok", true); } catch (Exception ignored) {}
        return out.toString();
    }

    private String finishLogin(String qrsig, Map<String, List<String>> pollHeaders, String redirectUrl) throws Exception {
        Map<String, String> merged = new HashMap<>();
        merged.put("qrsig", qrsig); merged.putAll(cookiesFrom(pollHeaders));
        Response checkSig = request("GET", redirectUrl, null, formatCookies(merged), refererHeaders(), false);
        merged.putAll(cookiesFrom(checkSig.headers));
        String pSkey = merged.get("p_skey");
        if (pSkey == null) pSkey = "";
        long gTk = gtk(pSkey);
        String form = "response_type=code&client_id=100497308&redirect_uri=" + enc("https://y.qq.com/portal/wx_redirect.html?login_type=1&surl=https://y.qq.com/")
            + "&scope=all&state=state&switch=&from_ptlogin=1&src=1&update_auth=1&openapi=80901010_1030&g_tk=" + gTk
            + "&auth_time=" + System.currentTimeMillis() + "&ui=DFEC5395-9E69-4D3E-96A6-300BB770874D";
        Response authorize = request("POST", "https://graph.qq.com/oauth2.0/authorize", form.getBytes(StandardCharsets.UTF_8), formatCookies(merged), formHeaders(), false);
        merged.putAll(cookiesFrom(authorize.headers));
        String location = header(authorize.headers, "Location");
        String authCode = queryValue(location, "code");
        if (authCode.isEmpty()) throw new IllegalStateException("QQ 授权码获取失败");
        JSONObject comm = new JSONObject(); comm.put("g_tk", 5381); comm.put("platform", "yqq"); comm.put("ct", 24); comm.put("cv", 0);
        JSONObject param = new JSONObject(); param.put("code", authCode);
        JSONObject req = new JSONObject(); req.put("module", "QQConnectLogin.LoginServer"); req.put("method", "QQLogin"); req.put("param", param);
        JSONObject payload = new JSONObject(); payload.put("comm", comm); payload.put("req", req);
        Response music = request("POST", "https://u.y.qq.com/cgi-bin/musicu.fcg", payload.toString().getBytes(StandardCharsets.UTF_8), formatCookies(merged), jsonHeaders(), true);
        merged.putAll(cookiesFrom(music.headers));
        String cookie = formatCookies(merged);
        if (cookie.isEmpty()) throw new IllegalStateException("QQ 音乐会话创建失败");
        return cookie;
    }

    private JSONObject fetchProfile(String cookie) throws Exception {
        Response response = request("GET", "https://c.y.qq.com/rsc/fcgi-bin/fcg_get_profile_homepage.fcg?cid=205360838&reqfrom=1", null, cookie, refererHeaders(), true);
        JSONObject data = new JSONObject(response.text()).optJSONObject("data");
        return profileFromData(data);
    }

    private JSONObject profileFromData(JSONObject data) throws Exception {
        if (data == null) return null;
        JSONObject creator = data.optJSONObject("creator");
        if (creator == null) return null;
        String id = creator.optString("encrypt_uin", creator.optString("uin", ""));
        if (id.isEmpty()) return null;
        JSONObject profile = new JSONObject();
        profile.put("id", id); profile.put("name", creator.optString("nick", "QQ 用户"));
        profile.put("avatar", creator.optString("headpic", "")); profile.put("source", "qq");
        return profile;
    }

    private void appendPlaylists(JSONArray target, JSONArray source) throws Exception {
        if (source == null) return;
        for (int i = 0; i < source.length(); i++) {
            JSONObject item = source.optJSONObject(i); if (item == null) continue;
            String id = item.optString("dissid", item.optString("id", ""));
            if (id.isEmpty()) continue;
            JSONObject playlist = new JSONObject();
            playlist.put("id", "qq:" + id); playlist.put("rawId", id);
            playlist.put("source", "qq"); playlist.put("sourceLabel", "QQ音乐");
            playlist.put("name", item.optString("title", item.optString("dissname", "QQ 歌单")));
            playlist.put("coverImgUrl", https(item.optString("picurl", item.optString("logo", ""))));
            playlist.put("trackCount", item.optInt("songnum", item.optInt("song_num", 0)));
            playlist.put("creator", "QQ音乐"); target.put(playlist);
        }
    }

    private Response request(String method, String rawUrl, byte[] body, String cookie, Map<String, String> extra, boolean followRedirects) throws Exception {
        URL url = new URL(rawUrl); HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(12000); conn.setReadTimeout(18000); conn.setInstanceFollowRedirects(followRedirects); conn.setRequestMethod(method);
        conn.setRequestProperty("User-Agent", UA); conn.setRequestProperty("Accept", "application/json, text/plain, */*");
        if (cookie != null && !cookie.isEmpty()) conn.setRequestProperty("Cookie", cookie);
        if (extra != null) for (Map.Entry<String, String> e : extra.entrySet()) conn.setRequestProperty(e.getKey(), e.getValue());
        if (body != null) { conn.setDoOutput(true); conn.setFixedLengthStreamingMode(body.length); OutputStream os = conn.getOutputStream(); os.write(body); os.close(); }
        int code = conn.getResponseCode(); InputStream input = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream(); if (input != null) { byte[] buf = new byte[8192]; int n; while ((n = input.read(buf)) >= 0) out.write(buf, 0, n); input.close(); }
        Map<String, List<String>> headers = conn.getHeaderFields(); conn.disconnect(); return new Response(code, out.toByteArray(), headers);
    }

    private Map<String, String> refererHeaders() { Map<String, String> h = new HashMap<>(); h.put("Referer", "https://y.qq.com/"); return h; }
    private Map<String, String> formHeaders() { Map<String, String> h = refererHeaders(); h.put("Content-Type", "application/x-www-form-urlencoded"); return h; }
    private Map<String, String> jsonHeaders() { Map<String, String> h = refererHeaders(); h.put("Content-Type", "application/json"); return h; }
    private static String https(String url) { return url == null ? "" : url.replaceFirst("^http://", "https://"); }
    private static String enc(String value) throws Exception { return URLEncoder.encode(value, "UTF-8"); }
    private static String safeMessage(Exception e) { String m = e.getMessage(); return m == null || m.trim().isEmpty() ? "QQ 登录请求失败" : m; }
    private static int hash33(String value) { int h = 0; for (int i = 0; i < value.length(); i++) h += (h << 5) + value.charAt(i); return h & 0x7fffffff; }
    private static long gtk(String value) { long h = 5381; for (int i = 0; i < value.length(); i++) h += (h << 5) + value.charAt(i); return h & 0x7fffffff; }
    private static String callbackCode(String body) { java.util.regex.Matcher m = java.util.regex.Pattern.compile("ptuiCB\\('?(\\d+)'?").matcher(body); return m.find() ? m.group(1) : ""; }
    private static String callbackRedirectUrl(String body) { java.util.regex.Matcher m = java.util.regex.Pattern.compile("ptuiCB\\('0','[^']*','([^']*)'").matcher(body); return m.find() ? m.group(1) : ""; }
    private static String queryValue(String url, String key) { if (url == null) return ""; try { String q = new URL(url).getQuery(); if (q == null) return ""; for (String pair : q.split("&")) { String[] p = pair.split("=", 2); if (p.length == 2 && key.equals(p[0])) return java.net.URLDecoder.decode(p[1], "UTF-8"); } } catch (Exception ignored) {} return ""; }
    private static String header(Map<String, List<String>> headers, String name) { if (headers == null) return ""; for (Map.Entry<String, List<String>> e : headers.entrySet()) if (e.getKey() != null && name.equalsIgnoreCase(e.getKey()) && e.getValue() != null && !e.getValue().isEmpty()) return e.getValue().get(0); return ""; }
    private static Map<String, String> cookiesFrom(Map<String, List<String>> headers) { Map<String, String> result = new HashMap<>(); if (headers == null) return result; for (Map.Entry<String, List<String>> e : headers.entrySet()) { if (e.getKey() == null || !"Set-Cookie".equalsIgnoreCase(e.getKey()) || e.getValue() == null) continue; for (String line : e.getValue()) { if (line == null) continue; String first = line.split(";", 2)[0]; int at = first.indexOf('='); if (at > 0) result.put(first.substring(0, at).trim(), first.substring(at + 1).trim()); } } return result; }
    private static String formatCookies(Map<String, String> cookies) { ArrayList<String> parts = new ArrayList<>(); for (Map.Entry<String, String> e : cookies.entrySet()) if (e.getKey() != null && !e.getKey().isEmpty() && e.getValue() != null) parts.add(e.getKey() + "=" + e.getValue()); return android.text.TextUtils.join("; ", parts); }
    private void pruneQrSessions() { long now = System.currentTimeMillis(); for (Map.Entry<String, QrSession> e : qrSessions.entrySet()) if (now - e.getValue().createdAt > QR_TTL_MS) qrSessions.remove(e.getKey()); }

    private SecretKey sessionKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore"); keyStore.load(null);
        if (keyStore.containsAlias(KEY_ALIAS)) return ((KeyStore.SecretKeyEntry) keyStore.getEntry(KEY_ALIAS, null)).getSecretKey();
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build();
        generator.init(spec); return generator.generateKey();
    }
    private String encrypt(String plain) throws Exception { Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, sessionKey()); return Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":" + Base64.encodeToString(cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP); }
    private String decrypt(String packed) throws Exception { String[] parts = packed == null ? new String[0] : packed.split(":", 2); if (parts.length != 2) return ""; Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE, sessionKey(), new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP))); return new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8); }
    private void saveSession(String cookie, JSONObject profile) throws Exception { prefs.edit().putString(PREF_COOKIE, encrypt(cookie)).putString(PREF_PROFILE, encrypt(profile.toString())).apply(); }
    private String loadCookie() throws Exception { return decrypt(prefs.getString(PREF_COOKIE, "")); }
    private JSONObject loadProfile() throws Exception { String raw = decrypt(prefs.getString(PREF_PROFILE, "")); return raw.isEmpty() ? null : new JSONObject(raw); }
    private void clearSession() { prefs.edit().remove(PREF_COOKIE).remove(PREF_PROFILE).apply(); }
}
