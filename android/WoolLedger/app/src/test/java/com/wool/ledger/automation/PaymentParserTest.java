package com.wool.ledger.automation;

import static org.junit.Assert.*;

import org.junit.Test;

public class PaymentParserTest {
    @Test
    public void forecastIsNotSettled() {
        assertFalse(PaymentParser.parse("支出提醒\n商户：餐厅\n预计明日支出15元").complete);
    }

    @Test
    public void processingIsNotSettled() {
        assertFalse(PaymentParser.parse("支付成功后将于明日处理 商户：餐厅\n15元").complete);
    }

    @Test
    public void payment() {
        PaymentParser.Result r = PaymentParser.parse("支付成功\n商户：食堂\n金额15.50元");
        assertTrue(r.complete);
        assertEquals(15.5, r.amount, 0);
        assertEquals("food", r.category);
    }

    @Test
    public void balanceNotAmount() {
        PaymentParser.Result r = PaymentParser.parse("扣款成功，商户：公交\n消费3.00元，余额1000.00元");
        assertTrue(r.complete);
        assertEquals(3, r.amount, 0);
    }

    @Test
    public void income() {
        PaymentParser.Result r = PaymentParser.parse("收款到账\n付款方：张三\n人民币100.00");
        assertTrue(r.complete);
        assertEquals(1, r.type);
        assertEquals("income_other", r.category);
    }

    @Test
    public void missing() {
        PaymentParser.Result r = PaymentParser.parse("支付成功 商户：餐厅");
        assertTrue(r.relevant);
        assertFalse(r.complete);
        assertEquals(0, r.amount, 0);
    }

    @Test
    public void multiple() {
        assertFalse(PaymentParser.parse("支付成功 商户：餐厅\n原价20元，支付15元").complete);
    }

    @Test
    public void noMerchant() {
        assertFalse(PaymentParser.parse("支付成功 ¥15").complete);
    }

    @Test
    public void failed() {
        assertFalse(PaymentParser.parse("支付失败 15元").relevant);
    }

    @Test
    public void pending() {
        assertFalse(PaymentParser.parse("待支付 15元").relevant);
    }

    @Test
    public void marketing() {
        assertFalse(PaymentParser.parse("领红包 支付成功立减15元").relevant);
    }

    @Test
    public void otp() {
        assertFalse(PaymentParser.parse("验证码123456 支付15元").relevant);
    }

    @Test
    public void refund() {
        PaymentParser.Result r = PaymentParser.parse("退款到账 ¥15 商户：餐厅");
        assertTrue(r.relevant);
        assertFalse(r.complete);
    }

    @Test
    public void transfer() {
        assertFalse(PaymentParser.parse("转账成功 100元 商户：张三").complete);
    }

    @Test
    public void idsNotMoney() {
        assertEquals(0, PaymentParser.parse("支付成功 商户：餐厅 卡号1234 订单号：123456789").amount, 0);
    }

    @Test
    public void transaction() {
        assertEquals(
                "123456789", PaymentParser.parse("支付成功 商户：餐厅 15元 交易号：123456789").transactionId);
    }

    @Test
    public void formattedAmount() {
        assertEquals(1234.56, PaymentParser.parse("支付成功 商户：商城\n￥1,234.56").amount, 0);
    }

    @Test
    public void directionAmbiguity() {
        assertFalse(PaymentParser.parse("支付成功 收入 15元 商户：餐厅").complete);
    }

    @Test
    public void noArbitraryMessages() {
        assertFalse(PaymentParser.parse("今晚去食堂吃饭，15元").relevant);
    }
}
