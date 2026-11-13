package com.wool.ledger.util;

import android.app.DownloadManager;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;

import com.wool.ledger.data.Prefs;

/**
 * 本地语音模型（sherpa-onnx + Paraformer 中文）的状态、内置安装与网络兜底下载。
 *
 * 正常路径：模型随 APK 一起发（assets/voice-model/*.tar.bz2），首次使用直接解压到
 * getExternalFilesDir("voice-model/paraformer")，全程不联网。
 * 兜底路径：APK 里没内置（比如精简包）时才走 DownloadManager 下载。
 */
public class VoiceModel {

    /** Paraformer 中文小模型（sherpa-onnx 官方发布，2024-03-09 版） */
    public static final String NAME = "sherpa-onnx-paraformer-zh-small-2024-03-09.tar.bz2";
    public static final String URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/" + NAME;
    /** 压缩包 74.3 MB；解出来是 model.int8.onnx + tokens.txt 约 82 MB */
    public static final long SIZE_BYTES = 77920048L;
    public static final String DIR = "voice-model";
    public static final String FILE = NAME;
    /** APK 内置位置 */
    public static final String ASSET = "voice-model/" + NAME;

    /** 解压后大约占多少（82 MB 模型 + 词表，再留点余量） */
    public static final long NEED_BYTES = 200L * 1024 * 1024;

    /** APK 里有没有内置模型 */
    public static boolean bundled(Context c) {
        try (java.io.InputStream in = c.getAssets().open(ASSET)) {
            return in != null;
        } catch (Exception e) {
            return false;
        }
    }

    /** 把内置模型流式解压到 modelDir；成功返回模型目录，失败 null */
    public static java.io.File installBundled(Context c, ModelInstaller.Progress cb) {
        java.io.File dir;
        try (java.io.InputStream in = c.getAssets().open(ASSET)) {
            dir = ModelInstaller.install(c, in, SIZE_BYTES, cb);
        } catch (Exception e) {
            dir = null;
        }
        Prefs.setVoiceModelState(c, dir != null ? Prefs.VOICE_READY : Prefs.VOICE_NONE);
        return dir;
    }

    /** 压缩包已下载完（还没解压也算） */
    public static boolean downloaded(Context c) {
        java.io.File f = file(c);
        return f.exists() && f.length() > 20L * 1024 * 1024;
    }

    public static int state(Context c) {
        return Prefs.voiceModelState(c);
    }

    /** 模型文件齐了就算就绪；顺带把状态位补齐（装完被杀进程也不会卡在"未安装"） */
    public static boolean ready(Context c) {
        boolean ok = ModelInstaller.hasModel(com.wool.ledger.ai.SherpaAsr.modelDir(c));
        if (ok && state(c) != Prefs.VOICE_READY) {
            Prefs.setVoiceModelState(c, Prefs.VOICE_READY);
        }
        return ok;
    }

    public static java.io.File file(Context c) {
        java.io.File dir = c.getExternalFilesDir(DIR);
        if (dir == null) dir = new java.io.File(c.getFilesDir(), DIR);
        if (!dir.exists()) dir.mkdirs();
        return new java.io.File(dir, FILE);
    }

    /** 下载前的检查结果 */
    public static final int OK = 0;
    public static final int NO_WIFI = 1;
    public static final int NO_SPACE = 2;

    /** 解压/安装内置模型前看下空间够不够 */
    public static boolean hasSpaceForInstall(Context c) {
        return freeBytes(c) >= NEED_BYTES;
    }

    private static long freeBytes(Context c) {
        try {
            java.io.File dir = c.getExternalFilesDir(DIR);
            if (dir == null) dir = c.getFilesDir();
            android.os.StatFs stat = new android.os.StatFs(dir.getAbsolutePath());
            return stat.getAvailableBytes();
        } catch (Exception e) {
            return Long.MAX_VALUE;
        }
    }

    /** 检查网络类型与剩余空间（首装只解压，不联网；兜底下载才用得到网络判断） */
    public static int checkBeforeDownload(Context c) {
        if (com.wool.ledger.data.Prefs.voiceWifiOnly(c)) {
            try {
                android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                        c.getSystemService(Context.CONNECTIVITY_SERVICE);
                android.net.NetworkInfo info = cm == null ? null : cm.getActiveNetworkInfo();
                if (info == null || !info.isConnected()) return NO_WIFI;
                if (info.getType() != android.net.ConnectivityManager.TYPE_WIFI) return NO_WIFI;
            } catch (Exception ignored) {
                return NO_WIFI;
            }
        }
        // 压缩包 + 解压后的模型，留 300MB
        if (freeBytes(c) < 300L * 1024 * 1024) return NO_SPACE;
        return OK;
    }

    /** 文件是否完整（大小接近预期，允许 5% 误差） */
    public static boolean sizeOk(Context c) {
        java.io.File f = file(c);
        if (!f.exists()) return false;
        long len = f.length();
        return len >= SIZE_BYTES * 95 / 100 && len <= SIZE_BYTES * 105 / 100;
    }

    /** 开始下载，返回 DownloadManager 的 id（失败返回 -1） */
    public static long start(Context c) {
        try {
            DownloadManager dm = (DownloadManager) c.getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm == null) return -1;
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(URL))
                    .setTitle("羊毛账本 · 离线语音模型")
                    .setDescription("sherpa-onnx Paraformer 中文模型，约 78 MB")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalFilesDir(c, DIR, FILE)
                    .setAllowedOverMetered(true)
                    .setAllowedOverRoaming(false);
            long id = dm.enqueue(req);
            Prefs.setVoiceModelState(c, Prefs.VOICE_DOWNLOADING);
            Prefs.setVoiceDownloadId(c, id);
            return id;
        } catch (Exception e) {
            Prefs.setVoiceModelState(c, Prefs.VOICE_NONE);
            return -1;
        }
    }

    /** 查询进度（0..1，-1 表示查询不到） */
    public static float progress(Context c) {
        try {
            long id = Prefs.voiceDownloadId(c);
            if (id <= 0) return -1;
            DownloadManager dm = (DownloadManager) c.getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm == null) return -1;
            Cursor cur = dm.query(new DownloadManager.Query().setFilterById(id));
            if (cur == null) return -1;
            try {
                if (!cur.moveToFirst()) return -1;
                int status = cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                long total = cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                long done = cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    Prefs.setVoiceModelState(c, Prefs.VOICE_READY);
                    return 1f;
                }
                if (status == DownloadManager.STATUS_FAILED) {
                    Prefs.setVoiceModelState(c, Prefs.VOICE_NONE);
                    return -1;
                }
                return total > 0 ? Math.max(0f, Math.min(1f, done / (float) total)) : 0f;
            } finally {
                cur.close();
            }
        } catch (Exception e) {
            return -1;
        }
    }
}
