package com.wool.ledger.ui.settings;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.wool.ledger.R;
import com.wool.ledger.ai.LlmClient;
import com.wool.ledger.ai.LlmResult;
import com.wool.ledger.data.Bill;
import com.wool.ledger.data.BillDao;
import com.wool.ledger.data.Category;
import com.wool.ledger.data.Prefs;
import com.wool.ledger.util.DateUtil;
import com.wool.ledger.util.Money;
import com.wool.ledger.util.ThemeUtil;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 我的：这个月概览 / 账本 / 记账偏好 / 提醒 / 大模型 / 数据 / 关于。 */
public class SettingsFragment extends Fragment {

    private LinearLayout body;
    /** 当前分组卡片：group() 新建，行往这里挂 */
    private LinearLayout card;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup c, @Nullable Bundle s) {
        return inflater.inflate(R.layout.fragment_settings, c, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle s) {
        body = v.findViewById(R.id.settings_body);
        v.setBackgroundColor(ThemeUtil.paper(getContext()));
        ((TextView) v.findViewById(R.id.me_title)).setTextColor(ThemeUtil.ink(getContext()));
        ((TextView) v.findViewById(R.id.me_sub)).setTextColor(ThemeUtil.ink3(getContext()));
        build();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (body != null) build();
    }

    /* ---------------- 构建 ---------------- */

    private void build() {
        final Context c = getContext();
        if (c == null) return;
        body.removeAllViews();

        final String month = DateUtil.thisMonth();
        BillDao dao = new BillDao(c);
        double[] t = dao.totals(month + "-01", month + "-31");
        int count = dao.count(month + "-01", month + "-31");
        double budget = Prefs.budget(c);
        double left = Math.max(0, budget - t[0]);

        group(getString(R.string.settings_month));
        row(getString(R.string.settings_count), count + " 笔");
        row(getString(R.string.settings_total), "¥" + Money.fmt(t[0]));
        row(getString(R.string.settings_left), "¥" + Money.fmt(left));
        row(getString(R.string.settings_streak), streak(dao) + " 天");

        group(getString(R.string.settings_book));
        final EditText budgetInput = input("" + (long) budget, InputType.TYPE_CLASS_NUMBER);
        rowCustom(getString(R.string.settings_budget), budgetInput, view -> {
            String s = budgetInput.getText().toString().trim();
            if (!s.isEmpty()) Prefs.setBudget(c, Double.parseDouble(s));
            toast("预算已存");
        });
        rowTap(getString(R.string.settings_cycle), "每月 " + Prefs.cycleStart(c) + " 日",
                () -> showCycleStartPicker(c));

        group(getString(R.string.settings_pref));
        java.util.List<Category> defCats = Category.expense();
        String[] defCatLabels = new String[defCats.size()];
        int defCatIndex = 0;
        for (int k = 0; k < defCats.size(); k++) {
            defCatLabels[k] = defCats.get(k).name;
            if (defCats.get(k).code.equals(Prefs.defCategory(c))) defCatIndex = k;
        }
        rowPick(getString(R.string.settings_defcat), Category.nameOf(Prefs.defCategory(c)),
                defCatLabels, defCatIndex, idx -> {
                    Category next = defCats.get(idx);
                    Prefs.setDefCategory(c, next.code);
                    build();
                    toast("默认分类 · " + next.name);
                });
        String[] slotLabels = DateUtil.SLOTS;
        int slotIndex = 0;
        for (int k = 0; k < slotLabels.length; k++) if (slotLabels[k].equals(Prefs.defSlot(c))) slotIndex = k;
        rowPick(getString(R.string.settings_defslot), Prefs.defSlot(c),
                slotLabels, slotIndex, idx -> {
                    String next = slotLabels[idx];
                    Prefs.setDefSlot(c, next);
                    build();
                    toast("默认时段 · " + next);
                });
        rowPick(getString(R.string.settings_after),
                Prefs.autoHome(c) ? "回到首页" : "留在记一笔",
                new String[]{"回到首页", "留在记一笔"}, Prefs.autoHome(c) ? 0 : 1, idx -> {
                    Prefs.setAutoHome(c, idx == 0);
                    build();
                });
        toggle(getString(R.string.settings_autocat), true);
        toggle(getString(R.string.settings_shake), true);

        group(getString(R.string.settings_remind));
        String[] remindOptions = {"关闭", "20:30", "21:00", "21:30"};
        int remindIndex = 0;
        for (int k = 0; k < remindOptions.length; k++) {
            if (remindOptions[k].equals(Prefs.reminder(c))) remindIndex = k;
        }
        rowPick(getString(R.string.reminder), Prefs.reminder(c), remindOptions, remindIndex, idx -> {
            String next = remindOptions[idx];
            Prefs.setReminder(c, next);
            if ("关闭".equals(next)) {
                com.wool.ledger.notify.Reminder.cancel(c);
            } else {
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 9);
                }
                com.wool.ledger.notify.Reminder.schedule(c, next);
            }
            build();
            toast("每日提醒 · " + next);
        });
        toggle(getString(R.string.settings_overbudget), true);

        group("自动记账与 Agent");
        rowTap("支付通知自动记账", Prefs.autoCapture(c) ? "已开启 · 查看待确认" : "未开启", () -> startActivity(new Intent(c, com.wool.ledger.automation.PaymentInboxActivity.class)));
        rowTap("蓝羊羊 Agent", "查账 / 记账 / 对账", () -> startActivity(new Intent(c, com.wool.ledger.ui.chat.ChatActivity.class)));
        rowTap("联网理解请求", Prefs.agentOnline(c) ? "已开启" : "本地工具模式", () -> {
            if(Prefs.agentOnline(c)){Prefs.setAgentOnline(c,false);build();}
            else new android.app.AlertDialog.Builder(c).setTitle("开启联网 Agent？").setMessage("将向你配置的模型服务发送请求、最近对话与最多20笔近期账目摘要，以理解多轮指令。支付通知原文不会发送。服务可能收费；不开启时仍可使用本地记账、查账、对账工具。任何写账操作仍需确认。").setNegativeButton("取消",null).setPositiveButton("开启",(dialog,which)->{Prefs.setAgentOnline(c,true);build();}).show();
        });

        group(getString(R.string.settings_api_title));
        final EditText base = input(Prefs.apiBase(c), InputType.TYPE_TEXT_VARIATION_URI);
        final EditText key = input(Prefs.apiKey(c), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        final EditText model = input(Prefs.apiModel(c), InputType.TYPE_CLASS_TEXT);
        colInput(getString(R.string.settings_api_base), base);
        colInput(getString(R.string.settings_api_key), key);
        colInput(getString(R.string.settings_api_model), model);
        View test = button(getString(R.string.settings_test), R.drawable.bg_btn_ghost, R.color.ink);
        body.addView(test, lp(10));
        test.setOnClickListener(v -> {
            Prefs.setApiBase(c, base.getText().toString());
            Prefs.setApiKey(c, key.getText().toString());
            Prefs.setApiModel(c, model.getText().toString());
            toast("正在连接…");
            LlmClient.ping(c, r -> toast(r.ok ? "连上了：" + r.text : "失败：" + r.error));
        });

        group("语音");
        rowTap("本地语音模型", voiceModelStateText(c), () -> showVoiceModelDialog());
        group(getString(R.string.settings_data));
        rowTap(getString(R.string.export_csv), "写入本机文件", () -> exportCsv(c, dao));
        rowTap(getString(R.string.import_csv), "从文件读回", () -> pickCsv());
        rowTap(getString(R.string.clear_data), "不可恢复", () -> clearData(c));

        group(getString(R.string.settings_about));
        row(getString(R.string.settings_version),
                "v" + com.wool.ledger.BuildConfig.VERSION_NAME
                        + " (" + com.wool.ledger.BuildConfig.VERSION_CODE + ")");
        row(getString(R.string.settings_where), getString(R.string.settings_local_only));
        row(getString(R.string.settings_scope), getString(R.string.settings_scope_val));
    }

    /** 账期起始：弹出 1–28 号网格直接选，不再点一下循环一个数 */
    private void showCycleStartPicker(final Context c) {
        if (c == null) return;
        final int cur = Math.max(1, Math.min(28, Prefs.cycleStart(c)));
        final int ink = ThemeUtil.ink(c);
        final int ink3 = ThemeUtil.ink3(c);
        final int paper = ThemeUtil.paper(c);
        final int cardColor = ThemeUtil.card(c);
        final boolean dark = ThemeUtil.dark(c);

        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(10));

        TextView title = new TextView(c);
        title.setText("账期起始");
        title.setTextSize(17);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(ink);
        root.addView(title);

        TextView sub = new TextView(c);
        sub.setText("每月从几号开始算这个月（选 1–28 号）");
        sub.setTextSize(11);
        sub.setTextColor(ink3);
        sub.setPadding(0, dp(4), 0, 0);
        root.addView(sub);

        final androidx.appcompat.app.AlertDialog[] holder = new androidx.appcompat.app.AlertDialog[1];
        int cellH = dp(40);
        for (int r = 0; r < 4; r++) {
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowLp.topMargin = r == 0 ? dp(12) : 0;
            root.addView(row, rowLp);
            for (int col = 0; col < 7; col++) {
                final int day = r * 7 + col + 1;
                TextView cell = new TextView(c);
                cell.setText(String.valueOf(day));
                cell.setGravity(Gravity.CENTER);
                cell.setTextSize(14);
                cell.setTypeface(null, day == cur ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
                android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
                bg.setCornerRadius(dp(10));
                if (day == cur) {
                    bg.setColor(ink);
                    cell.setTextColor(paper);
                } else {
                    bg.setColor(dark ? 0x00000000 : 0x00000000);
                    cell.setTextColor(ink);
                }
                cell.setBackground(bg);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, cellH, 1f);
                lp.setMargins(dp(2), dp(2), dp(2), dp(2));
                row.addView(cell, lp);
                cell.setOnClickListener(v -> {
                    com.wool.ledger.util.Haptic.tap(v);
                    Prefs.setCycleStart(c, day);
                    if (holder[0] != null) holder[0].dismiss();
                    build();
                    toast("账期起始 · 每月 " + day + " 日");
                });
            }
        }

        TextView cancel = new TextView(c);
        cancel.setText("取消");
        cancel.setTextSize(13);
        cancel.setTextColor(ink3);
        cancel.setGravity(Gravity.END);
        cancel.setPadding(0, dp(12), dp(4), dp(4));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        root.addView(cancel, clp);

        androidx.appcompat.app.AlertDialog dlg =
                new androidx.appcompat.app.AlertDialog.Builder(c).setView(root).create();
        holder[0] = dlg;
        cancel.setOnClickListener(v -> dlg.dismiss());
        dlg.show();
        android.view.Window w = dlg.getWindow();
        if (w != null) {
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(cardColor);
            bg.setCornerRadius(dp(20));
            bg.setStroke(Math.max(1, dp(0.8f)), ThemeUtil.line(c));
            w.setBackgroundDrawable(bg);
            int width = c.getResources().getDisplayMetrics().widthPixels - dp(56);
            w.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private int streak(BillDao dao) {
        int n = 0;
        String d = DateUtil.today();
        while (n < 90) {
            if (dao.listDate(d).isEmpty()) break;
            n++;
            d = DateUtil.addDays(d, -1);
        }
        return n;
    }

    /* ---------------- 小组件 ---------------- */

    private void group(String name) {
        // 分组标题：小号、加字距，像目录里的分节名
        TextView t = new TextView(getContext());
        t.setText(name);
        t.setTextSize(10);
        t.setLetterSpacing(0.2f);
        t.setTextColor(ThemeUtil.ink3(getContext()));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(24);
        lp.bottomMargin = dp(8);
        lp.leftMargin = dp(4);
        t.setLayoutParams(lp);
        body.addView(t);

        // 分组内容装进一张纸卡，行与行之间留发丝线
        card = new LinearLayout(getContext());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(ThemeUtil.dark(getContext())
                ? R.drawable.bg_card_dark : R.drawable.bg_card);
        card.setPadding(dp(16), dp(2), dp(16), dp(2));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.bottomMargin = dp(6);
        card.setLayoutParams(clp);
        body.addView(card);
    }

    private LinearLayout rowShell() {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(52));
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        View line = new View(getContext());
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(ThemeUtil.line(getContext()));
        line.setBackground(g);
        return row;
    }

    private void row(String label, String value) {
        LinearLayout row = rowShell();
        row.addView(labelView(label), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView v = valueView(value);
        row.addView(v);
        card.addView(row, hairline());
    }

    private void rowTap(String label, String value, Runnable onClick) {
        LinearLayout row = rowShell();
        row.addView(labelView(label), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView v = valueView(value);
        row.addView(v);
        TextView arrow = new TextView(getContext());
        arrow.setText("›");
        arrow.setTextSize(15);
        arrow.setTextColor(ThemeUtil.ink3(getContext()));
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.leftMargin = dp(6);
        row.addView(arrow, alp);
        row.setOnClickListener(x -> onClick.run());
        card.addView(row, hairline());
    }

    /** 和账期起始一样：点开下拉直接选，不再点一下循环一个值 */
    private void rowPick(String label, String value, String[] options, int selected,
                         com.wool.ledger.ui.widget.ThemePopup.OnPick pick) {
        LinearLayout row = rowShell();
        row.addView(labelView(label), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(valueView(value));
        TextView arrow = new TextView(getContext());
        arrow.setText("›");
        arrow.setTextSize(15);
        arrow.setTextColor(ThemeUtil.ink3(getContext()));
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.leftMargin = dp(6);
        row.addView(arrow, alp);
        row.setOnClickListener(x -> com.wool.ledger.ui.widget.ThemePopup.show(
                getContext(), row, options, selected, pick));
        card.addView(row, hairline());
    }

    private void rowCustom(String label, View input, OnCommit commit) {
        LinearLayout row = rowShell();
        row.addView(labelView(label), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(input);
        input.setOnFocusChangeListener((v, has) -> {
            if (!has) commit.onCommit(v);
        });
        card.addView(row, hairline());
    }

    private void toggle(String label, boolean on) {
        LinearLayout row = rowShell();
        row.addView(labelView(label), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView sw = new TextView(getContext());
        sw.setText(on ? "开" : "关");
        sw.setTextSize(10);
        sw.setLetterSpacing(0.1f);
        sw.setTextColor(on ? 0xFFFFFFFF : ThemeUtil.ink3(getContext()));
        sw.setBackgroundResource(on ? R.drawable.bg_chip_on : R.drawable.bg_chip);
        sw.setPadding(dp(10), dp(3), dp(10), dp(3));
        final boolean[] state = {on};
        row.addView(sw);
        row.setOnClickListener(v -> {
            state[0] = !state[0];
            sw.setText(state[0] ? "开" : "关");
            sw.setBackgroundResource(state[0] ? R.drawable.bg_chip_on : R.drawable.bg_chip);
            sw.setTextColor(state[0] ? 0xFFFFFFFF : ThemeUtil.ink3(getContext()));
        });
        card.addView(row, hairline());
    }

    private void colInput(String label, EditText input) {
        LinearLayout col = new LinearLayout(getContext());
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(2);
        clp.bottomMargin = dp(2);
        col.setLayoutParams(clp);
        TextView l = labelView(label);
        l.setTextSize(11);
        l.setLetterSpacing(0.08f);
        l.setTextColor(ThemeUtil.ink3(getContext()));
        col.addView(l);
        // 标签与输入框之间留 8dp，输入框自身 12dp 内边距
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ilp.topMargin = dp(8);
        input.setLayoutParams(ilp);
        input.setPadding(dp(12), dp(10), dp(12), dp(10));
        input.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
        input.setTextSize(13);
        input.setBackgroundResource(ThemeUtil.dark(getContext())
                ? R.drawable.bg_input_dark : R.drawable.bg_input);
        col.addView(input);
        col.setPadding(0, dp(10), 0, dp(10));
        card.addView(col);
        divider(card);
    }

    private TextView labelView(String s) {
        TextView t = new TextView(getContext());
        t.setText(s);
        t.setTextSize(13);
        t.setTextColor(ThemeUtil.ink(getContext()));
        return t;
    }

    private TextView valueView(String s) {
        TextView t = new TextView(getContext());
        t.setText(s);
        t.setTextSize(13);
        t.setTextColor(ThemeUtil.ink2(getContext()));
        return t;
    }

    private EditText input(String value, int type) {
        EditText e = new EditText(getContext());
        e.setText(value);
        e.setTextSize(13);
        e.setInputType(type);
        e.setGravity(Gravity.END);
        e.setBackgroundResource(R.drawable.bg_input);
        e.setPadding(dp(8), dp(6), dp(8), dp(6));
        e.setTextColor(ThemeUtil.ink(getContext()));
        return e;
    }

    private View button(String text, int bg, int colorRes) {
        TextView b = new TextView(getContext());
        b.setText(text);
        b.setGravity(Gravity.CENTER);
        b.setTextSize(12.5f);
        b.setTextColor(getContext().getColor(colorRes));
        b.setBackgroundResource(bg);
        b.setMinHeight(dp(42));
        b.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        b.setClickable(true);
        return b;
    }

    private LinearLayout.LayoutParams hairline() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        return lp;
    }

    /** 行与行之间的发丝线（卡片内使用） */
    private void divider(LinearLayout parent) {
        View line = new View(getContext());
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(ThemeUtil.line(getContext()));
        line.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.8f)));
        parent.addView(line, lp);
    }

    private LinearLayout.LayoutParams lp(int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(top);
        return p;
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void toast(String s) {
        Toast.makeText(getContext(), s, Toast.LENGTH_SHORT).show();
    }

    private interface OnCommit {
        void onCommit(View v);
    }

    /* ---------------- 数据操作 ---------------- */

    private void exportCsv(android.content.Context c, BillDao dao) {
        try {
            java.util.List<Bill> all = dao.listAll();
            StringBuilder sb = new StringBuilder("日期,时间,类型,分类,金额,备注,来源\n");
            for (Bill b : all) {
                sb.append(b.billDate).append(',')
                        .append(b.billTime).append(',')
                        .append(b.isIncome() ? "收入" : "支出").append(',')
                        .append(b.categoryName()).append(',')
                        .append(Money.plain(b.amount)).append(',')
                        .append('"').append(b.note == null ? "" : b.note.replace("\"", "\"\"")).append('"').append(',')
                        .append(b.source == 3 ? "支付通知" : (b.source == 2 ? "语音" : (b.source == 1 ? "Agent/AI" : "手动"))).append('\n');
            }
            String name = "wool-" + new SimpleDateFormat("yyyyMMdd-HHmm", Locale.CHINA).format(new Date()) + ".csv";
            byte[] data = ("\ufeff" + sb).getBytes("UTF-8");
            String where;
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                android.content.ContentValues v = new android.content.ContentValues();
                v.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name);
                v.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/csv");
                v.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                        android.os.Environment.DIRECTORY_DOWNLOADS + "/羊毛账本");
                android.net.Uri uri = c.getContentResolver()
                        .insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri == null) throw new IllegalStateException("无法创建文件");
                try (java.io.OutputStream os = c.getContentResolver().openOutputStream(uri)) {
                    if (os == null) throw new IllegalStateException("无法写入文件");
                    os.write(data);
                }
                where = "下载/羊毛账本/" + name;
            } else {
                java.io.File dir = c.getExternalFilesDir("export");
                if (dir == null) dir = c.getFilesDir();
                if (!dir.exists()) dir.mkdirs();
                java.io.File f = new java.io.File(dir, name);
                try (java.io.FileOutputStream os = new java.io.FileOutputStream(f)) {
                    os.write(data);
                }
                where = f.getAbsolutePath();
            }
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) c.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("csv path", where));
            toast("已导出 " + all.size() + " 条 → " + where);
        } catch (Exception e) {
            toast("导出失败：" + e.getMessage());
        }
    }

    private void clearData(Context c) {
        new android.app.AlertDialog.Builder(c)
                .setTitle("清空所有账目？")
                .setMessage("删除后无法恢复，预算和接口配置会保留。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清空", (d, w) -> {
                    BillDao dao = new BillDao(c);
                    dao.clearAll();
                    build();
                    toast("已清空");
                })
                .show();
    }

    /* ---------- 导入 CSV ---------- */
    private void pickCsv() {
        try {
            android.content.Intent it = new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT);
            it.addCategory(android.content.Intent.CATEGORY_OPENABLE);
            it.setType("*/*");
            startActivityForResult(it, 8001);
        } catch (Exception e) {
            toast("这台设备没有文件选择器");
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 8002) {
            if (data == null || data.getData() == null) return;
            final android.net.Uri uri = data.getData();
            final android.content.Context ctx = getContext();
            toast("正在导入模型…");
            new Thread(() -> {
                try {
                    java.io.File target = com.wool.ledger.util.VoiceModel.file(ctx);
                    try (java.io.InputStream in = ctx.getContentResolver().openInputStream(uri);
                         java.io.OutputStream out = new java.io.FileOutputStream(target)) {
                        byte[] buf = new byte[128 * 1024];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    }
                    java.io.File dir = com.wool.ledger.util.ModelInstaller.install(ctx, target, null);
                    final boolean ok = dir != null;
                    main.post(() -> {
                        if (ok) {
                            com.wool.ledger.data.Prefs.setVoiceModelState(ctx,
                                    com.wool.ledger.data.Prefs.VOICE_READY);
                            build();
                            toast("模型导入成功");
                        } else {
                            toast("导入失败：不是有效的 .tar.bz2 模型包");
                        }
                    });
                } catch (Exception e) {
                    main.post(() -> toast("导入失败：" + e.getMessage()));
                }
            }).start();
            return;
        }
        if (requestCode != 8001 || data == null || data.getData() == null) return;
        final android.net.Uri uri = data.getData();
        final android.content.Context c = getContext();
        new Thread(() -> {
            int ok = 0, fail = 0;
            BillDao dao = new BillDao(c);
            try (java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(c.getContentResolver().openInputStream(uri), "UTF-8"))) {
                String line;
                boolean first = true;
                while ((line = br.readLine()) != null) {
                    if (first) { first = false; continue; }   // 表头
                    String[] f = splitCsv(line);
                    if (f.length < 5) { fail++; continue; }
                    try {
                        Bill b = new Bill();
                        b.billDate = f[0].trim();
                        b.billTime = f[1].trim();
                        if (b.billTime.isEmpty()) b.billTime = "12:00";
                        b.slot = DateUtil.slotOf(b.billTime);
                        String type = f[2].trim();
                        String catName = f[3].trim();
                        String code = "other";
                        for (Category cat : Category.ALL) {
                            if (cat.name.equals(catName)) { code = cat.code; break; }
                        }
                        b.category = code;
                        b.amount = Double.parseDouble(f[4].trim());
                        b.note = f.length > 5 ? f[5].trim() : "";
                        b.source = 0;
                        b.createTime = b.billDate + " " + b.billTime;
                        if ("收入".equals(type)) b.category = Category.byCode(code).type == 1 ? code : "parttime";
                        dao.insert(b);
                        ok++;
                    } catch (Exception e) {
                        fail++;
                    }
                }
            } catch (Exception e) {
                main.post(() -> toast("导入失败：" + e.getMessage()));
                return;
            }
            final int okc = ok, failc = fail;
            main.post(() -> {
                build();
                toast("导入完成：成功 " + okc + " 条" + (failc > 0 ? "，跳过 " + failc + " 条" : ""));
            });
        }).start();
    }

    /** 简易 CSV 拆分：支持双引号包裹的逗号 */
    private static String[] splitCsv(String line) {
        java.util.List<String> out = new java.util.ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean q = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') { q = !q; continue; }
            if (ch == ',' && !q) { out.add(sb.toString()); sb.setLength(0); }
            else sb.append(ch);
        }
        out.add(sb.toString());
        return out.toArray(new String[0]);
    }

    /* ---------- 本地语音模型 ---------- */
    private String voiceModelStateText(android.content.Context c) {
        if (com.wool.ledger.util.VoiceModel.ready(c)) {
            return com.wool.ledger.data.Prefs.voiceVerified(c)
                    ? "已就绪 · 离线可用" : "已就绪 · 待首次使用验证";
        }
        if (com.wool.ledger.util.VoiceModel.bundled(c)) return "随 App 内置 · 待解压（80 MB）";
        if (com.wool.ledger.util.VoiceModel.downloaded(c)) return "已下载 · 待解压";
        return "未下载";
    }

    private void showVoiceModelDialog() {
        final android.content.Context c = getContext();
        if (c == null) return;
        boolean ready = com.wool.ledger.util.VoiceModel.ready(c);
        boolean bundled = com.wool.ledger.util.VoiceModel.bundled(c);
        String installLabel = bundled ? "解压内置模型" : "下载模型";
        String[] items = ready
                ? new String[]{bundled ? "重新安装内置模型" : "重新下载模型", "删除本地模型"}
                : new String[]{installLabel, "从本地导入（.tar.bz2）"};
        new androidx.appcompat.app.AlertDialog.Builder(c)
                .setTitle("本地语音模型 · " + voiceModelStateText(c))
                .setItems(items, (d, which) -> {
                    if (ready) {
                        if (which == 0) {
                            com.wool.ledger.util.VoiceModel.file(c).delete();
                            com.wool.ledger.data.Prefs.setVoiceModelState(c,
                                    com.wool.ledger.data.Prefs.VOICE_NONE);
                            com.wool.ledger.ui.voice.VoiceModelDialog.show(c,
                                    new com.wool.ledger.ui.voice.VoiceModelDialog.OnDone() {
                                        public void onReady() { build(); }
                                        public void onSkip() { build(); }
                                    });
                        } else {
                            deleteVoiceModel();
                        }
                    } else {
                        if (which == 0) {
                            com.wool.ledger.ui.voice.VoiceModelDialog.show(c,
                                    new com.wool.ledger.ui.voice.VoiceModelDialog.OnDone() {
                                        public void onReady() { build(); }
                                        public void onSkip() { build(); }
                                    });
                        } else {
                            pickVoiceModelFile();
                        }
                    }
                }).show();
    }

    private void deleteVoiceModel() {
        android.content.Context c = getContext();
        java.io.File dir = com.wool.ledger.ai.SherpaAsr.modelDir(c);
        java.io.File[] fs = dir.listFiles();
        if (fs != null) for (java.io.File f : fs) f.delete();
        com.wool.ledger.util.VoiceModel.file(c).delete();
        com.wool.ledger.data.Prefs.setVoiceModelState(c, com.wool.ledger.data.Prefs.VOICE_NONE);
        build();
        toast("已删除本地模型");
    }

    /** 从手机里挑一个 .tar.bz2 模型包导入 */
    private void pickVoiceModelFile() {
        try {
            android.content.Intent it = new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT);
            it.addCategory(android.content.Intent.CATEGORY_OPENABLE);
            it.setType("*/*");
            startActivityForResult(it, 8002);
        } catch (Exception e) {
            toast("这台设备没有文件选择器");
        }
    }
}
