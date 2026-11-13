package com.wool.ledger;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentTransaction;

import com.wool.ledger.data.Prefs;
import com.wool.ledger.ui.bills.BillListFragment;

/** 「查看全部」进来的账单页：复用 BillListFragment，藏掉底部导航。 */
public class BillListActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Prefs.applyTheme(this);
        setContentView(R.layout.activity_shell);
        if (savedInstanceState == null) {
            BillListFragment f = new BillListFragment();
            Bundle args = new Bundle();
            if (getIntent() != null) {
                args.putString("category", getIntent().getStringExtra("category"));
                String kw = getIntent().getStringExtra("keyword");
                if (kw != null) args.putString("keyword", kw);
            }
            f.setArguments(args);
            FragmentTransaction tx = getSupportFragmentManager().beginTransaction();
            tx.replace(R.id.shell_container, f);
            tx.commit();
        }
    }
}
