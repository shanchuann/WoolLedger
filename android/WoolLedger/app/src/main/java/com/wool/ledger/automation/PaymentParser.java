package com.wool.ledger.automation;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.*;

/** Conservative local rules. Notification text is untrusted data, never an instruction. */
public final class PaymentParser {
    public static final class Result {
        public boolean relevant, complete;
        public double amount;
        public int type;
        public String merchant = "", category = "other", reason = "", transactionId = "";
    }

    public static Result parse(String input) {
        Result r = new Result();
        String s = input == null ? "" : input.replace('\u00a0', ' ').trim();
        if (s.length() > 3000) s = s.substring(0, 3000);
        if (s.matches("(?s).*(验证码|校验码|待支付|支付失败|付款失败|交易失败|支付取消|未支付|优惠券|领红包|广告|限时优惠).*")) return r;
        boolean
                expense =
                        s.matches(
                                "(?s).*(支付成功|付款成功|扣款成功|消费成功|成功支付|成功付款|支付凭证|交易成功|付款金额|消费支出|支出|消费人民币|消费金额).*"),
                income = s.matches("(?s).*(收款到账|收款成功|收款通知|微信支付收款|到账通知|收入|入账|到账人民币).*"),
                special = s.matches("(?s).*(退款|退回|转账|还款|提现|充值).*");
        r.relevant = expense || income || special;
        if (!r.relevant) return r;
        r.type = income ? 1 : 0;
        r.category = income ? "income_other" : "other";
        // Exclude balance amounts; never use card suffixes, order IDs or dates as amounts.
        String moneyText = s.replaceAll("(?:可用余额|账户余额|余额|剩余)[^\\n，,；;]*", "");
        Matcher m =
                Pattern.compile(
                                "(?:[¥￥]|人民币|RMB|CNY)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)(?![0-9.])|(?<![0-9.])([0-9][0-9,]*(?:\\.[0-9]{1,2})?)\\s*元")
                        .matcher(moneyText);
        Set<String> amounts = new HashSet<>();
        while (m.find()) {
            try {
                BigDecimal a =
                        new BigDecimal(
                                (m.group(1) != null ? m.group(1) : m.group(2)).replace(",", ""));
                if (a.signum() > 0 && a.compareTo(new BigDecimal("10000000")) <= 0)
                    amounts.add(a.stripTrailingZeros().toPlainString());
            } catch (Exception ignored) {
            }
        }
        if (amounts.size() == 1) r.amount = Double.parseDouble(amounts.iterator().next());
        Matcher merchant =
                Pattern.compile("(?:商户|商家|收款方|付款方|交易对象)[：:\\s]+([^\\n，,；;]{1,60})").matcher(s);
        if (merchant.find()) r.merchant = merchant.group(1).trim();
        if (r.merchant.isEmpty()) {
            merchant = Pattern.compile("(?:向|在)\\s*([^\\n，,；;]{1,40}?)\\s*(?:支付|付款|消费)").matcher(s);
            if (merchant.find()) r.merchant = merchant.group(1).trim();
        }
        Matcher tx = Pattern.compile("(?:交易单号|交易号|订单号)[：:\\s]+([A-Za-z0-9_-]{6,80})").matcher(s);
        if (tx.find()) r.transactionId = tx.group(1);
        if (special) r.reason = "退款、转账、还款或资金划转，请核对后处理";
        else if (expense == income) r.reason = "收支方向不明确";
        else if (amounts.isEmpty()) r.reason = "未识别到交易金额";
        else if (amounts.size() > 1) r.reason = "出现多个金额，请确认实际交易金额";
        else if (r.merchant.isEmpty()) r.reason = "缺少商户或交易对象";
        else if (!s.matches("(?s).*(支付成功|付款成功|扣款成功|消费成功|成功支付|成功付款|交易成功|收款到账|收款成功|已到账|已入账).*"))
            r.reason = "缺少明确的交易成功状态";
        else if (s.matches("(?s).*(预计|预约|待扣|处理中|正在处理|稍后|将于|待入账).*")) r.reason = "交易尚未明确完成";
        else r.complete = true;
        if (r.type == 0) {
            String[][] cats = {
                {"food", "餐|饭|咖啡|奶茶|食堂|外卖"},
                {"transport", "地铁|公交|打车|滴滴|铁路"},
                {"shop", "购物|商城|淘宝|京东"},
                {"daily", "超市|日用"},
                {"medical", "医院|药房"}
            };
            for (String[] c : cats)
                if (r.merchant.matches(".*(" + c[1] + ").*")) {
                    r.category = c[0];
                    break;
                }
        }
        return r;
    }
}
