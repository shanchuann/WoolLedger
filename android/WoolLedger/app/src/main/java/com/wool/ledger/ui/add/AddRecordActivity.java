package com.wool.ledger.ui.add;

import android.app.DatePickerDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.wool.ledger.R;
import com.wool.ledger.ai.LlmClient;
import com.wool.ledger.ai.LlmResult;
import com.wool.ledger.ai.OfflineParser;
import com.wool.ledger.data.Bill;
import com.wool.ledger.data.BillDao;
import com.wool.ledger.data.Category;
import com.wool.ledger.data.Prefs;
import com.wool.ledger.util.DateUtil;
import com.wool.ledger.util.Money;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 记一笔：手动填全金额 / 分类 / 日期 / 时段 / 备注，也可以一句话交给 AI 解析。
 * 输入 bill_id（long，0=新建）、date、slot；从蓝羊羊「改一下」过来时还会带
 * amount / type / category / note / source 作为预填。
 * 保存成功 setResult(RESULT_OK, "changed"=true) 后 finish。
 */
public class AddRecordActivity extends AppCompatActivity {

    public static final String EXTRA_BILL_ID = "bill_id";

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private BillDao dao;
    private Bill editing;          // 非空 = 编辑已有账目

    private int type = Bill.EXPENSE;
    private double amount;
    private String category = "";
    private String date;
    private String slot = "午";
    private String note = "";
    private String createTime;
    private boolean aiUsed;
    /** 这一笔是不是语音录进来的（source=2） */
    private boolean voiceUsed;
    /** 语音识别出的原话：备注没填时存进备注，列表/详情就不至于一条空白 */
    private String voiceText;

    private boolean bindingAmount;

    private FrameLayout segWrap;   // activity_add.xml 里 add_seg_wrap 是 FrameLayout（描边容器 + clipToOutline）
    private TextView segExpense;
    private TextView segIncome;
    private EditText etAmount;
    private TextView etPrompt;
    private TextView btnAi;
    private TextView tvAiHint;
    private TextView tvCatState;
    private RecyclerView rvCats;
    private TextView tvDate;
    private TextView tvSlot;
    private EditText etNote;
    private TextView tvSource;
    private TextView tvTime;
    private TextView btnSave;
    private ImageView addMic;

    /** 语音流程是否处理中（模型弹窗 / 模型加载 / 录音后的识别），防止重复触发 */
    private volatile boolean voiceBusy;

    private final List<Category> catList = new ArrayList<>();
    private CatAdapter catAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Prefs.applyTheme(this);
        setContentView(R.layout.activity_add);
        applyDarkIfNeeded();
        dao = new BillDao(this);

        date = DateUtil.today();
        slot = Prefs.defSlot(this);
        if (slot == null || slot.isEmpty()) slot = "午";
        createTime = DateUtil.nowFull();

        bindViews();
        setupSeg();
        setupAmount();
        setupCats();
        setupRows();
        setupAi();
        setupSave();

        long billId = getIntent().getLongExtra(EXTRA_BILL_ID, 0L);
        if (billId <= 0) {
            // 兼容调用方用 int 塞 bill_id 的情况（契约是 long，这里兜一层）
            Bundle extras = getIntent().getExtras();
            if (extras != null) {
                Object raw = extras.get(EXTRA_BILL_ID);
                if (raw instanceof Number) billId = ((Number) raw).longValue();
            }
        }
        if (billId > 0) {
            loadBill(billId);
            repaintTheme();
        } else {
            applyPrefill(getIntent());
            renderAll();
            repaintTheme();
        }
    }

    /* ---------------- 视图 ---------------- */

    private void bindViews() {
        segWrap = findViewById(R.id.add_seg_wrap);
        segExpense = findViewById(R.id.add_seg_expense);
        segIncome = findViewById(R.id.add_seg_income);
        etAmount = findViewById(R.id.add_amount);
        etPrompt = findViewById(R.id.add_prompt);
        // 一句话里有多笔时，保存按钮要能点亮（不用先填金额）
        etPrompt.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                updateSave();
            }
        });
        btnAi = findViewById(R.id.add_ai_btn);
        tvAiHint = findViewById(R.id.add_ai_hint);
        tvCatState = findViewById(R.id.add_cat_state);
        rvCats = findViewById(R.id.add_cats);
        tvDate = findViewById(R.id.add_date);
        tvSlot = findViewById(R.id.add_slot);
        etNote = findViewById(R.id.add_note);
        tvSource = findViewById(R.id.add_source);
        tvTime = findViewById(R.id.add_time);
        btnSave = findViewById(R.id.add_save);
        addMic = findViewById(R.id.add_mic);
        if (addMic != null) {
            addMic.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onMicClick();
                }
            });
        }

        findViewById(R.id.add_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
    }

    private void setupSeg() {
        GradientDrawable border = new GradientDrawable();
        border.setCornerRadius(dp(4));
        border.setColor(Color.TRANSPARENT);
        border.setStroke(dp(1), color(R.color.line));
        segWrap.setBackgroundResource(R.drawable.add_seg_track);
        segWrap.setClipToOutline(true);

        segExpense.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setType(Bill.EXPENSE);
            }
        });
        segIncome.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setType(Bill.INCOME);
            }
        });
    }

    private void setType(int t) {
        if (type == t) return;
        type = t;
        category = "";               // 换类型时分类作废，和预览一致
        renderType();
        renderCats();
        updateSave();
    }

    private void renderType() {
        int ink = color(R.color.ink);
        int paper = color(R.color.paper);
        int ink3 = color(R.color.ink3);

        GradientDrawable on = new GradientDrawable();
        on.setColor(ink);

        segExpense.setBackgroundResource(type == Bill.EXPENSE ? R.drawable.add_seg_on : 0);
        segIncome.setBackgroundResource(type == Bill.INCOME ? R.drawable.add_seg_on : 0);
        segExpense.setTextColor(type == Bill.EXPENSE ? paper : ink3);
        segIncome.setTextColor(type == Bill.INCOME ? paper : ink3);
    }

    private void setupAmount() {
        etAmount.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (bindingAmount) return;
                String raw = s.toString();
                String clean = cleanAmount(raw);
                if (!clean.equals(raw)) {
                    bindingAmount = true;
                    etAmount.setText(clean);
                    etAmount.setSelection(clean.length());
                    bindingAmount = false;
                }
                amount = parseAmount(clean);
                updateSave();
            }
        });
    }

    private void setupCats() {
        catAdapter = new CatAdapter();
        rvCats.setLayoutManager(new GridLayoutManager(this, 3));
        rvCats.addItemDecoration(new RecyclerView.ItemDecoration() {
            @Override
            public void getItemOffsets(@NonNull Rect outRect, @NonNull View view,
                                       @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
                int pos = parent.getChildAdapterPosition(view);
                if (pos < 0) return;
                int col = pos % 3;
                outRect.left = col == 0 ? 0 : dp(4);
                outRect.right = col == 2 ? 0 : dp(4);
                outRect.bottom = dp(8);
            }
        });
        rvCats.setAdapter(catAdapter);
    }

    private void setupRows() {
        findViewById(R.id.add_row_date).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickDate();
            }
        });
        findViewById(R.id.add_row_slot).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickSlot();
            }
        });
        etNote.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                note = s.toString().trim();
            }
        });
    }

    private void setupAi() {
        GradientDrawable box = new GradientDrawable();
        box.setCornerRadius(dp(4));
        box.setColor(Color.WHITE);
        box.setStroke(dp(1), color(R.color.ink));
        findViewById(R.id.add_prompt_box).setBackgroundResource(R.drawable.add_prompt_card);

        btnAi.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                parseByAi();
            }
        });
        etPrompt.setSingleLine(true);
        etPrompt.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent event) {
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                    parseByAi();
                    return true;
                }
                return false;
            }
        });
    }

    private void setupSave() {
        btnSave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save();
            }
        });
    }

    /* ---------------- 初始化 / 预填 ---------------- */

    private void applyPrefill(Intent it) {
        String d = it.getStringExtra("date");
        if (d != null && d.length() == 10) date = d;
        String s = it.getStringExtra("slot");
        if (s != null && !s.isEmpty()) slot = s;

        int t = it.getIntExtra("type", -1);
        if (t == Bill.EXPENSE || t == Bill.INCOME) type = t;

        String c = it.getStringExtra("category");
        if (c != null && !c.isEmpty()) {
            category = Category.byCode(c).code;
            type = Category.byCode(c).type;
        }
        double a = it.getDoubleExtra("amount", 0);
        if (a > 0) amount = a;
        String n = it.getStringExtra("note");
        if (n != null) note = n;
        aiUsed = it.getIntExtra("source", 0) == 1;
        voiceUsed = it.getIntExtra("source", 0) == 2;
    }

    private void loadBill(final long id) {
        io.execute(new Runnable() {
            @Override
            public void run() {
                final Bill b = dao.get(id);
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || isDestroyed()) return;
                        if (b == null) {
                            applyPrefill(getIntent());
                            renderAll();
                            return;
                        }
                        editing = b;
                        type = b.type;
                        amount = b.amount;
                        category = b.category;
                        date = b.billDate;
                        slot = b.slot;
                        note = b.note == null || "保密".equals(b.note) ? "" : b.note;
                        createTime = b.createTime;
                        aiUsed = b.source == 1;
                        voiceUsed = b.source == 2;
                        TextView title = findViewById(R.id.add_title);
                        TextView sub = findViewById(R.id.add_subtitle);
                        title.setText("编辑这一笔");
                        sub.setText("Edit Entry");
                        renderAll();
                    }
                });
            }
        });
    }

    private void renderAll() {
        renderType();
        bindingAmount = true;
        etAmount.setText(amount > 0 ? Money.plain(amount) : "");
        etAmount.setSelection(etAmount.getText().length());
        bindingAmount = false;
        etNote.setText(note);
        renderCats();
        renderDate();
        renderSlot();
        renderSource();
        renderTime();
        updateSave();
    }

    private void renderCats() {
        catList.clear();
        catList.addAll(type == Bill.INCOME ? Category.income() : Category.expense());
        if (!category.isEmpty()) {
            boolean match = false;
            for (Category c : catList) {
                if (c.code.equals(category)) match = true;
            }
            if (!match) category = "";
        }
        catAdapter.notifyDataSetChanged();
        updateCatState();
    }

    private void renderDate() {
        tvDate.setText(DateUtil.monthDay(date) + " · 周" + DateUtil.weekdayCn(date));
    }

    private void renderSlot() {
        tvSlot.setText(slot + " " + slotHour(slot));
    }

    private void renderSource() {
        tvSource.setText(voiceUsed ? "语音录入" : (aiUsed ? "AI 识别" : "手动录入"));
    }

    private void renderTime() {
        String c = createTime;
        if (c == null || c.length() < 16) {
            tvTime.setText(DateUtil.monthDay(DateUtil.today()) + " " + DateUtil.nowTime());
            return;
        }
        tvTime.setText(DateUtil.shortDate(c.substring(0, 10)) + " " + c.substring(11, 16));
    }

    private void updateCatState() {
        tvCatState.setText(category.isEmpty() ? "未选择" : Category.nameOf(category));
    }

    /** 输入框里是不是"一句话多笔"（≥2 笔）。是的话保存按钮不用等金额 */
    private boolean multiPending() {
        if (editing != null || etPrompt == null) return false;
        String t = etPrompt.getText().toString().trim();
        if (t.isEmpty()) return false;
        try {
            return com.wool.ledger.ai.OfflineParser.parseMulti(t).size() >= 2;
        } catch (Throwable e) {
            return false;
        }
    }

    private void updateSave() {
        if (btnSave == null) return;
        // 金额必须 > 0；上限 999999，超过时按钮直接不可点并说明原因。
        // 例外：一句话多笔时金额由各自解析，按钮要先能点。
        boolean over = amount > 999999;
        boolean multi = multiPending();
        boolean ok = (amount > 0 || multi) && !over;
        btnSave.setEnabled(ok);
        String label;
        if (over) label = "金额超过上限（≤ 999999）";
        else if (multi) label = "保存这几笔";
        else label = "保存这一笔 ¥" + Money.plain(amount > 0 ? amount : 0);
        btnSave.setText(label);

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        if (ok) {
            bg.setColor(color(R.color.blue2));
            btnSave.setTextColor(Color.WHITE);
        } else {
            bg.setColor(0xFFDEE9F5);
            bg.setStroke(dp(1), 0xFFCFDEEC);
            btnSave.setTextColor(0xFF7A90A6);
        }
        btnSave.setBackground(bg);
    }

    private void setAiHint(String text) {
        if (text == null || text.isEmpty()) {
            tvAiHint.setVisibility(View.GONE);
            return;
        }
        tvAiHint.setText(text);
        tvAiHint.setVisibility(View.VISIBLE);
    }

    /* ---------------- 选择日期 / 时段 ---------------- */

    private void pickDate() {
        Calendar c = Calendar.getInstance();
        try {
            String[] p = date.split("-");
            c.set(Integer.parseInt(p[0]), Integer.parseInt(p[1]) - 1, Integer.parseInt(p[2]));
        } catch (Exception ignored) {
        }
        new DatePickerDialog(this, new DatePickerDialog.OnDateSetListener() {
            @Override
            public void onDateSet(DatePicker view, int year, int month, int dayOfMonth) {
                date = String.format(java.util.Locale.CHINA, "%04d-%02d-%02d", year, month + 1, dayOfMonth);
                renderDate();
            }
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void pickSlot() {
        int checked = 0;
        for (int i = 0; i < DateUtil.SLOTS.length; i++) {
            if (DateUtil.SLOTS[i].equals(slot)) checked = i;
        }
        new AlertDialog.Builder(this)
                .setTitle("时段")
                .setSingleChoiceItems(DateUtil.SLOTS, checked, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        slot = DateUtil.SLOTS[which];
                        renderSlot();
                        dialog.dismiss();
                    }
                })
                .show();
    }

    /* ---------------- AI 一句话识别 ---------------- */

    /** 一句话里有多笔就一次性记完（离线规则能稳定做这件事） */
    private boolean tryMultiSave(String text) {
        java.util.List<LlmResult> list =
                com.wool.ledger.ai.OfflineParser.parseMulti(text);
        if (list.size() < 2) return false;
        int n = 0;
        for (LlmResult r : list) {
            try {
                Bill b = new Bill();
                b.type = r.type;
                b.amount = r.amount;
                b.category = r.category;
                b.note = r.note;
                b.billDate = r.date;
                b.billTime = r.time;
                b.slot = r.slot;
                b.createTime = com.wool.ledger.util.DateUtil.nowFull();
                b.source = voiceUsed ? 2 : 1;
                dao.insert(b);
                n++;
            } catch (Exception ignored) {
            }
        }
        if (n > 0) {
            getWindow().getDecorView().performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            setResult(RESULT_OK, new android.content.Intent().putExtra("changed", true));
            android.widget.Toast.makeText(this, "已识别 " + n + " 笔账目，已全部入账", android.widget.Toast.LENGTH_SHORT).show();
            finish();
        }
        return n > 0;
    }

    private void parseByAi() {
        final String text = etPrompt.getText().toString().trim();
        if (text.isEmpty()) {
            toast("先写一句话，比如「午饭食堂 15」");
            return;
        }
        btnAi.setEnabled(false);
        btnAi.setText("识别中…");
        LlmClient.parseRecord(this, text, new LlmClient.Callback() {
            @Override
            public void onResult(LlmResult r) {
                if (isFinishing() || isDestroyed()) return;
                btnAi.setEnabled(true);
                btnAi.setText("AI 识别");
                if (r == null || !r.ok) {
                    toast(r != null && r.error != null && !r.error.isEmpty()
                            ? r.error : "没识别出来，手动填也一样");
                    setAiHint("没能识别，改成手动填写也可以");
                    return;
                }
                applyAi(r);
            }
        });
    }

    private void applyAi(LlmResult r) {
        aiUsed = true;
        type = r.type == Bill.INCOME ? Bill.INCOME : Bill.EXPENSE;
        String cat = r.category == null || r.category.isEmpty() ? "other" : Category.byCode(r.category).code;
        if (Category.byCode(cat).type != type) {
            cat = type == Bill.INCOME ? "parttime" : "other";
        }
        category = cat;
        if (r.amount > 0) amount = r.amount;
        if (r.date != null && r.date.length() == 10) date = r.date;
        if (r.slot != null && !r.slot.isEmpty()) slot = r.slot;
        if (r.note != null && !r.note.trim().isEmpty()) note = r.note.trim();

        renderAll();
        setAiHint("已填好：" + "¥" + Money.plain(amount) + " · " + Category.nameOf(category)
                + " · " + DateUtil.shortDate(date) + " · " + slot
                + (r.offline ? "（本地识别）" : "（AI 识别）"));
        toast("已识别，核对一下再保存");
    }

    /* ---------------- 保存 ---------------- */

    private void save() {
        // 新建账单时先看「一句话」里是不是多笔：>=2 笔由 tryMultiSave 直接入账并 finish；
        // 单笔时 tryMultiSave 返回 false，继续走下面的普通保存；编辑已有账单不参与拆分。
        String promptText = etPrompt.getText().toString().trim();
        if (editing == null && !promptText.isEmpty() && tryMultiSave(promptText)) return;

        if (amount <= 0) {
            toast("金额还没填");
            return;
        }
        if (amount > 999999) {
            toast("金额太大了，最多 999999");
            return;
        }
        if (category.isEmpty()) {
            // 没点分类时用设置里的默认分类兜底；类型不符就退到"其他 / 兼职"
            Category def = Category.byCode(Prefs.defCategory(this));
            category = def.type == type ? def.code : (type == Bill.INCOME ? "parttime" : "other");
            renderCats();
            updateCatState();
        }
        if (voiceUsed && (note == null || note.trim().isEmpty())
                && voiceText != null && !voiceText.trim().isEmpty()) {
            note = voiceText.trim();
        }
        final Bill b = editing != null ? editing : new Bill();
        b.type = type;
        b.amount = amount;
        b.category = category;
        b.note = note;
        b.billDate = date;
        b.slot = slot;
        b.billTime = editing != null ? editing.billTime : DateUtil.nowTime();
        // 录入时间以真正点保存的时刻为准（页面可能开着好几分钟）
        b.createTime = editing != null ? editing.createTime : DateUtil.nowFull();
        b.source = editing != null ? editing.source : (voiceUsed ? 2 : (aiUsed ? 1 : 0));

        btnSave.setEnabled(false);
        io.execute(new Runnable() {
            @Override
            public void run() {
                if (b.id > 0) {
                    dao.update(b);
                } else {
                    dao.insert(b);
                }
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || isDestroyed()) return;
                        setResult(RESULT_OK, new Intent().putExtra("changed", true));
                        finish();
                    }
                });
            }
        });
    }

    /* ---------------- 分类宫格 ---------------- */

    private class CatAdapter extends RecyclerView.Adapter<CatAdapter.VH> {

        class VH extends RecyclerView.ViewHolder {
            final View dot;
            final TextView name;

            VH(View v) {
                super(v);
                dot = v.findViewById(R.id.cat_dot);
                name = v.findViewById(R.id.cat_name);
            }
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_cat_chip, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            final Category c = catList.get(position);
            h.name.setText(c.name);

            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(c.color);
            h.dot.setBackground(dot);

            boolean on = category != null && category.equals(c.code);
            boolean isDark = com.wool.ledger.util.ThemeUtil.dark(AddRecordActivity.this);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(10));
            // 选中：分类色 12% 填充 + 同色 1.5dp 描边
            int tint = (c.color & 0x00FFFFFF) | 0x1F000000;
            bg.setColor(on ? tint : Color.TRANSPARENT);
            bg.setStroke(on ? dp(1.5f) : dp(1),
                    on ? c.color : (isDark ? color(R.color.line_dark) : color(R.color.line)));
            h.itemView.setBackground(bg);
            h.name.setTextColor(on ? color(R.color.ink) : color(R.color.ink2));
            h.name.getPaint().setFakeBoldText(on);

            h.itemView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    category = c.code;
                    renderCats();
                    updateSave();
                }
            });
        }

        @Override
        public int getItemCount() {
            return catList.size();
        }
    }

    /* ---------------- 小工具 ---------------- */

    private int color(int res) {
        return ContextCompat.getColor(this, res);
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable newSolid(int c) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(c);
        return g;
    }

    private static String slotHour(String s) {
        if ("早".equals(s)) return "07";
        if ("晚".equals(s)) return "18";
        if ("夜".equals(s)) return "21";
        return "12";
    }

    private static String cleanAmount(String raw) {
        StringBuilder b = new StringBuilder();
        boolean dot = false;
        int dec = 0;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c >= '0' && c <= '9') {
                if (dot) {
                    if (dec >= 2) continue;
                    dec++;
                }
                b.append(c);
            } else if (c == '.' && !dot) {
                dot = true;
                if (b.length() == 0) b.append('0');
                b.append('.');
            }
        }
        return b.toString();
    }

    private static double parseAmount(String s) {
        try {
            return Double.parseDouble(s);
        } catch (Exception e) {
            return 0;
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        // 录音中途退出：只翻标志，让读线程自己退出并释放 AudioRecord。
        // 不在这里 release，并发 release 会让子线程里的 read() 抛异常把进程带走。
        recording = false;
        voiceBusy = false;
        restoreMicButton();
        super.onDestroy();
        io.shutdownNow();
    }


    /** 深色模式：把这一页的纸色/墨色/发丝线统一重涂。 */
    private void applyDarkIfNeeded() {
        if (!com.wool.ledger.util.ThemeUtil.dark(this)) return;
        android.view.View root = getWindow().getDecorView().findViewById(android.R.id.content);
        root.setBackgroundColor(com.wool.ledger.util.ThemeUtil.paper(this));
        tintTree(root);
    }

    private void tintTree(android.view.View v) {
        if (v instanceof android.widget.TextView) {
            android.widget.TextView t = (android.widget.TextView) v;
            int color = t.getCurrentTextColor();
            if (color == 0xFF1A1F24) t.setTextColor(0xFFF3EFE4);
            else if (color == 0xFF5C6166) t.setTextColor(0xFFB7B2A5);
            else if (color == 0xFF6B7076) t.setTextColor(0xFF8F9AA6);
        } else if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) tintTree(g.getChildAt(i));
        }
    }

    private com.wool.ledger.ai.SherpaAsr asr;
    private android.media.AudioRecord recorder;
    private volatile boolean recording = false;

    /** 本地识别：录 10 秒（或静音 1.2 秒 / 手动点按钮后提前结束），交给 Paraformer */
    private void startLocalRecognition() {
        voiceBusy = true;
        if (androidx.core.content.ContextCompat.checkSelfPermission(this,
                android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 9101);
            return;
        }
        final android.widget.TextView hint = findViewById(R.id.add_ai_hint);
        if (hint != null) {
            hint.setVisibility(android.view.View.VISIBLE);
            hint.setText("正在准备本地模型…");
        }
        asr = new com.wool.ledger.ai.SherpaAsr();
        // 模型 82MB，加载要一两秒，放子线程，别卡主线程
        new Thread(() -> {
            final boolean ok = asr.init(AddRecordActivity.this);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    voiceBusy = false;
                    return;
                }
                if (!ok) {
                    if (hint != null) hint.setVisibility(android.view.View.GONE);
                    android.widget.Toast.makeText(AddRecordActivity.this,
                            "本地模型没加载起来，这次先用系统识别",
                            android.widget.Toast.LENGTH_SHORT).show();
                    launchRecognizer();
                    return;
                }
                startRecording(hint);
            });
        }, "asr-init").start();
    }

    /* ---------------- 语音按钮：正常态 / 录音态 ---------------- */

    /** 录音态兜底：万一读线程异常没能收尾，12 秒后按钮也一定恢复原样 */
    private final Runnable micRestoreGuard = new Runnable() {
        @Override
        public void run() {
            recording = false;
            restoreMicButton();
        }
    };

    /** 语音按钮唯一点击入口：没在录音 → 原来的 startVoiceInput；正在录音 → 立刻结束 */
    private void onMicClick() {
        if (recording) {
            stopRecordingNow();
            return;
        }
        if (voiceBusy) {
            toast("语音正在处理，稍等一下");
            return;
        }
        startVoiceInput();
    }

    /** 手动结束录音：只翻标志，读线程下一轮 read 返回就退出循环、直接进识别 */
    private void stopRecordingNow() {
        if (!recording) return;
        recording = false;
        voiceBusy = true;                 // 识别期间不允许再起一次录音
        restoreMicButton();
        setListeningHint("正在识别…");
    }

    /** 进入录音态：红色圆底 + 白色停止图标，并挂一个 12 秒兜底恢复 */
    private void enterMicRecordingUi() {
        setMicButtonState(true);
        main.removeCallbacks(micRestoreGuard);
        main.postDelayed(micRestoreGuard, 12000);
    }

    /** 任何结束路径（手动 / 静音 / 超时 / onDestroy / 失败）都回到白底麦克风，且可点击 */
    private void restoreMicButton() {
        main.removeCallbacks(micRestoreGuard);
        setMicButtonState(false);
    }

    private void setMicButtonState(boolean rec) {
        if (addMic == null) addMic = findViewById(R.id.add_mic);
        if (addMic == null) return;
        addMic.setBackgroundResource(rec ? R.drawable.bg_mic_recording : R.drawable.bg_round_white);
        addMic.setImageResource(rec ? R.drawable.ic_mic_stop : R.drawable.ic_mic);
        if (rec) {
            addMic.setColorFilter(Color.WHITE);       // 停止图标强制纯白
            addMic.setContentDescription("点一下结束录音");
        } else {
            addMic.clearColorFilter();
            addMic.setContentDescription("语音输入");
        }
        addMic.setClickable(true);
        addMic.setEnabled(true);
    }

    private void setListeningHint(String text) {
        android.widget.TextView hint = findViewById(R.id.add_ai_hint);
        if (hint == null) return;
        hint.setVisibility(android.view.View.VISIBLE);
        hint.setText(text);
    }

    /** 模型就绪后开始录音：录音中再点一下按钮立刻结束，10 秒兜底自动结束 */
    private void startRecording(final android.widget.TextView hint) {
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            voiceBusy=false;restoreMicButton();android.widget.Toast.makeText(this,"请先授予麦克风权限",android.widget.Toast.LENGTH_SHORT).show();return;
        }
        final int rate = 16000;
        final int minBuf = android.media.AudioRecord.getMinBufferSize(rate,
                android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT);
        try {
            recorder = new android.media.AudioRecord(android.media.MediaRecorder.AudioSource.MIC,
                    rate, android.media.AudioFormat.CHANNEL_IN_MONO,
                    android.media.AudioFormat.ENCODING_PCM_16BIT, Math.max(minBuf, rate * 2));
            recorder.startRecording();
        } catch (Exception e) {
            recorder = null;
            recording = false;
            voiceBusy = false;
            restoreMicButton();
            android.widget.Toast.makeText(this, "麦克风打不开", android.widget.Toast.LENGTH_SHORT).show();
            launchRecognizer();
            return;
        }
        recording = true;
        voiceBusy = false;                // 录音期间由 recording 管重入
        enterMicRecordingUi();
        if (hint != null) {
            hint.setVisibility(android.view.View.VISIBLE);
            hint.setText("正在听…点一下结束");
        }
        final android.media.AudioRecord rec = recorder;
        new Thread(() -> {
            java.util.List<Short> all = new java.util.ArrayList<>();
            short[] buf = new short[1600];
            long lastLoud = System.currentTimeMillis();
            long start = System.currentTimeMillis();
            try {
                while (recording) {
                    int n = rec.read(buf, 0, buf.length);
                    if (n > 0) {
                        boolean loud = false;
                        for (int i = 0; i < n; i++) {
                            all.add(buf[i]);
                            if (Math.abs(buf[i]) > 1200) loud = true;
                        }
                        if (loud) lastLoud = System.currentTimeMillis();
                    }
                    long now = System.currentTimeMillis();
                    if (now - start > 10000) break;                                  // 10 秒兜底，防止忘了点
                    if (now - lastLoud > 1200 && now - start > 1500) break;
                }
            } catch (Exception ignored) {
            } finally {
                recording = false;
            }
            try { rec.stop(); } catch (Exception ignored) { }
            try { rec.release(); } catch (Exception ignored) { }
            recorder = null;
            voiceBusy = true;                 // 识别中，挡住重复点击
            // 不管哪种方式结束，先把按钮恢复原样，等识别结果
            runOnUiThread(() -> {
                restoreMicButton();
                if (hint != null) hint.setText("正在识别…");
            });

            if (isFinishing() || isDestroyed()) return;
            float[] samples = new float[all.size()];
            for (int i = 0; i < all.size(); i++) samples[i] = all.get(i) / 32768f;
            final String text = asr == null ? "" : asr.recognize(samples);
            runOnUiThread(() -> {
                voiceBusy = false;
                restoreMicButton();
                if (isFinishing() || isDestroyed()) return;
                if (hint != null) hint.setVisibility(android.view.View.GONE);
                if (text == null || text.trim().isEmpty()) {
                    android.widget.Toast.makeText(this, "没听清，再说一次",
                            android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                android.widget.EditText p = findViewById(R.id.add_prompt);
                if (p != null) p.setText(text);
                voiceUsed = true;
                voiceText = text;
                renderSource();
                android.widget.Toast.makeText(this, "听成：「" + text + "」，确认后点保存",
                        android.widget.Toast.LENGTH_SHORT).show();
            });
        }).start();
    }

    /* 语音输入：首次必须选一次是否下载本地模型 */
    private void startVoiceInput() {
        voiceBusy = true;
        if (!com.wool.ledger.util.VoiceModel.ready(this)) {
            com.wool.ledger.ui.voice.VoiceModelDialog.show(this,
                    new com.wool.ledger.ui.voice.VoiceModelDialog.OnDone() {
                        @Override
                        public void onReady() {
                            if (com.wool.ledger.ai.SherpaAsr.libPresent()) startLocalRecognition();
                            else launchRecognizer();
                        }

                        @Override
                        public void onSkip() {
                            android.widget.Toast.makeText(AddRecordActivity.this,
                                    "已跳过下载，本次仍可使用系统识别", android.widget.Toast.LENGTH_SHORT).show();
                            launchRecognizer();
                        }
                    });
            return;
        }
        if (com.wool.ledger.ai.SherpaAsr.libPresent()) {
            startLocalRecognition();
            return;
        }
        launchRecognizer();
    }

    /** 拉起系统识别（有离线能力时优先离线） */
    private void launchRecognizer() {
        voiceBusy = false;
        try {
            android.content.Intent it = new android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            it.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            it.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "zh-CN");
            it.putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "请输入账目，例如「午饭 15 元」");
            if (android.os.Build.VERSION.SDK_INT >= 23) {
                it.putExtra(android.speech.RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
            }
            startActivityForResult(it, 7001);
        } catch (Exception e) {
            android.widget.Toast.makeText(this, "这台设备没有语音识别服务", android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] perms, int[] results) {
        super.onRequestPermissionsResult(requestCode, perms, results);
        if (requestCode == 9101) {
            if (results.length > 0 && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                startLocalRecognition();
            } else {
                android.widget.Toast.makeText(this, "没给麦克风权限，先用系统识别",
                        android.widget.Toast.LENGTH_SHORT).show();
                launchRecognizer();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 7001 && resultCode == RESULT_OK && data != null) {
            java.util.ArrayList<String> r = data.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS);
            if (r != null && !r.isEmpty()) {
                android.widget.EditText prompt = findViewById(R.id.add_prompt);
                if (prompt != null) prompt.setText(r.get(0));
                voiceUsed = true;
                voiceText = r.get(0);
                renderSource();
                parseByAi();
            }
        }
    }

    @Override
    public void finish() {
        super.finish();
        // 返回同样是淡出（和进记一笔一致），别用主题默认的侧滑
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    /** 每次渲染后按当前主题重涂一遍，保证日夜切换后颜色一致 */
    private void repaintTheme() {
        final android.view.View root = findViewById(android.R.id.content);
        if (root == null) return;
        root.post(() -> {
            boolean dark = com.wool.ledger.util.ThemeUtil.dark(this);
            root.setBackgroundColor(com.wool.ledger.util.ThemeUtil.paper(this));
            android.view.View page = findViewById(R.id.add_root);
            if (page != null) page.setBackgroundColor(com.wool.ledger.util.ThemeUtil.paper(this));
            // 顶栏与底栏也跟随
            android.view.View bar = findViewById(R.id.add_bottom_bar);
            if (bar != null) bar.setBackgroundColor(com.wool.ledger.util.ThemeUtil.paper(this));
            // 支出/收入 胶囊轨道
            android.view.View seg = findViewById(R.id.add_seg_wrap);
            if (seg != null) {
                seg.setBackgroundResource(dark
                        ? R.drawable.add_seg_track_dark : R.drawable.add_seg_track);
            }
            // 保存按钮：深色用深底主色
            if (btnSave != null) {
                btnSave.setBackgroundResource(dark
                        ? R.drawable.bg_btn_primary_dark : R.drawable.bg_btn_primary);
            }
            // 一句话卡的虚线框：深色用深底
            android.view.View box = findViewById(R.id.add_prompt_box);
            if (box != null) {
                box.setBackgroundResource(dark
                        ? R.drawable.add_prompt_card_dark : R.drawable.add_prompt_card);
            }
            com.wool.ledger.util.ThemeUtil.repaint(this, root);
        });
    }
}
