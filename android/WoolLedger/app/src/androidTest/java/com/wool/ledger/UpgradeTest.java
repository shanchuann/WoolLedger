package com.wool.ledger;

import static org.junit.Assert.*;

import android.content.*;
import android.database.*;
import android.database.sqlite.*;

import androidx.test.platform.app.InstrumentationRegistry;

import com.wool.ledger.agent.*;
import com.wool.ledger.automation.*;
import com.wool.ledger.data.*;

import org.json.*;
import org.junit.*;

/** Run only on the disposable/read-only test emulator: these cases reset its app ledger. */
public class UpgradeTest {
    Context ctx;
    BillDao dao;
    AgentEngine agent;

    @Before
    public void setup() throws Exception {
        ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        dao = new BillDao(ctx);
        dao.clearAll();
        DbHelper.get(ctx).getWritableDatabase().delete("payment_event", null, null);
        agent = new AgentEngine(ctx);
        Prefs.setAgentOnline(ctx, false);
        Prefs.setAutoCapture(ctx, true);
        Prefs.setAutoPost(ctx, true);
        Prefs.setCapturePackages(ctx, "com.eg.android.AlipayGphone,com.tencent.mm");
    }

    @Test
    public void migrationPreservesRows() throws Exception {
        SQLiteDatabase old = SQLiteDatabase.create(null);
        old.execSQL("CREATE TABLE bill(id INTEGER PRIMARY KEY,amount REAL,note TEXT)");
        old.execSQL("INSERT INTO bill VALUES(42,15.5,'existing')");
        DbHelper.get(ctx).onUpgrade(old, 1, 2);
        try (Cursor c = old.rawQuery("SELECT * FROM bill", null)) {
            assertTrue(c.moveToFirst());
            assertEquals(42, c.getInt(0));
            assertEquals(15.5, c.getDouble(1), 0);
            assertEquals("existing", c.getString(2));
        }
        try (Cursor c = old.rawQuery("SELECT COUNT(*) FROM payment_event", null)) {
            assertTrue(c.moveToFirst());
            assertEquals(0, c.getInt(0));
        }
        old.close();
    }

    @Test
    public void notificationExactlyOnceAndUndo() throws Exception {
        PaymentStore p = new PaymentStore(ctx);
        long now = System.currentTimeMillis();
        String raw = "支付成功\n商户：食堂\n15.50元 交易号：ABC123456";
        long id = p.receive("com.eg.android.AlipayGphone", "n1", raw, now);
        assertTrue(id > 0);
        assertEquals(1, dao.listAll().size());
        assertEquals(id, p.receive("com.eg.android.AlipayGphone", "n2", raw, now + 100));
        assertEquals(1, dao.listAll().size());
        p.undo(id);
        assertTrue(dao.isEmpty());
        p.receive("com.eg.android.AlipayGphone", "n3", raw, now + 200);
        assertTrue(dao.isEmpty());
    }

    @Test
    public void pendingUpdateAndCrossSource() throws Exception {
        PaymentStore p = new PaymentStore(ctx);
        long now = System.currentTimeMillis();
        long id = p.receive("com.eg.android.AlipayGphone", "n1", "支付成功 商户：食堂", now);
        assertEquals(1, p.pendingCount());
        assertTrue(dao.isEmpty());
        assertEquals(id, p.receive("com.eg.android.AlipayGphone", "n1", "支付成功 商户：食堂\n15元", now));
        assertEquals(1, dao.listAll().size());
        assertEquals(0, p.pendingCount());
        p.receive("com.tencent.mm", "n2", "支付成功 商户：食堂\n15元", now + 200);
        assertEquals(1, dao.listAll().size());
        assertEquals(1, p.pendingCount());
    }

    @Test
    public void ignoredReplayAndDisabledSource() {
        PaymentStore p = new PaymentStore(ctx);
        long now = System.currentTimeMillis();
        assertEquals(0, p.receive("unselected.app", "x", "支付成功 商户：食堂15元", now));
        long id = p.receive("com.tencent.mm", "k", "支付成功15元", now);
        p.ignore(id);
        p.receive("com.tencent.mm", "k", "支付成功15元", now);
        assertEquals(0, p.pendingCount());
        Prefs.setAutoCapture(ctx, false);
        p.receive("com.tencent.mm", "k2", "支付成功 商户：食堂15元", now);
        assertTrue(dao.isEmpty());
    }

    @Test
    public void manualConfirmationIdempotent() throws Exception {
        PaymentStore p = new PaymentStore(ctx);
        long id = p.receive("com.tencent.mm", "k", "支付成功15元", System.currentTimeMillis());
        Bill b = PaymentStore.bill(15, 0, "food", "食堂", System.currentTimeMillis());
        p.confirm(id, b);
        try {
            p.confirm(id, b);
            fail();
        } catch (IllegalStateException expected) {
        }
        assertEquals(1, dao.listAll().size());
        p.undo(id);
        assertTrue(dao.isEmpty());
    }

    @Test
    public void agentBatchConfirmUndo() throws Exception {
        AgentEngine.Reply r = agent.handle("午饭15，地铁3");
        assertTrue(r.actionId > 0);
        assertTrue(dao.isEmpty());
        agent.execute(r.actionId, false);
        assertEquals(2, dao.listAll().size());
        try {
            agent.execute(r.actionId, false);
            fail();
        } catch (IllegalStateException expected) {
        }
        agent.execute(r.actionId, true);
        assertTrue(dao.isEmpty());
    }

    @Test
    public void agentUpdateDeleteUndoAndStale() throws Exception {
        AgentEngine.Reply r = agent.handle("午饭15");
        agent.execute(r.actionId, false);
        long id = dao.listAll().get(0).id;
        AgentEngine.Reply update = agent.handle("把 #" + id + " 改为18元");
        agent.execute(update.actionId, false);
        assertEquals(18, dao.get(id).amount, 0);
        agent.execute(update.actionId, true);
        assertEquals(15, dao.get(id).amount, 0);
        AgentEngine.Reply delete = agent.handle("删除 #" + id);
        agent.execute(delete.actionId, false);
        assertNull(dao.get(id));
        agent.execute(delete.actionId, true);
        assertEquals(15, dao.get(id).amount, 0);
        AgentEngine.Reply stale = agent.handle("删除 #" + id);
        Bill b = dao.get(id);
        b.note = "changed";
        dao.update(b);
        try {
            agent.execute(stale.actionId, false);
            fail();
        } catch (IllegalStateException expected) {
        }
        assertNotNull(dao.get(id));
    }

    @Test
    public void queryAndReconcile() throws Exception {
        AgentEngine.Reply r = agent.handle("午饭15，地铁3");
        agent.execute(r.actionId, false);
        String q = agent.handle("查今天餐饮支出").text;
        assertTrue(q.contains("15"));
        assertTrue(q.contains("共 1 笔"));
        String check = agent.handle("今天对账，合计18").text;
        assertTrue(check.contains("差额 ¥0.00"));
        assertEquals(2, dao.listAll().size());
    }

    @Test
    public void durableDraftAndBudget() throws Exception {
        AgentEngine.Reply r = agent.handle("预算设为1234");
        assertEquals("pending", new AgentEngine(ctx).actionState(r.actionId));
        double old = Prefs.budget(ctx);
        new AgentEngine(ctx).execute(r.actionId, false);
        assertEquals(1234, Prefs.budget(ctx), 0);
        agent.execute(r.actionId, true);
        assertEquals(old, Prefs.budget(ctx), 0);
        assertFalse(agent.history().isEmpty());
    }

    @Test
    public void clearCancelsDraftsAndPreservesTombstone() throws Exception {
        PaymentStore p = new PaymentStore(ctx);
        long now = System.currentTimeMillis();
        String raw = "支付成功 商户：食堂\n15元";
        p.receive("com.tencent.mm", "k", raw, now);
        AgentEngine.Reply r = agent.handle("午饭15");
        dao.clearAll();
        assertEquals("cancelled", agent.actionState(r.actionId));
        p.receive("com.tencent.mm", "k", raw, now);
        assertTrue(dao.isEmpty());
    }

    @Test
    public void replayWithChangedPostTimeStaysPending() throws Exception {
        PaymentStore p = new PaymentStore(ctx);
        long now = System.currentTimeMillis();
        String raw = "支付成功 商户：食堂\n15元";
        long id = p.receive("com.tencent.mm", "k", raw, now);
        p.undo(id);
        p.receive("com.tencent.mm", "k", raw, now + 1000);
        assertTrue(dao.isEmpty());
        assertEquals(1, p.pendingCount());
    }

    @Test
    public void strongIdPendingEnrichment() {
        PaymentStore p = new PaymentStore(ctx);
        long now = System.currentTimeMillis();
        long id = p.receive("com.tencent.mm", "k", "支付成功 商户：食堂 交易号：ABC123456", now);
        assertTrue(dao.isEmpty());
        assertEquals(
                id, p.receive("com.tencent.mm", "k", "支付成功 商户：食堂\n15元 交易号：ABC123456", now + 100));
        assertEquals(1, dao.listAll().size());
    }

    @Test
    public void plannerRejectsDateOnlyAndRecordsConsumption() throws Exception {
        assertEquals("help", AgentPlanner.local("明天15号").getString("action"));
        assertEquals("help", AgentPlanner.local("2026").getString("action"));
        assertEquals("create", AgentPlanner.local("记一笔消费15元").getString("action"));
    }

    @Test
    public void modifiedNotificationCannotBeUndone() throws Exception {
        PaymentStore p = new PaymentStore(ctx);
        long id = p.receive("com.tencent.mm", "k", "支付成功 商户：餐厅\n15元", System.currentTimeMillis());
        Bill b = dao.listAll().get(0);
        b.billDate = "2026-01-01";
        dao.update(b);
        try {
            p.undo(id);
            fail();
        } catch (IllegalStateException expected) {
        }
        assertNotNull(dao.get(b.id));
    }

    @Test
    public void clearScrubsFinancialPayload() throws Exception {
        PaymentStore p = new PaymentStore(ctx);
        p.receive(
                "com.tencent.mm", "k", "支付成功 商户：secret merchant\n15元", System.currentTimeMillis());
        agent.handle("午饭15");
        dao.clearAll();
        try (Cursor c =
                DbHelper.get(ctx)
                        .getReadableDatabase()
                        .rawQuery("SELECT raw,merchant,snapshot FROM payment_event", null)) {
            assertTrue(c.moveToFirst());
            assertEquals("", c.getString(0));
            assertEquals("", c.getString(1));
            assertEquals("", c.getString(2));
        }
        try (Cursor c =
                DbHelper.get(ctx)
                        .getReadableDatabase()
                        .rawQuery("SELECT before_json,after_json FROM agent_action", null)) {
            assertTrue(c.moveToFirst());
            assertEquals("[]", c.getString(0));
            assertEquals("[]", c.getString(1));
        }
    }

    @Test
    public void dateQueriesAndEdits() throws Exception {
        assertEquals("2026-10-01", AgentPlanner.range("查2026年10月支出")[0]);
        AgentEngine.Reply create = agent.handle("2026-10-02午饭15元");
        agent.execute(create.actionId, false);
        Bill b = dao.listAll().get(0);
        assertEquals("2026-10-02", b.billDate);
        assertTrue(agent.handle("查 #" + b.id).text.contains("共 1 笔"));
        AgentEngine.Reply update = agent.handle("把 #" + b.id + " 日期改到2026-10-03");
        agent.execute(update.actionId, false);
        assertEquals("2026-10-03", dao.get(b.id).billDate);
    }

    @Test
    public void validation() {
        Bill b = PaymentStore.bill(Double.NaN, 0, "food", "test", System.currentTimeMillis());
        try {
            LedgerCodec.validate(b);
            fail();
        } catch (IllegalArgumentException expected) {
        }
        b.amount = 15;
        b.billDate = "2026-02-30";
        try {
            LedgerCodec.validate(b);
            fail();
        } catch (IllegalArgumentException expected) {
        }
    }
}
