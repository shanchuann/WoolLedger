package com.wool.ledger.util;

import android.content.Context;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.InputStream;
import java.io.OutputStream;

/** 把 tar.bz2 模型解开，只留下 *.onnx 和 tokens.txt。 */
public class ModelInstaller {

    public interface Progress {
        void onProgress(int percent);
    }

    /**
     * 从任意输入流解模型：APK assets 里的内置压缩包、或者下载好的本地文件都走这里。
     * 不落中间文件，所以内置模型的峰值占用只有"解压后的体积"，不会占双份。
     */
    public static File install(Context c, InputStream raw, long totalBytes, Progress cb) {
        File outDir = com.wool.ledger.ai.SherpaAsr.modelDir(c);
        if (!outDir.exists() && !outDir.mkdirs()) return null;
        try (CountingInputStream count = new CountingInputStream(raw, totalBytes, cb);
             BufferedInputStream bis = new BufferedInputStream(count, 256 * 1024);
             BZip2CompressorInputStream bz = new BZip2CompressorInputStream(bis);
             TarArchiveInputStream tin = new TarArchiveInputStream(bz)) {
            TarArchiveEntry e;
            while ((e = tin.getNextTarEntry()) != null) {
                if (e.isDirectory()) continue;
                String name = new File(e.getName()).getName();
                // 老包叫 model.onnx，2024 之后叫 model.int8.onnx，统一按后缀收
                boolean keep = name.endsWith(".onnx") || name.equals("tokens.txt");
                if (!keep) continue;
                File out = new File(outDir, name);
                try (OutputStream os = new FileOutputStream(out)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = tin.read(buf)) > 0) os.write(buf, 0, n);
                }
            }
        } catch (Throwable ex) {
            // 解压偶发 Error（依赖缺类、native 崩）也别把 App 带走
            return null;
        }
        return hasModel(outDir) ? outDir : null;
    }

    /** 解一个本地压缩包，成功后删掉压缩包省空间（原来的下载路径） */
    public static File install(Context c, File archive, Progress cb) {
        if (archive == null || !archive.exists()) return null;
        File out = null;
        try (InputStream in = new FileInputStream(archive)) {
            out = install(c, in, archive.length(), cb);
        } catch (Exception ignored) {
        }
        if (out != null) {
            try { archive.delete(); } catch (Exception ignored) { }
        }
        return out;
    }

    public static boolean hasModel(File dir) {
        if (dir == null || !dir.exists()) return false;
        boolean onnx = false, tokens = false;
        File[] fs = dir.listFiles();
        if (fs == null) return false;
        for (File f : fs) {
            if (f.getName().endsWith(".onnx")) onnx = true;
            if (f.getName().equals("tokens.txt")) tokens = true;
        }
        return onnx && tokens;
    }

    /** 解压进度按"读掉的压缩字节 / 总字节"算，回来的是 0..99 */
    private static class CountingInputStream extends FilterInputStream {
        private final long total;
        private final Progress cb;
        private long read;
        private int last = -1;

        CountingInputStream(InputStream in, long total, Progress cb) {
            super(in);
            this.total = total;
            this.cb = cb;
        }

        @Override
        public int read() throws java.io.IOException {
            int r = super.read();
            if (r >= 0) bump(1);
            return r;
        }

        @Override
        public int read(byte[] b, int off, int len) throws java.io.IOException {
            int n = super.read(b, off, len);
            if (n > 0) bump(n);
            return n;
        }

        private void bump(int n) {
            read += n;
            if (cb == null || total <= 0) return;
            int p = (int) Math.min(99L, read * 100L / total);
            if (p != last) {
                last = p;
                cb.onProgress(p);
            }
        }
    }
}
