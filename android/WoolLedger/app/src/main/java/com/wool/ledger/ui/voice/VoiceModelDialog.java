package com.wool.ledger.ui.voice;

import android.app.Dialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.wool.ledger.R;
import com.wool.ledger.data.Prefs;
import com.wool.ledger.util.VoiceModel;

/** 首次用语音时的模型弹窗：APK 内置的直接解压，没有内置的才走下载。 */
public class VoiceModelDialog {

    public interface OnDone {
        void onReady();

        void onSkip();
    }

    public static void show(Context ctx, OnDone done) {
        final Dialog d = new Dialog(ctx);
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        View v = LayoutInflater.from(ctx).inflate(R.layout.dialog_voice_model, null);
        d.setContentView(v);
        d.setCancelable(false);
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
            d.getWindow().setLayout((int) (ctx.getResources().getDisplayMetrics().widthPixels * 0.86f),
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        TextView title = v.findViewById(R.id.vm_title);
        TextView body = v.findViewById(R.id.vm_body);
        TextView state = v.findViewById(R.id.vm_state);
        ProgressBar bar = v.findViewById(R.id.vm_progress);
        TextView cancel = v.findViewById(R.id.vm_cancel);
        TextView download = v.findViewById(R.id.vm_download);

        final Handler h = new Handler(Looper.getMainLooper());
        final boolean[] busy = {false};
        final Runnable[] tick = new Runnable[1];

        if (VoiceModel.ready(ctx)) {
            title.setText("本地模型已就绪");
            body.setText("已装好 sherpa-onnx Paraformer 中文模型，语音识别在这台手机上完成，断网也能记账。");
            download.setText("开始使用");
            cancel.setVisibility(View.GONE);
            download.setOnClickListener(x -> {
                d.dismiss();
                done.onReady();
            });
        } else if (VoiceModel.bundled(ctx)) {
            /* ---------- 内置模型：解压一次即可，全程不联网 ---------- */
            title.setText("装一下离线语音模型");
            body.setText("模型已经随 App 一起装进来了，不用下载。第一次用要先解压到手机上，占 80 MB 左右，十几秒就好。装完断网也能认，说话不出这台手机。");
            download.setText("现在装上");
            cancel.setText("暂不使用");
            cancel.setOnClickListener(x -> {
                d.dismiss();
                done.onSkip();
            });
            download.setOnClickListener(x -> {
                if (busy[0]) return;
                if (!VoiceModel.hasSpaceForInstall(ctx)) {
                    state.setVisibility(View.VISIBLE);
                    state.setText("存储空间不够（要 200 MB 左右），清一点再来");
                    return;
                }
                busy[0] = true;
                d.setCancelable(false);
                cancel.setVisibility(View.GONE);
                title.setText("正在解压内置模型");
                body.setText("在手机里解开就行，不用联网。");
                download.setText("安装中…");
                download.setAlpha(0.6f);
                bar.setVisibility(View.VISIBLE);
                bar.setProgress(0);
                state.setVisibility(View.VISIBLE);
                state.setText("0%");
                new Thread(() -> {
                    java.io.File dir = VoiceModel.installBundled(ctx, pct -> h.post(() -> {
                        bar.setProgress(pct);
                        state.setText(pct + "%");
                    }));
                    h.post(() -> {
                        busy[0] = false;
                        if (dir != null) {
                            bar.setProgress(100);
                            title.setText("本地模型已就绪");
                            body.setText("以后没网也能记账。识别都在这台手机上做，语音不会传出去。");
                            state.setText("装好了");
                            download.setText("开始使用");
                            download.setAlpha(1f);
                            download.setOnClickListener(y -> {
                                d.dismiss();
                                done.onReady();
                            });
                        } else {
                            title.setText("没装成功");
                            body.setText("可能是空间不够或者包被清理了。");
                            state.setText("再试一次，或到「我的 → 本地语音模型」里重装");
                            download.setText("重试");
                            download.setAlpha(1f);
                            download.setOnClickListener(y -> {
                                d.dismiss();
                                show(ctx, done);
                            });
                        }
                    });
                }).start();
            });
        } else {
            /* ---------- 兜底：APK 里没有内置模型时才下载 ---------- */
            body.setText("使用 sherpa-onnx Paraformer 中文模型，约 78 MB，只需下载一次。下载后断网也能识别，语音不会离开这台设备。");
            download.setOnClickListener(x -> {
                if (busy[0]) return;
                int chk = VoiceModel.checkBeforeDownload(ctx);
                if (chk == VoiceModel.NO_WIFI) {
                    state.setVisibility(View.VISIBLE);
                    state.setText("现在不是 Wi-Fi，78 MB 会走流量。连上 Wi-Fi 再试，或到「我的 → 本地语音模型」关掉「仅 Wi-Fi 下载」");
                    return;
                }
                if (chk == VoiceModel.NO_SPACE) {
                    state.setVisibility(View.VISIBLE);
                    state.setText("存储空间不足（至少要 300 MB），清理后再试");
                    return;
                }
                busy[0] = true;
                long id = VoiceModel.start(ctx);
                if (id < 0) {
                    state.setVisibility(View.VISIBLE);
                    state.setText("无法启动下载，请检查网络后重试");
                    busy[0] = false;
                    return;
                }
                bar.setVisibility(View.VISIBLE);
                state.setVisibility(View.VISIBLE);
                state.setText("正在下载 · 0%");
                title.setText("正在下载模型");
                download.setText("下载中…");
                download.setAlpha(0.6f);
                tick[0] = new Runnable() {
                    @Override
                    public void run() {
                        float p = VoiceModel.progress(ctx);
                        if (p >= 0) {
                            int pct = Math.round(p * 100);
                            bar.setProgress(pct);
                            state.setText("正在下载 · " + pct + "%");
                        }
                        if (VoiceModel.downloaded(ctx) && !VoiceModel.sizeOk(ctx)) {
                            h.removeCallbacks(tick[0]);
                            state.setText("包不完整，请删掉后重新下载");
                            download.setText("重新下载");
                            download.setAlpha(1f);
                            busy[0] = false;
                            download.setOnClickListener(y -> {
                                VoiceModel.file(ctx).delete();
                                Prefs.setVoiceModelState(ctx, Prefs.VOICE_NONE);
                                d.dismiss();
                                show(ctx, done);
                            });
                            return;
                        }
                        if (VoiceModel.downloaded(ctx)) {
                            h.removeCallbacks(tick[0]);
                            bar.setProgress(100);
                            title.setText("正在解压模型");
                            state.setText("解压中，稍等");
                            download.setText("解压中…");
                            download.setAlpha(0.6f);
                            new Thread(() -> {
                                java.io.File dir = com.wool.ledger.util.ModelInstaller.install(
                                        ctx, VoiceModel.file(ctx), percent -> h.post(() -> {
                                            bar.setProgress(percent);
                                            state.setText("解压中 · " + percent + "%");
                                        }));
                                h.post(() -> {
                                    if (dir != null) {
                                        Prefs.setVoiceModelState(ctx, Prefs.VOICE_READY);
                                        title.setText("本地模型已就绪");
                                        state.setText("解压完成");
                                        download.setText("开始使用");
                                        download.setAlpha(1f);
                                        busy[0] = false;
                                        download.setOnClickListener(y -> {
                                            d.dismiss();
                                            done.onReady();
                                        });
                                    } else {
                                        state.setText("解压失败，可重试下载");
                                        download.setText("重试");
                                        download.setAlpha(1f);
                                        busy[0] = false;
                                    }
                                });
                            }).start();
                            return;
                        }
                        h.postDelayed(tick[0], 600);
                    }
                };
                h.postDelayed(tick[0], 600);
            });
            cancel.setOnClickListener(x -> {
                if (tick[0] != null) h.removeCallbacks(tick[0]);
                d.dismiss();
                done.onSkip();
            });
        }
        d.setOnDismissListener(x -> {
            if (tick[0] != null) h.removeCallbacks(tick[0]);
        });
        d.show();
    }
}
