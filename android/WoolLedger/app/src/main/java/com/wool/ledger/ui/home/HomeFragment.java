package com.wool.ledger.ui.home;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.wool.ledger.BillListActivity;
import com.wool.ledger.R;
import com.wool.ledger.data.Bill;
import com.wool.ledger.data.BillDao;
import com.wool.ledger.data.Prefs;
import com.wool.ledger.ui.add.AddRecordActivity;
import com.wool.ledger.ui.cell.CellActivity;
import com.wool.ledger.ui.chat.ChatActivity;
import com.wool.ledger.ui.detail.BillDetailActivity;
import com.wool.ledger.util.DateUtil;
import com.wool.ledger.util.Money;
import com.wool.ledger.util.ThemeUtil;
import com.wool.ledger.view.TimetableView;

import java.util.ArrayList;
import java.util.List;

/** 首页：消费课表 + 本月数字 + 预算 + 最近记账。 */
public class HomeFragment extends Fragment {

    private TimetableView table;
    private TextView weekRange, weekTotal, todayTag, todaySpent, monthExpense, monthIncome, monthLeft;
    private TextView budgetText, budgetUsed, budgetDaily, aiSub, seeAll, homeMore;
    private ProgressBar budgetBar;
    private RecyclerView list;
    private final List<Bill> recent = new ArrayList<>();
    private Adapter adapter;
    private final Handler main = new Handler(Looper.getMainLooper());
    private double lastMonthExpense = 0;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup c, @Nullable Bundle s) {
        return inflater.inflate(R.layout.fragment_home, c, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle s) {
        table = v.findViewById(R.id.timetable);
        com.wool.ledger.util.DragBus.setTable(table);
        weekRange = v.findViewById(R.id.week_range);
        weekTotal = v.findViewById(R.id.week_total);
        todayTag = v.findViewById(R.id.today_tag);
        todaySpent = v.findViewById(R.id.today_spent);
        monthExpense = v.findViewById(R.id.month_expense);
        monthIncome = v.findViewById(R.id.month_income);
        monthLeft = v.findViewById(R.id.month_left);
        budgetText = v.findViewById(R.id.budget_text);
        budgetBar = v.findViewById(R.id.budget_bar);
        budgetUsed = v.findViewById(R.id.budget_used);
        budgetDaily = v.findViewById(R.id.budget_daily);
        aiSub = v.findViewById(R.id.ai_sub);
        seeAll = v.findViewById(R.id.see_all);
        homeMore = v.findViewById(R.id.home_more);
        list = v.findViewById(R.id.home_list);

        v.findViewById(R.id.ai_row).setOnClickListener(x ->
                startActivity(new Intent(getContext(), ChatActivity.class)));
        TextView capture = v.findViewById(R.id.payment_inbox_home);
        capture.setTextColor(ThemeUtil.ink(getContext()));
        capture.setOnClickListener(x -> startActivity(new Intent(getContext(), com.wool.ledger.automation.PaymentInboxActivity.class)));
        seeAll.setOnClickListener(x -> startActivity(new Intent(getContext(), BillListActivity.class)));
        homeMore.setOnClickListener(x -> startActivity(new Intent(getContext(), BillListActivity.class)));
        v.findViewById(R.id.month_block).setOnClickListener(x ->
                startActivity(new Intent(getContext(), BillListActivity.class)));

        adapter = new Adapter();
        list.setLayoutManager(new LinearLayoutManager(getContext()));
        list.setAdapter(adapter);
        list.setNestedScrollingEnabled(false);

        table.setListener((date, slot, billId) -> {
            if (billId > 0) {
                // 先抓一张课表页快照：「这一格」里长按拖拽时用它还原课表，
                // 这样同一条手势能继续拖到目标格（系统 drag 不能跨 Activity finish）
                com.wool.ledger.util.DragBus.setSnapshot(captureWindowForDrag());
                captureWindowForDragAsync();
                Intent i = new Intent(getContext(), CellActivity.class);
                i.putExtra("date", date);
                i.putExtra("slot", slot);
                startActivity(i);
            } else {
                Intent i = new Intent(getContext(), AddRecordActivity.class);
                i.putExtra("bill_id", 0L);
                i.putExtra("date", date);
                i.putExtra("slot", slot);
                startActivityForResult(i, 2001);
            }
        });

        table.setOnCellEdit(new TimetableView.OnCellEdit() {
            @Override
            public void onMove(java.util.List<Long> billIds, final String toDate, final String toSlot) {
                moveBills(billIds, toDate, toSlot);
            }

            @Override
            public void onDelete(java.util.List<Long> billIds) {
                final BillDao dao = new BillDao(getContext());
                new Thread(() -> {
                    for (Long id : billIds) dao.delete(id);
                    main.post(() -> {
                        if (!isAdded()) return;
                        load();
                        android.widget.Toast.makeText(getContext(), "已删除 " + billIds.size() + " 笔",
                                android.widget.Toast.LENGTH_SHORT).show();
                    });
                }).start();
            }
        });

        // 从「这一格」长按拖过来的系统拖放：手指不用抬，直接拖到目标格松手
        table.setOnDragListener((view, e) -> {
            switch (e.getAction()) {
                case android.view.DragEvent.ACTION_DRAG_STARTED:
                    return e.getClipDescription() != null
                            && e.getClipDescription().hasMimeType(android.content.ClipDescription.MIMETYPE_TEXT_PLAIN);
                case android.view.DragEvent.ACTION_DRAG_ENTERED:
                case android.view.DragEvent.ACTION_DRAG_LOCATION:
                    table.updateDropTarget(e.getX(), e.getY());
                    return true;
                case android.view.DragEvent.ACTION_DRAG_EXITED:
                    table.updateDropTarget(-1, -1);
                    return true;
                case android.view.DragEvent.ACTION_DROP: {
                    String toDate = table.dropDateAt(e.getX());
                    String toSlot = table.dropSlotAt(e.getY());
                    Object local = e.getLocalState();
                    table.endExternalDrag();
                    if (toDate != null && toSlot != null && local instanceof Long) {
                        java.util.List<Long> ids = new java.util.ArrayList<>();
                        ids.add((Long) local);
                        moveBills(ids, toDate, toSlot);
                    }
                    return true;
                }
                case android.view.DragEvent.ACTION_DRAG_ENDED:
                    table.endExternalDrag();
                    return true;
                default:
                    return true;
            }
        });
        // 落在课表外面也算结束（不落数据，只收尾）
        v.setOnDragListener((view, e) -> {
            if (e.getAction() == android.view.DragEvent.ACTION_DROP
                    || e.getAction() == android.view.DragEvent.ACTION_DRAG_ENDED) {
                table.endExternalDrag();
            }
            return true;
        });

        applyThemeColors(v);
        load();
    }

    /** 改日期/时段（课表内拖动、跨页拖放都用这一条） */
    private void moveBills(java.util.List<Long> billIds, final String toDate, final String toSlot) {
        final BillDao dao = new BillDao(getContext());
        new Thread(() -> {
            for (Long id : billIds) {
                Bill b = dao.get(id);
                if (b != null) {
                    b.billDate = toDate;
                    b.slot = toSlot;
                    dao.update(b);
                }
            }
            main.post(() -> {
                if (!isAdded()) return;
                load();
                android.widget.Toast.makeText(getContext(),
                        "已移动 " + billIds.size() + " 笔至 " + DateUtil.shortDate(toDate) + " · " + toSlot,
                        android.widget.Toast.LENGTH_SHORT).show();
            });
        }).start();
    }

    private void applyThemeColors(View v) {
        boolean dark = ThemeUtil.dark(getContext());
        v.setBackgroundColor(ThemeUtil.paper(getContext()));
        ((TextView) v.findViewById(R.id.brand_title)).setTextColor(dark ? 0xFFF3EFE4 : 0xFF1A1F24);
        ((TextView) v.findViewById(R.id.brand_sub)).setTextColor(ThemeUtil.ink3(getContext()));
        for (int id : new int[]{R.id.week_range, R.id.week_total, R.id.budget_text, R.id.budget_used,
                R.id.budget_daily, R.id.ai_sub, R.id.see_all, R.id.home_more}) {
            ((TextView) v.findViewById(id)).setTextColor(ThemeUtil.ink3(getContext()));
        }
        monthExpense.setTextColor(ThemeUtil.ink(getContext()));
        monthLeft.setTextColor(ThemeUtil.ink3(getContext()));
        todaySpent.setTextColor(ThemeUtil.ink2(getContext()));
        v.findViewById(R.id.ai_row).setBackgroundResource(dark ? R.drawable.bg_card_dark : R.drawable.bg_card);
    }

    public void refresh() {
        load();
    }

    /** PixelCopy 抓真实的窗口合成结果（部分机型 decor.draw 会得到空白帧） */
    private void captureWindowForDragAsync() {
        if (android.os.Build.VERSION.SDK_INT < 26 || getActivity() == null) return;
        android.view.View decor = getActivity().getWindow().getDecorView();
        int w = decor.getWidth();
        int h = decor.getHeight();
        if (w <= 0 || h <= 0) return;
        try {
            android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                    w, h, android.graphics.Bitmap.Config.ARGB_8888);
            android.view.PixelCopy.request(getActivity().getWindow(), bmp, result -> {
                if (result == android.view.PixelCopy.SUCCESS && !bmp.isRecycled()) {
                    com.wool.ledger.util.DragBus.setSnapshot(bmp);
                } else if (!bmp.isRecycled()) {
                    bmp.recycle();
                }
            }, new android.os.Handler(android.os.Looper.getMainLooper()));
        } catch (Throwable ignored) {
        }
    }

    /** 把当前 Activity 整窗画到一张 Bitmap，供「这一格」还原课表 */
    private android.graphics.Bitmap captureWindowForDrag() {
        if (getActivity() == null) return null;
        android.view.View decor = getActivity().getWindow().getDecorView();
        int w = decor.getWidth();
        int h = decor.getHeight();
        if (w <= 0 || h <= 0) return null;
        try {
            android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                    w, h, android.graphics.Bitmap.Config.ARGB_8888);
            decor.draw(new android.graphics.Canvas(bmp));
            return bmp;
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        com.wool.ledger.util.DragBus.setTable(null);
        table = null;
    }

    @Override
    public void onResume() {
        super.onResume();
        if(getView()!=null){TextView capture=getView().findViewById(R.id.payment_inbox_home);int pending=new com.wool.ledger.automation.PaymentStore(requireContext()).pendingCount();capture.setText("支付通知 · "+pending+" 条待确认  ›");}
        load();
        // 从「这一格」长按拖过来：源格先空着，落点跟着系统拖放的高亮走
        if (com.wool.ledger.util.DragBus.active() && table != null) {
            final long id = com.wool.ledger.util.DragBus.billId;
            com.wool.ledger.util.DragBus.clear();
            table.post(() -> table.beginExternalDrag(java.util.Collections.singletonList(id)));
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == android.app.Activity.RESULT_OK) load();
    }

    private void load() {
        final android.content.Context ctx = getContext();
        if (ctx == null) return;
        final String today = DateUtil.today();
        final String weekStart = DateUtil.addDays(today, -DateUtil.weekdayIndex(today));
        final String weekEnd = DateUtil.addDays(weekStart, 6);
        final String month = DateUtil.thisMonth();

        new Thread(() -> {
            BillDao dao = new BillDao(ctx);
            List<Bill> monthBills = dao.listMonth(month);
            final List<Bill> weekBills = new ArrayList<>();
            for (Bill b : monthBills) {
                if (b.billDate.compareTo(weekStart) >= 0 && b.billDate.compareTo(weekEnd) <= 0) {
                    weekBills.add(b);
                }
            }
            final double[] totals = dao.totals(month + "-01", month + "-31");
            final double[] dayTotals = dao.totals(today, today);
            final List<Bill> recents = new ArrayList<>();
            for (Bill b : dao.listAll()) {
                if (recents.size() >= 10) break;
                recents.add(b);
            }
            final int monthCount = dao.count(month + "-01", month + "-31");
            final double weekSum = sumExpense(weekBills);
            final double weekIncome = sumIncome(weekBills);

            main.post(() -> {
                if (!isAdded() || getView() == null) return;
                weekRange.setText("本周课表 " + shortMd(weekStart) + "—" + shortMd(weekEnd));
                weekTotal.setText("支 ¥" + Money.fmt(weekSum) + " · 收 ¥" + Money.fmt(weekIncome));
                table.setData(weekStart, weekBills);

                todayTag.setText("今天 · 周" + DateUtil.weekdayCn(today));
        // 荧光标记：只在文字下沿画一条，不做整块黄底
        todayTag.setBackground(new com.wool.ledger.util.Highlighter(
                getResources().getDrawable(R.drawable.bg_hl, getContext().getTheme()), 6 * getResources().getDisplayMetrics().density));
                todaySpent.setText("支出 ¥" + Money.fmt(dayTotals[0]) + " · 收入 ¥" + Money.fmt(dayTotals[1]));

                com.wool.ledger.util.Anim.count(monthExpense, lastMonthExpense, totals[0], "¥");
                lastMonthExpense = totals[0];
                monthIncome.setText("收入 +¥" + Money.fmt(totals[1]));
                monthIncome.setTextColor(com.wool.ledger.util.ThemeUtil.dark(ctx)
                        ? 0xFF79D3A6 : 0xFF2C8A63);
                double left = totals[1] - totals[0];
                monthLeft.setText("结余 " + (left >= 0 ? "+" : "−") + "¥" + Money.fmt(Math.abs(left)));

                double budget = Prefs.budget(ctx);
                int pct = budget <= 0 ? 0 : (int) Math.min(100, Math.round(totals[0] / budget * 100));
                budgetText.setText(Money.fmt(totals[0]) + " / " + Money.fmt(budget));
                budgetBar.setProgress(pct);
                budgetUsed.setText("已用 " + pct + "%");
                int restDays = Math.max(1, DateUtil.daysInMonth(month) - Integer.parseInt(today.substring(8, 10)) + 1);
                double daily = Math.max(0, (budget - totals[0]) / restDays);
                budgetDaily.setText("日均可花 ¥" + Math.round(daily));

                recent.clear();
                recent.addAll(recents);
                adapter.notifyDataSetChanged();
                homeMore.setText(monthCount > recent.size()
                        ? ("显示更多 · 本月共 " + monthCount + " 笔")
                        : "查看全部账单");

                aiSub.setText(weekBills.isEmpty()
                        ? "这一周还没记账，先记第一笔"
                        : (weekBills.size() + " 笔里午饭最多，夜宵集中在周末"));
            });
        }).start();
    }

    private static double sumIncome(List<Bill> list) {
        double s = 0;
        for (Bill b : list) if (b.isIncome()) s += b.amount;
        return s;
    }

    private static double sumExpense(List<Bill> list) {
        double s = 0;
        for (Bill b : list) if (!b.isIncome()) s += b.amount;
        return s;
    }

    private static String shortMd(String d) {
        return d.substring(5, 7) + "." + d.substring(8, 10);
    }

    private class Adapter extends RecyclerView.Adapter<Adapter.VH> {

        class VH extends RecyclerView.ViewHolder {
            TextView time, note, cat, amount;

            VH(View v) {
                super(v);
                time = v.findViewById(R.id.item_time);
                note = v.findViewById(R.id.item_note);
                cat = v.findViewById(R.id.item_cat);
                amount = v.findViewById(R.id.item_amount);
            }
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_home_bill, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            Bill b = recent.get(pos);
            android.content.Context ctx = h.itemView.getContext();
            h.time.setText(b.billTime);
            h.note.setText(b.note);
            h.cat.setText(b.categoryName() + " · " + b.slot);
            h.amount.setText((b.isIncome() ? "+" : "-") + Money.fmt(b.amount));
            h.amount.setTextColor(b.isIncome() ? ctx.getColor(R.color.green) : ThemeUtil.ink(ctx));
            h.note.setTextColor(ThemeUtil.ink(ctx));
            h.time.setTextColor(ThemeUtil.ink3(ctx));
            h.cat.setTextColor(ThemeUtil.ink3(ctx));
            h.itemView.setOnClickListener(v -> {
                Intent i = new Intent(ctx, BillDetailActivity.class);
                i.putExtra("bill_id", b.id);
                startActivity(i);
            });
        }

        @Override
        public int getItemCount() {
            return recent.size();
        }
    }
}
