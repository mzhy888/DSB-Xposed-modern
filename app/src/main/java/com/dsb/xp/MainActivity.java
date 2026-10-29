package com.dsb.xp;

import android.app.Activity;
import android.os.Bundle;
import android.widget.ScrollView;
import android.widget.TextView;

/** 模块自己的说明页（桌面图标点开看到的）。真正的功能在迪速帮里。 */
public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView sv = new ScrollView(this);
        TextView tv = new TextView(this);
        tv.setText("DSB 悬浮抢单模块（LSPosed，新 API）\n\n"
                + "1. 在 LSPosed 里启用本模块，作用域只勾选「迪速帮配送版」。\n"
                + "2. 强制停止迪速帮，再重新打开。\n"
                + "3. 正常登录 App 后进到主页，右上角会出现「DSB抢单」小面板，并弹出一次「DSB 抢单面板已加载」。\n"
                + "4. 点「读楼栋」→ 勾选楼栋 → 填楼层（如 1-3,6）→ 开始。\n\n"
                + "为什么不会顶号：\n"
                + "本模块不调用 /login，直接读 App 已经存下的 AccountInfo，所有请求共用同一个 access_token；"
                + "token 过期时刷新后会同时写回 SharedPreferences 和 HttpClient.accountInfo，"
                + "所以宿主 App 与本面板始终是同一份登录态。\n\n"
                + "排错：\n"
                + "- 面板不出现：确认 LSPosed 里模块已启用、作用域已勾选，并强制停止过迪速帮。\n"
                + "- 提示未登录：先在 App 里登录一次。\n"
                + "- 拉单为空：确认 App 里接单开关已打开、已绑区域。\n"
                + "- 日志：LSPosed「日志」→「模块日志」，或 logcat 搜 DSB。");
        tv.setTextSize(14f);
        tv.setLineSpacing(6f, 1f);
        tv.setPadding(48, 48, 48, 48);
        sv.addView(tv);
        setContentView(sv);
    }
}
