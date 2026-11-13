package com.wool.ledger.ui.bills;

import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.wool.ledger.R;
import com.wool.ledger.data.Bill;
import com.wool.ledger.util.DateUtil;
import com.wool.ledger.util.Money;
import com.wool.ledger.util.ThemeUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 账单流水：按天分组的多 ViewType 适配器。
 * 日头（今天 · 10 月 23 日 · 周五 … 支出 ¥270.00）/ 一笔 / 页脚（本月共 N 笔）。
 */
public class BillAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public static final int FILTER_ALL = 0;
    public static final int FILTER_EXPENSE = 1;
    public static final int FILTER_INCOME = 2;
    public static final int FILTER_STUDY = 3;
    public static final int FILTER_MEDICAL = 4;

    private static final int TYPE_DAY = 0;
    private static final int TYPE_BILL = 1;
    private static final int TYPE_FOOTER = 2;

    public interface Listener {
        void onBillClick(Bill bill);

        void onBillLongClick(Bill bill);
    }

    private static final class Row {
        int type;
        String dayTitle = "";
        String daySub = "";
        String daySum = "";
        String footer = "";
        Bill bill;
    }

    private final Listener listener;
    private final List<Row> rows = new ArrayList<>();
    private List<Bill> monthBills = new ArrayList<>();
    private int filter = FILTER_ALL;
    private int visibleCount = 0;

    public BillAdapter(Listener listener) {
        this.listener = listener;
    }

    /** 整月账单；DAO 已按 bill_date DESC, bill_time DESC 排序 */
    public void setMonthBills(List<Bill> bills) {
        this.monthBills = bills == null ? new ArrayList<Bill>() : bills;
        rebuild();
    }

    public int getFilter() {
        return filter;
    }

    public void setFilter(int filter) {
        if (this.filter == filter) return;
        this.filter = filter;
        rebuild();
    }

    /** 当前筛选下可见的笔数（空态判断用） */
    public int getVisibleCount() {
        return visibleCount;
    }

    private boolean match(Bill b) {
        switch (filter) {
            case FILTER_EXPENSE:
                return !b.isIncome();
            case FILTER_INCOME:
                return b.isIncome();
            case FILTER_STUDY:
                return "study".equals(b.category);
            case FILTER_MEDICAL:
                return "medical".equals(b.category);
            default:
                return true;
        }
    }

    private void rebuild() {
        rows.clear();
        visibleCount = 0;
        Map<String, List<Bill>> groups = new LinkedHashMap<>();
        for (Bill b : monthBills) {
            if (!match(b)) continue;
            visibleCount++;
            List<Bill> list = groups.get(b.billDate);
            if (list == null) {
                list = new ArrayList<>();
                groups.put(b.billDate, list);
            }
            list.add(b);
        }
        String today = DateUtil.today();
        for (Map.Entry<String, List<Bill>> e : groups.entrySet()) {
            rows.add(dayRow(e.getKey(), e.getValue(), today));
            for (Bill b : e.getValue()) {
                Row r = new Row();
                r.type = TYPE_BILL;
                r.bill = b;
                rows.add(r);
            }
        }
        if (visibleCount > 0) {
            Row footer = new Row();
            footer.type = TYPE_FOOTER;
            footer.footer = filter == FILTER_ALL
                    ? "本月共 " + visibleCount + " 笔"
                    : "筛选出 " + visibleCount + " 笔";
            rows.add(footer);
        }
        notifyDataSetChanged();
    }

    private Row dayRow(String date, List<Bill> items, String today) {
        Row r = new Row();
        r.type = TYPE_DAY;
        boolean isToday = date.equals(today);
        String weekday = "周" + DateUtil.weekdayCn(date);
        r.dayTitle = isToday ? "今天" : DateUtil.monthDay(date);
        r.daySub = isToday ? " · " + DateUtil.monthDay(date) + " · " + weekday : " · " + weekday;
        double expense = 0;
        double income = 0;
        for (Bill b : items) {
            if (b.isIncome()) income += b.amount;
            else expense += b.amount;
        }
        StringBuilder sb = new StringBuilder();
        if (expense > 0) sb.append("支出 ¥").append(Money.fmt(expense));
        if (income > 0) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append("收入 ¥").append(Money.fmt(income));
        }
        r.daySum = sb.toString();
        return r;
    }

    @Override
    public int getItemViewType(int position) {
        return rows.get(position).type;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_DAY) {
            return new DayHolder(inflater.inflate(R.layout.item_day_header, parent, false));
        }
        if (viewType == TYPE_BILL) {
            return new BillHolder(inflater.inflate(R.layout.item_bill, parent, false));
        }
        TextView footer = new TextView(parent.getContext());
        int h = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 46f,
                parent.getResources().getDisplayMetrics());
        footer.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h));
        footer.setGravity(Gravity.CENTER);
        footer.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        footer.setTextColor(ContextCompat.getColor(parent.getContext(), R.color.ink3));
        footer.setLetterSpacing(0.14f);
        footer.setIncludeFontPadding(false);
        return new FootHolder(footer);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Row row = rows.get(position);
        Context context = holder.itemView.getContext();
        boolean dark = ThemeUtil.dark(context);
        int dividerColor = ContextCompat.getColor(context,
                dark ? R.color.line_dark : R.color.line2);
        if (holder instanceof DayHolder) {
            DayHolder h = (DayHolder) holder;
            h.title.setTextColor(ThemeUtil.ink(context));
            h.sub.setTextColor(ThemeUtil.ink3(context));
            h.sum.setTextColor(ThemeUtil.ink3(context));
            h.title.setText(row.dayTitle);
            h.sub.setText(row.daySub);
            h.sum.setText(row.daySum);
            h.sum.setVisibility(row.daySum.isEmpty() ? View.INVISIBLE : View.VISIBLE);
        } else if (holder instanceof BillHolder) {
            final BillHolder h = (BillHolder) holder;
            final Bill b = row.bill;
            if (b == null) return;
            h.time.setText(b.slot + " " + b.billTime);
            h.name.setText(b.note == null || b.note.isEmpty() ? "—" : b.note);
            h.cat.setText(b.categoryName() + " · " + b.slot);
            h.amount.setText((b.isIncome() ? "+" : "-") + Money.fmt(b.amount));
            h.time.setTextColor(ThemeUtil.ink3(context));
            h.name.setTextColor(ThemeUtil.ink(context));
            h.cat.setTextColor(ThemeUtil.ink3(context));
            if (b.isIncome()) {
                h.amount.setTextColor(ContextCompat.getColor(context,
                        dark ? R.color.bills_income_dark : R.color.bills_income));
            } else {
                h.amount.setTextColor(ThemeUtil.ink(context));
            }
            h.divider.setBackgroundColor(dividerColor);
            // 金额单独也能点，避免用户点在数字上没反应
            h.amount.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (listener != null) listener.onBillClick(b);
                }
            });
            h.itemView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (listener != null) listener.onBillClick(b);
                }
            });
            h.itemView.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    if (listener != null) {
                        listener.onBillLongClick(b);
                        return true;
                    }
                    return false;
                }
            });
        } else if (holder instanceof FootHolder) {
            FootHolder h = (FootHolder) holder;
            h.text.setTextColor(ThemeUtil.ink3(context));
            h.text.setText(row.footer);
        }
    }

    /** 左滑用：position 对应的账单（不是账单行则返回 null） */
    public Bill billAt(int position) {
        if (position < 0 || position >= rows.size()) return null;
        Row r = rows.get(position);
        return r.bill;
    }

    /** 左滑用：从数据源移除并通知 */
    public void removeBill(Bill bill) {
        if (bill == null) return;
        monthBills.remove(bill);
        rebuild();
    }

    /** 左滑撤销：加回来并重建 */
    public void restoreBill(Bill bill) {
        if (bill == null) return;
        monthBills.add(bill);
        rebuild();
    }

    public int getItemCount() {
        return rows.size();
    }

    private static class DayHolder extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView sub;
        final TextView sum;

        DayHolder(View v) {
            super(v);
            title = v.findViewById(R.id.day_title);
            sub = v.findViewById(R.id.day_sub);
            sum = v.findViewById(R.id.day_sum);
        }
    }

    private static class BillHolder extends RecyclerView.ViewHolder {
        final TextView time;
        final TextView name;
        final TextView cat;
        final TextView amount;
        final View divider;

        BillHolder(View v) {
            super(v);
            time = v.findViewById(R.id.bill_time);
            name = v.findViewById(R.id.bill_name);
            cat = v.findViewById(R.id.bill_cat);
            amount = v.findViewById(R.id.bill_amount);
            divider = v.findViewById(R.id.bill_divider);
        }
    }

    private static class FootHolder extends RecyclerView.ViewHolder {
        final TextView text;

        FootHolder(TextView v) {
            super(v);
            text = v;
        }
    }
}
