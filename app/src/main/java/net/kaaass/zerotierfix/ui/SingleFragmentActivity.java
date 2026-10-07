package net.kaaass.zerotierfix.ui;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import net.kaaass.zerotierfix.R;

/**
 * 单片段 Activity
 */
public abstract class SingleFragmentActivity extends AppCompatActivity {
    private static final String TAG = "SingleFragmentActivity";

    public abstract Fragment createFragment();

    @Override
    public void onCreate(Bundle bundle) {
        super.onCreate(bundle);

        // Android 15 (targetSdk 35) 起强制 edge-to-edge，不再依赖已废弃的
        // windowOptOutEdgeToEdgeEnforcement。全部界面都经过这个基类，
        // 所以只需在这里把根容器垫到系统栏之外。
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        setContentView(R.layout.activity_fragment);

        View root = findViewById(R.id.fragmentContainer);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            // 四个方向都要垫。如果 AppCompat 已经为 ActionBar 垫过状态栏，
            // 子视图收到的 top 会是 0，这里不会重复垫。
            v.setPadding(insets.left, insets.top, insets.right, insets.bottom);
            // 不返回 CONSUMED：项目里没有任何布局使用 fitsSystemWindows，
            // 消费掉反而会让输入法(ime) inset 无法继续分发。
            return windowInsets;
        });

        FragmentManager supportFragmentManager = getSupportFragmentManager();
        Fragment findFragmentById = supportFragmentManager.findFragmentById(R.id.fragmentContainer);
        if (findFragmentById == null) {
            Fragment createFragment = createFragment();
            setArgs(createFragment);
            supportFragmentManager.beginTransaction().add(R.id.fragmentContainer, createFragment).commit();
            return;
        }
        setArgs(findFragmentById);
    }

    private void setArgs(Fragment fragment) {
        Bundle extras;
        Intent intent = getIntent();
        if (intent != null && (extras = intent.getExtras()) != null && !fragment.isAdded()) {
            try {
                fragment.setArguments(extras);
            } catch (IllegalArgumentException unused) {
                Log.e(TAG, "Exception setting arguments on fragment");
            }
        }
    }
}
