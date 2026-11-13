package com.wool.ledger;

import static org.junit.Assert.*;

import android.app.*;
import android.content.*;
import android.widget.*;

import androidx.recyclerview.widget.RecyclerView;
import androidx.test.platform.app.InstrumentationRegistry;

import com.wool.ledger.agent.*;
import com.wool.ledger.automation.*;
import com.wool.ledger.data.*;
import com.wool.ledger.ui.chat.*;

import org.junit.*;

public class AgentUiTest {
    Instrumentation inst;
    Context ctx;
    Activity activity;

    @Before
    public void setup() {
        inst = InstrumentationRegistry.getInstrumentation();
        ctx = inst.getTargetContext();
        new BillDao(ctx).clearAll();
        Prefs.setAgentOnline(ctx, false);
        Prefs.setDark(ctx, false);
    }

    @After
    public void close() {
        if (activity != null) inst.runOnMainSync(() -> activity.finish());
    }

    private void await(java.util.concurrent.Callable<Boolean> condition) throws Exception {
        long end = System.currentTimeMillis() + 10000;
        while (System.currentTimeMillis() < end) {
            inst.waitForIdleSync();
            if (condition.call()) return;
            Thread.sleep(50);
        }
        fail("Timed out waiting for UI operation");
    }

    @Test
    public void chatRecordsOnlyAfterCardConfirmationAndRestores() throws Exception {
        activity =
                inst.startActivitySync(
                        new Intent(ctx, ChatActivity.class)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        RecyclerView list = activity.findViewById(R.id.chat_log);
        await(() -> list.getAdapter() != null && list.getAdapter().getItemCount() > 0);
        inst.runOnMainSync(
                () -> {
                    ((EditText) activity.findViewById(R.id.chat_input)).setText("午饭15");
                    activity.findViewById(R.id.chat_send).performClick();
                });
        ChatAdapter a = (ChatAdapter) list.getAdapter();
        await(() -> a.data().stream().anyMatch(m -> m.card != null));
        assertTrue(new BillDao(ctx).isEmpty());
        final ChatAdapter.Msg[] card = new ChatAdapter.Msg[1];
        inst.runOnMainSync(
                () -> {
                    for (ChatAdapter.Msg m : a.data()) if (m.card != null) card[0] = m;
                    list.scrollToPosition(a.getItemCount() - 1);
                });
        long id = card[0].card.actionId;
        await(() -> list.findViewHolderForAdapterPosition(a.getItemCount() - 1) != null);
        inst.runOnMainSync(
                () ->
                        list.findViewHolderForAdapterPosition(a.getItemCount() - 1)
                                .itemView
                                .findViewById(R.id.chat_ap_ok)
                                .performClick());
        await(() -> new AgentEngine(ctx).actionState(id).equals("applied"));
        assertEquals(1, new BillDao(ctx).listAll().size());
        inst.runOnMainSync(() -> activity.finish());
        activity =
                inst.startActivitySync(
                        new Intent(ctx, ChatActivity.class)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        RecyclerView restored = activity.findViewById(R.id.chat_log);
        await(() -> restored.getAdapter().getItemCount() >= 3);
        assertEquals("applied", new AgentEngine(ctx).actionState(id));
    }

    @Test
    public void inboxAndAgentOpenInDarkTheme() {
        Prefs.setDark(ctx, true);
        activity =
                inst.startActivitySync(
                        new Intent(ctx, PaymentInboxActivity.class)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        assertNotNull(activity.findViewById(android.R.id.content));
        inst.runOnMainSync(() -> activity.finish());
        activity =
                inst.startActivitySync(
                        new Intent(ctx, ChatActivity.class)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        assertNotNull(activity.findViewById(R.id.chat_send));
    }
}
