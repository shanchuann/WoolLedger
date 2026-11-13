package com.wool.ledger.ui.bills;

import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.wool.ledger.R;
import com.wool.ledger.data.Bill;
import com.wool.ledger.data.BillDao;
import com.wool.ledger.data.Prefs;
import com.wool.ledger.data.Seed;
import com.wool.ledger.ui.add.AddRecordActivity;
import com.wool.ledger.ui.detail.BillDetailActivity;
import com.wool.ledger.util.DateUtil;
import com.wool.ledger.util.Money;
import com.wool.ledger.util.ThemeUtil;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * 账单页：页头 + 汇总胶囊 + 筛选 chips + 按天分组的流水。
 * DAO 全是同步调用，这里统一放 HandlerThread，再回主线程刷新界面。
 */
public class BillListFragment extends Fragment implements BillAdapter.Listener {

    private HandlerThread ioThread;
    private Handler bg;
    private final Handler main = new Handler(Looper.getMainLooper());

    private BillDao dao;
    private RecyclerView listView;
    private BillAdapter adapter;
    private View emptyState;
    private TextView subtitle;
    private TextView expenseText;
    private TextView incomeText;
    private TextView balanceText;
    private TextView emptyTitle;
    private TextView emptyHint;
    private final TextView[] chips = new TextView[5];

    private int monthCount = 0;
    private double lastExpense = 0;
    private double lastIncome = 0;
    private int filterIndex = BillAdapter.FILTER_ALL;
    private String catFilter = null;   // 从统计页下钻时带过来的分类名
    private String query = "";         // 搜索关键词

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_bills, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        Context context = view.getContext();
        ioThread = new HandlerThread("bills-list-io");
        ioThread.start();
        bg = new Handler(ioThread.getLooper());
        dao = new BillDao(context);

        subtitle = view.findViewById(R.id.bills_sub);
        expenseText = view.findViewById(R.id.bills_expense);
        incomeText = view.findViewById(R.id.bills_income);
        balanceText = view.findViewById(R.id.bills_balance);
        emptyState = view.findViewById(R.id.bills_empty);
        emptyTitle = view.findViewById(R.id.bills_empty_title);
        emptyHint = view.findViewById(R.id.bills_empty_hint);
        listView = view.findViewById(R.id.bills_list);

        adapter = new BillAdapter(this);
        attachSwipeToDelete(listView);
        listView.setLayoutManager(new LinearLayoutManager(context));
        listView.setAdapter(adapter);

        android.widget.EditText search = view.findViewById(R.id.bills_search);
        if (search != null) {
            if (getArguments() != null) {
                catFilter = getArguments().getString("category");
                query = getArguments().getString("keyword", "");
                if (query == null) query = "";
                search.setText(query);
            }
            search.addTextChangedListener(new android.text.TextWatcher() {
                public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
                public void onTextChanged(CharSequence s, int a, int b, int c) { }
                public void afterTextChanged(android.text.Editable e) {
                    query = e.toString().trim();
                    refresh();
                }
            });
        }
        int[] chipIds = {R.id.chip_all, R.id.chip_expense, R.id.chip_income,
                R.id.chip_study, R.id.chip_medical};
        for (int i = 0; i < chipIds.length; i++) {
            final int index = i;
            chips[i] = view.findViewById(chipIds[i]);
            chips[i].setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectFilter(index);
                }
            });
        }

        view.findViewById(R.id.bills_empty_add).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openAddRecord(0L, null, null);
            }
        });

        // 这个页面是独立 Activity（从统计/首页下钻进来），补一个页面内关闭按钮，不用只靠系统返回
        view.findViewById(R.id.bills_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                android.app.Activity a = getActivity();
                if (a != null) a.finish();
            }
        });

        applyThemeColors(view);
        selectFilter(BillAdapter.FILTER_ALL);
    }

    /** 深色（Prefs.dark）统一从 ThemeUtil 取色，浅色时效果与原版一致 */
    private void applyThemeColors(View v) {
        Context context = requireContext();
        boolean dark = ThemeUtil.dark(context);
        v.setBackgroundColor(ThemeUtil.paper(context));
        ((TextView) v.findViewById(R.id.bills_title)).setTextColor(ThemeUtil.ink(context));
        subtitle.setTextColor(ThemeUtil.ink3(context));
        expenseText.setTextColor(ThemeUtil.ink(context));
        balanceText.setTextColor(ThemeUtil.ink(context));
        incomeText.setTextColor(ContextCompat.getColor(context,
                dark ? R.color.bills_income_dark : R.color.bills_income));
        v.findViewById(R.id.bills_pill).setBackgroundResource(
                dark ? R.drawable.bills_pill_dark : R.drawable.bg_pill_white);
        View pill = v.findViewById(R.id.bills_pill);
        if (pill instanceof ViewGroup) {
            ViewGroup pillGroup = (ViewGroup) pill;
            for (int i = 0; i < pillGroup.getChildCount(); i++) {
                View cell = pillGroup.getChildAt(i);
                if (cell instanceof ViewGroup && ((ViewGroup) cell).getChildCount() > 1) {
                    View label = ((ViewGroup) cell).getChildAt(1);
                    if (label instanceof TextView) {
                        ((TextView) label).setTextColor(ThemeUtil.ink3(context));
                    }
                }
            }
        }
        v.findViewById(R.id.bills_empty_ring).setBackgroundResource(
                dark ? R.drawable.bills_ring_dark : R.drawable.bills_ring);
        ((ImageView) v.findViewById(R.id.bills_empty_icon)).setColorFilter(ContextCompat.getColor(
                context, dark ? R.color.sky : R.color.blue_deep));
        emptyTitle.setTextColor(ThemeUtil.ink(context));
        emptyHint.setTextColor(ThemeUtil.ink3(context));
        TextView emptyAdd = v.findViewById(R.id.bills_empty_add);
        emptyAdd.setBackgroundResource(dark ? R.drawable.bills_btn_primary_dark : R.drawable.bg_btn_primary);
        emptyAdd.setTextColor(dark
                ? ContextCompat.getColor(context, R.color.bills_on_primary_dark) : 0xFFFFFFFF);
    }

    @Override
    public void onResume() {
        super.onResume();
        load();
    }

    /** 供宿主（首页 / 查看全部）在别处记账后主动刷新 */
    /** 左滑删除 + Snackbar 撤销 */
    private void attachSwipeToDelete(androidx.recyclerview.widget.RecyclerView rv) {
        if (rv == null) return;
        androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback cb =
                new androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback(0,
                        androidx.recyclerview.widget.ItemTouchHelper.LEFT | androidx.recyclerview.widget.ItemTouchHelper.RIGHT) {
                    @Override
                    public boolean onMove(@NonNull androidx.recyclerview.widget.RecyclerView r,
                                          @NonNull androidx.recyclerview.widget.RecyclerView.ViewHolder a,
                                          @NonNull androidx.recyclerview.widget.RecyclerView.ViewHolder b) {
                        return false;
                    }

                    @Override
                    public int getSwipeDirs(@NonNull androidx.recyclerview.widget.RecyclerView r,
                                            @NonNull androidx.recyclerview.widget.RecyclerView.ViewHolder holder) {
                        // 只有账单行能滑，日头/页脚不响应
                        return adapter.billAt(holder.getAdapterPosition()) == null ? 0 : super.getSwipeDirs(r, holder);
                    }

                    @Override
                    public void onSwiped(@NonNull androidx.recyclerview.widget.RecyclerView.ViewHolder holder, int dir) {
                        final Bill bill = adapter.billAt(holder.getAdapterPosition());
                        if (bill == null) {
                            adapter.notifyDataSetChanged();
                            return;
                        }
                        adapter.removeBill(bill);
                        final Handler h = bg;
                        if (h != null) h.post(() -> {
                            if (dao != null) dao.delete(bill.id);
                            if (main != null) main.post(() -> refresh());
                        });
                        android.view.View rootView = getView();
                        if (rootView != null) {
                            com.google.android.material.snackbar.Snackbar
                                    .make(rootView, "记录已删除 " + bill.note, com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                                    .setAction("撤销", v -> {
                                        bill.id = 0;
                                        final Handler h2 = bg;
                                        if (h2 != null) h2.post(() -> {
                                            if (dao != null) dao.insert(bill);
                                            if (main != null) main.post(() -> refresh());
                                        });
                                    }).show();
                        }
                    }
                };
        new androidx.recyclerview.widget.ItemTouchHelper(cb).attachToRecyclerView(rv);
    }

    public void refresh() {
        load();
    }

    private void selectFilter(int index) {
        filterIndex = index;
        Context context = requireContext();
        boolean dark = ThemeUtil.dark(context);
        for (int i = 0; i < chips.length; i++) {
            TextView chip = chips[i];
            if (chip == null) continue;
            boolean on = i == index;
            if (on) {
                chip.setBackgroundResource(dark ? R.drawable.bills_chip_on_dark : R.drawable.bg_chip_on);
                chip.setTextColor(ContextCompat.getColor(context,
                        dark ? R.color.paper_dark : R.color.paper));
            } else {
                chip.setBackgroundResource(dark ? R.drawable.bills_chip_dark : R.drawable.bg_chip);
                chip.setTextColor(ThemeUtil.ink2(context));
            }
            chip.setTypeface(Typeface.create(Typeface.DEFAULT,
                    on ? Typeface.BOLD : Typeface.NORMAL));
        }
        if (adapter != null) {
            adapter.setFilter(index);
            updateEmpty();
        }
    }

    private void load() {
        final Handler handler = bg;
        final BillDao localDao = dao;
        final Context context = getContext() == null ? null : getContext().getApplicationContext();
        if (handler == null || localDao == null || context == null) return;
        handler.post(new Runnable() {
            @Override
            public void run() {
                Seed.maybeSeed(context);
                String month = DateUtil.thisMonth();
                List<Bill> bills = localDao.listMonth(month);
                // 统计页下钻：按分类名过滤；搜索框：按备注/分类名过滤
                List<Bill> filtered = new java.util.ArrayList<>();
                for (Bill b : bills) {
                    if (catFilter != null && !catFilter.equals(b.categoryName())) continue;
                    if (query != null && !query.isEmpty()
                            && !(b.note != null && b.note.contains(query))
                            && !b.categoryName().contains(query)) continue;
                    filtered.add(b);
                }
                bills = filtered;
                double expense = 0;
                double income = 0;
                for (Bill b : bills) {
                    if (b.isIncome()) income += b.amount;
                    else expense += b.amount;
                }
                final List<Bill> result = bills;
                final double finalExpense = expense;
                final double finalIncome = income;
                final String monthKey = month;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!isAdded() || getView() == null || adapter == null) return;
                        render(monthKey, result, finalExpense, finalIncome);
                    }
                });
            }
        });
    }

    private void render(String monthKey, List<Bill> bills, double expense, double income) {
        monthCount = bills.size();
        subtitle.setText(monthLabel(monthKey) + " · " + monthCount + " 笔");
        if (expenseText != null) { com.wool.ledger.util.Anim.count(expenseText, lastExpense, expense, "¥"); lastExpense = expense; }
        if (incomeText != null) { com.wool.ledger.util.Anim.count(incomeText, lastIncome, income, "+¥"); lastIncome = income; }
        double net = expense - income;
        if (Math.abs(net) < 0.005) {
            balanceText.setText("¥0.00");
        } else if (net > 0) {
            balanceText.setText("−¥" + Money.fmt(net));
        } else {
            balanceText.setText("+¥" + Money.fmt(-net));
        }
        adapter.setMonthBills(bills);
        adapter.setFilter(filterIndex);
        updateEmpty();
    }

    private void updateEmpty() {
        if (emptyState == null || adapter == null || listView == null) return;
        boolean empty = adapter.getVisibleCount() == 0;
        emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
        listView.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (!empty) return;
        boolean monthHasData = monthCount > 0;
        emptyTitle.setText(monthHasData ? "暂无记录" : "这个月还没有账");
        emptyHint.setText(monthHasData
                ? "换一个分类看看，或者现在记一笔。"
                : "记第一笔，课表上就会出现一格。");
    }

    private static String monthLabel(String ym) {
        try {
            Calendar c = Calendar.getInstance();
            c.setTime(new SimpleDateFormat("yyyy-MM", Locale.CHINA).parse(ym));
            return new SimpleDateFormat("MMMM yyyy", Locale.ENGLISH).format(c.getTime());
        } catch (Exception e) {
            return ym;
        }
    }

    @Override
    public void onBillClick(Bill bill) {
        if (bill == null || getContext() == null) return;
        Intent intent = new Intent(getContext(), BillDetailActivity.class);
        intent.putExtra("bill_id", bill.id);
        startActivity(intent);
    }

    @Override
    public void onBillLongClick(Bill bill) {
        if (bill == null || !isAdded()) return;
        String when = DateUtil.monthDay(bill.billDate) + " " + bill.billTime;
        new AlertDialog.Builder(requireContext())
                .setTitle("删除这一笔？")
                .setMessage(bill.categoryName() + " ¥" + Money.fmt(bill.amount) + " · " + when
                        + "，删除后不可恢复。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        deleteBill(bill);
                    }
                })
                .show();
    }

    private void deleteBill(final Bill bill) {
        final Handler handler = bg;
        final BillDao localDao = dao;
        if (handler == null || localDao == null) return;
        handler.post(new Runnable() {
            @Override
            public void run() {
                localDao.delete(bill.id);
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!isAdded() || getContext() == null) return;
                        Toast.makeText(getContext(), "记录已删除这一笔", Toast.LENGTH_SHORT).show();
                        load();
                    }
                });
            }
        });
    }

    private void openAddRecord(long billId, String date, String slot) {
        Context context = getContext();
        if (context == null) return;
        Intent intent = new Intent(context, AddRecordActivity.class);
        intent.putExtra("bill_id", billId);
        intent.putExtra("date", date == null ? DateUtil.today() : date);
        intent.putExtra("slot", slot == null ? Prefs.defSlot(context) : slot);
        startActivity(intent);
    }

    @Override
    public void onDestroyView() {
        if (ioThread != null) {
            ioThread.quitSafely();
            ioThread = null;
        }
        bg = null;
        adapter = null;
        listView = null;
        emptyState = null;
        super.onDestroyView();
    }
}
