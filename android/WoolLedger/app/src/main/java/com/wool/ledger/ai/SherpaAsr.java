package com.wool.ledger.ai;

import android.content.Context;

import java.io.File;
import java.lang.reflect.Constructor;

/**
 * 本地 Paraformer 识别封装。
 * 用反射调用 sherpa-onnx 的 Java 接口，这样工程里没有 AAR 时也能编译、打包，
 * 把 sherpa-onnx.aar 丢进 app/libs 之后即可真正走本地推理。
 *
 * 注意：sherpa-onnx 1.10 之后 Java 接口是用 Kotlin 写的 data class（公开无参构造 + getter/setter），
 * 老教程里的 OfflineRecognizerConfig\$Builder 之类早就不存在了，按 Builder 反射会
 * ClassNotFoundException（v1.34 模拟器实测踩过）。这些 Kotlin 类还要用
 * kotlin.jvm.internal.Intrinsics，所以 build.gradle 必须带 kotlin-stdlib。
 */
public class SherpaAsr {

    private static final String TAG = "WoolAsr";
    private Object recognizer;
    private boolean available;

    public static boolean libPresent() {
        try {
            Class.forName("com.k2fsa.sherpa.onnx.OfflineRecognizer");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 模型目录：解压后的 paraformer 模型（model.int8.onnx / tokens.txt） */
    public static File modelDir(Context c) {
        java.io.File dir = c.getExternalFilesDir("voice-model/paraformer");
        if (dir == null) dir = new java.io.File(c.getFilesDir(), "voice-model/paraformer");
        return dir;
    }

    /** 老包叫 model.onnx，2024 之后的包叫 model.int8.onnx；哪个在就用哪个 */
    public static File modelFile(File dir) {
        if (dir == null) return null;
        File int8 = new File(dir, "model.int8.onnx");
        if (int8.exists()) return int8;
        File plain = new File(dir, "model.onnx");
        if (plain.exists()) return plain;
        File[] fs = dir.listFiles();
        if (fs != null) {
            for (File f : fs) if (f.getName().endsWith(".onnx")) return f;
        }
        return null;
    }

    public boolean init(Context c) {
        if (recognizer != null) return available;
        try {
            File dir = modelDir(c);
            File model = modelFile(dir);
            File tokens = new File(dir, "tokens.txt");
            if (model == null || !tokens.exists()) return false;

            Class<?> cfgCls = Class.forName("com.k2fsa.sherpa.onnx.OfflineRecognizerConfig");
            Class<?> modelCls = Class.forName("com.k2fsa.sherpa.onnx.OfflineModelConfig");
            Class<?> paraCls = Class.forName("com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig");
            Class<?> recCls = Class.forName("com.k2fsa.sherpa.onnx.OfflineRecognizer");

            // OfflineParaformerModelConfig(model = "...")
            Object para = paraCls.getDeclaredConstructor().newInstance();
            paraCls.getMethod("setModel", String.class).invoke(para, model.getAbsolutePath());

            // OfflineModelConfig(paraformer = ..., tokens = ..., numThreads = 2, provider = "cpu")
            Object modelCfg = modelCls.getDeclaredConstructor().newInstance();
            modelCls.getMethod("setParaformer", paraCls).invoke(modelCfg, para);
            modelCls.getMethod("setTokens", String.class).invoke(modelCfg, tokens.getAbsolutePath());
            modelCls.getMethod("setNumThreads", int.class).invoke(modelCfg, 2);
            modelCls.getMethod("setProvider", String.class).invoke(modelCfg, "cpu");

            // OfflineRecognizerConfig(modelConfig = ..., decodingMethod = "greedy_search")
            Object cfg = cfgCls.getDeclaredConstructor().newInstance();
            cfgCls.getMethod("setModelConfig", modelCls).invoke(cfg, modelCfg);
            cfgCls.getMethod("setDecodingMethod", String.class).invoke(cfg, "greedy_search");

            // 模型在 /sdcard 绝对路径上，assetManager 必须给 null：
            // 传了 Context.getAssets()，sherpa 会按"asset 资源"去读，找不到就 C++ 里 exit(-1)，
            // 这个 fatal 在 Java 层 catch(Throwable) 根本抓不到（v1.34 模拟器实测踩过）。
            Constructor<?> ctor = recCls.getDeclaredConstructor(
                    android.content.res.AssetManager.class, cfgCls);
            recognizer = ctor.newInstance((Object) null, cfg);
            available = true;
            com.wool.ledger.data.Prefs.setVoiceVerified(c, true);
            android.util.Log.i(TAG, "本地模型加载成功：" + model.getName()
                    + " / " + tokens.length() + "B 词表");
            return true;
        } catch (Throwable t) {
            android.util.Log.w(TAG, "本地模型加载失败：" + t);
            available = false;
            recognizer = null;
            com.wool.ledger.data.Prefs.setVoiceVerified(c, false);
            return false;
        }
    }

    public boolean isAvailable() {
        return available;
    }

    /** 16k float PCM → 文本（失败返回 null） */
    public String recognize(float[] samples) {
        if (!available || recognizer == null) return null;
        Object stream = null;
        try {
            stream = recognizer.getClass().getMethod("createStream").invoke(recognizer);
            stream.getClass().getMethod("acceptWaveform", float[].class, int.class)
                    .invoke(stream, samples, 16000);
            // 1.10+ 叫 decode(stream)，更老的版本叫 decodeStream(stream)
            try {
                recognizer.getClass().getMethod("decode", stream.getClass()).invoke(recognizer, stream);
            } catch (NoSuchMethodException older) {
                recognizer.getClass().getMethod("decodeStream", stream.getClass()).invoke(recognizer, stream);
            }
            Object result = recognizer.getClass().getMethod("getResult", stream.getClass())
                    .invoke(recognizer, stream);
            if (result == null) return null;
            // Kotlin data class 是 getText()；老 Java 版是 public String text
            String text;
            try {
                text = (String) result.getClass().getMethod("getText").invoke(result);
            } catch (NoSuchMethodException older) {
                Object t = result.getClass().getField("text").get(result);
                text = t == null ? null : t.toString();
            }
            return text == null ? null : text.trim();
        } catch (Throwable t) {
            android.util.Log.w(TAG, "识别失败：" + t);
            return null;
        } finally {
            if (stream != null) {
                try {
                    stream.getClass().getMethod("release").invoke(stream);
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
