package com.dsb.xp;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 右上角悬浮面板 + 抢单循环。业务逻辑与旧版一致：
 * 读宿主已登录的 AccountInfo -> 拉楼栋 -> 按楼栋+楼层轮询 -> 命中就 scramble_order。
 * 不调用 /login，token 过期时刷新并写回宿主，所以不会顶号。
 */
final class GrabPanel {
    /** 面板第一次挂上去时弹一次提示，方便确认模块真的进了宿主进程。不需要可改成 false。 */
    private static final boolean HELLO_TOAST = true;

    private static GrabPanel inst;

    static synchronized GrabPanel get() {
        if (inst == null) inst = new GrabPanel();
        return inst;
    }

    private static final class Dorm {
        final int id;
        final String name;
        final int attr;

        Dorm(int id, String name, int attr) {
            this.id = id;
            this.name = name;
            this.attr = attr;
        }
    }

    // ---- 状态 ----
    private volatile boolean running;
    private final Set<Integer> selDorms = new CopyOnWriteArraySet<Integer>();
    private final Map<Integer, String> dormNames = new ConcurrentHashMap<Integer, String>();
    private final List<Dorm> dormList = new ArrayList<Dorm>(); // 仅 UI 线程访问
    private final Set<Integer> seen = new HashSet<Integer>();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Http http = new Http();
    private final SimpleDateFormat ts = new SimpleDateFormat("HH:mm:ss", Locale.US);
    private final StringBuilder logBuf = new StringBuilder();

    private final AtomicLong nPoll = new AtomicLong();
    private final AtomicLong nHit = new AtomicLong();
    private final AtomicLong nFire = new AtomicLong();
    private final AtomicLong nOk = new AtomicLong();
    private final AtomicLong nFail = new AtomicLong();

    private volatile Activity act;
    private LinearLayout root;
    private LinearLayout body;
    private LinearLayout dormBox;
    private TextView tvStatus;
    private TextView tvLog;
    private volatile EditText etFloors;
    private volatile EditText etInterval;
    private Button btnDorms;
    private Button btnStart;
    private Button btnStop;
    private Thread worker;

    private String lastFloors = "1-6";
    private String lastInterval = "800";
    private String lastStatus = "未登录 / 读取宿主凭据中…";
    private boolean helloShown;

    private GrabPanel() {}

    // ------------------------------------------------------------------ 挂载

    /** 在宿主 Activity 上挂面板；同一个 Activity 已经挂过就什么都不做。 */
    void attach(final Activity a) {
        if (a == null) return;
        if (!a.getClass().getName().contains("disubang")) return;
        if (act == a && root != null && root.getParent() != null) return;

        saveUiState(); // 换页面时保留输入框和勾选
        act = a;
        ui.post(new Runnable() {
            @Override
            public void run() {
                try {
                    build(a);
                    FrameLayout.LayoutParams lp =
                            new FrameLayout.LayoutParams(dp(a, 250f), ViewGroup.LayoutParams.WRAP_CONTENT);
                    lp.gravity = 0x800035; // END | TOP
                    lp.topMargin = dp(a, 48f);
                    lp.rightMargin = dp(a, 6f);
                    a.addContentView(root, lp);
                    loadToken();
                    if (HELLO_TOAST && !helloShown) {
                        helloShown = true;
                        Toast.makeText(a, "DSB 抢单面板已加载", Toast.LENGTH_SHORT).show();
                    }
                } catch (Throwable t) {
                    HookEntry.log("attach fail: " + t);
                }
            }
        });
    }

    private void saveUiState() {
        try {
            if (etFloors != null) lastFloors = etFloors.getText().toString();
            if (etInterval != null) lastInterval = etInterval.getText().toString();
            if (dormBox != null && !running) {
                List<Integer> c = checkedDorms();
                selDorms.clear();
                selDorms.addAll(c);
            }
        } catch (Throwable ignored) {
        }
    }

    private static int dp(Activity a, float v) {
        return (int) (a.getResources().getDisplayMetrics().density * v + 0.5f);
    }

    private ClassLoader cl() {
        Activity a = act;
        return a == null ? getClass().getClassLoader() : a.getClassLoader();
    }

    // ------------------------------------------------------------------ 界面

    private Button miniBtn(Activity a, String text) {
        Button b = new Button(a);
        b.setText(text);
        b.setTextSize(11f);
        b.setAllCaps(false);
        b.setPadding(0, 0, 0, 0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        return b;
    }

    private LinearLayout.LayoutParams weightParams(Activity a) {
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = dp(a, 4f);
        return lp;
    }

    private void build(final Activity a) {
        root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#EE101418"));
        bg.setCornerRadius(dp(a, 10f));
        bg.setStroke(1, Color.parseColor("#44FFFFFF"));
        root.setBackground(bg);
        root.setPadding(dp(a, 8f), dp(a, 6f), dp(a, 8f), dp(a, 8f));

        // 标题栏：可拖动，右侧按钮折叠/展开
        LinearLayout header = new LinearLayout(a);
        header.setOrientation(LinearLayout.HORIZONTAL);
        TextView title = new TextView(a);
        title.setText("DSB抢单");
        title.setTextColor(Color.WHITE);
        title.setTextSize(13f);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final TextView tvFold = new TextView(a);
        tvFold.setText("▁");
        tvFold.setTextColor(Color.parseColor("#88FFFFFF"));
        tvFold.setTextSize(13f);
        tvFold.setPadding(dp(a, 8f), 0, dp(a, 4f), 0);
        header.addView(tvFold);
        root.addView(header);

        body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
        root.addView(body);

        tvFold.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (body.getVisibility() == View.VISIBLE) {
                    body.setVisibility(View.GONE);
                    tvFold.setText("▔");
                } else {
                    body.setVisibility(View.VISIBLE);
                    tvFold.setText("▁");
                }
            }
        });

        tvStatus = new TextView(a);
        tvStatus.setTextColor(Color.parseColor("#FFCC66"));
        tvStatus.setTextSize(11f);
        tvStatus.setText(lastStatus);
        body.addView(tvStatus);

        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        btnDorms = miniBtn(a, "读楼栋");
        btnStart = miniBtn(a, "开始");
        btnStop = miniBtn(a, "停止");
        row.addView(btnDorms, weightParams(a));
        row.addView(btnStart, weightParams(a));
        row.addView(btnStop, weightParams(a));
        body.addView(row);

        ScrollView dormScroll = new ScrollView(a);
        dormBox = new LinearLayout(a);
        dormBox.setOrientation(LinearLayout.VERTICAL);
        dormScroll.addView(dormBox);
        body.addView(dormScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(a, 96f)));

        etFloors = new EditText(a);
        etFloors.setHint("楼层 1-3,6");
        etFloors.setTextSize(12f);
        etFloors.setTextColor(Color.WHITE);
        etFloors.setHintTextColor(Color.parseColor("#66FFFFFF"));
        etFloors.setSingleLine(true);
        etFloors.setText(lastFloors);
        body.addView(etFloors);

        etInterval = new EditText(a);
        etInterval.setHint("间隔ms");
        etInterval.setTextSize(12f);
        etInterval.setTextColor(Color.WHITE);
        etInterval.setHintTextColor(Color.parseColor("#66FFFFFF"));
        etInterval.setSingleLine(true);
        etInterval.setInputType(2); // TYPE_CLASS_NUMBER
        etInterval.setText(lastInterval);
        body.addView(etInterval);

        ScrollView logScroll = new ScrollView(a);
        tvLog = new TextView(a);
        tvLog.setTextSize(10f);
        tvLog.setTextColor(Color.parseColor("#CCCCCC"));
        tvLog.setPadding(0, dp(a, 4f), 0, 0);
        tvLog.setText(logBuf.toString());
        logScroll.addView(tvLog);
        body.addView(logScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(a, 110f)));

        btnDorms.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doDorms(a);
            }
        });
        btnStart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doStart(a);
            }
        });
        btnStop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doStop();
            }
        });

        // 拖动：绑在标题栏和标题文字上
        View.OnTouchListener drag = new View.OnTouchListener() {
            private int l;
            private int t;
            private float dx;
            private float dy;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                ViewGroup.LayoutParams raw = root.getLayoutParams();
                if (!(raw instanceof FrameLayout.LayoutParams)) return false;
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) raw;
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        dx = e.getRawX();
                        dy = e.getRawY();
                        l = root.getLeft();
                        t = root.getTop();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        lp.leftMargin = l + (int) (e.getRawX() - dx);
                        lp.topMargin = t + (int) (e.getRawY() - dy);
                        lp.gravity = 51; // TOP | LEFT
                        root.setLayoutParams(lp);
                        return true;
                    default:
                        return false;
                }
            }
        };
        header.setOnTouchListener(drag);
        title.setOnTouchListener(drag);

        btnStart.setEnabled(!running);
        btnStop.setEnabled(running);
        fillDormBox(a);
    }

    private void fillDormBox(Activity a) {
        if (dormBox == null) return;
        dormBox.removeAllViews();
        for (Dorm d : dormList) {
            CheckBox cb = new CheckBox(a);
            String sex = d.attr == 1 ? " 男" : (d.attr == 2 ? " 女" : "");
            cb.setText(d.name + " (" + d.id + sex + ")");
            cb.setTextSize(13f);
            cb.setTextColor(Color.WHITE);
            cb.setTag(Integer.valueOf(d.id));
            if (selDorms.contains(d.id)) cb.setChecked(true);
            dormBox.addView(cb);
        }
    }

    private List<Integer> checkedDorms() {
        List<Integer> out = new ArrayList<Integer>();
        for (int i = 0; i < dormBox.getChildCount(); i++) {
            View v = dormBox.getChildAt(i);
            if (v instanceof CheckBox && ((CheckBox) v).isChecked()) {
                out.add((Integer) v.getTag());
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ 日志 / 状态 / 提示

    private void setStatus(final String s) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                lastStatus = s;
                if (tvStatus != null) tvStatus.setText(s);
            }
        });
    }

    private void log(final String s) {
        HookEntry.log(s);
        ui.post(new Runnable() {
            @Override
            public void run() {
                logBuf.append(ts.format(new Date())).append(' ').append(s).append('\n');
                if (logBuf.length() > 8000) logBuf.delete(0, logBuf.length() - 4000);
                if (tvLog != null) tvLog.setText(logBuf.toString());
            }
        });
    }

    private void toast(final String s) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                try {
                    Activity a = act;
                    if (a != null) Toast.makeText(a, s, Toast.LENGTH_SHORT).show();
                } catch (Throwable ignored) {
                }
            }
        });
    }

    // ------------------------------------------------------------------ 宿主登录态

    private Object prefs() throws Exception {
        Class<?> c = Refl.findClass("com.disubang.rider.mode.utils.PreferencesHelper", cl());
        return Refl.callStatic(c, "getInstance");
    }

    /** 读宿主保存的 AccountInfo / UserInfo，成功返回 true。 */
    private boolean loadToken() {
        final String notLogin = "宿主未登录，先在 App 里登录";
        try {
            Object prefs = prefs();
            Object acc = Refl.call(prefs, "getAccountInfo");
            if (acc == null) {
                setStatus(notLogin);
                return false;
            }
            String tk = (String) Refl.call(acc, "getAccess_token");
            if (tk == null || tk.length() == 0) {
                setStatus(notLogin);
                return false;
            }
            http.token = tk;
            http.tokenType = String.valueOf(Refl.call(acc, "getToken_type"));
            http.refreshToken = String.valueOf(Refl.call(acc, "getRefresh_token"));
            Object user = Refl.call(prefs, "getUserInfo");
            if (user != null) http.uid = String.valueOf(Refl.call(user, "getUser_id"));
            setStatus("共用宿主登录 | uid=" + http.uid + " | token " + tk.substring(0, Math.min(8, tk.length())));
            return true;
        } catch (Throwable e) {
            HookEntry.log("loadToken " + e);
            setStatus("读凭据失败: " + e);
            return false;
        }
    }

    /** token 过期：刷新，并把新 token 写回宿主，保证两边始终是同一份登录态。 */
    private boolean refreshAndSync() {
        JSONObject r = http.refresh();
        if (!"200".equals(r.optString("status"))) {
            log("刷新失败: " + r.optString("message"));
            return false;
        }
        try {
            Class<?> accCls = Refl.findClass("com.disubang.rider.mode.bean.AccountInfo", cl());
            Object ai = Refl.newInstance(accCls);
            Refl.call(ai, "setAccess_token", http.token);
            Refl.call(ai, "setToken_type", http.tokenType);
            Refl.call(ai, "setRefresh_token", http.refreshToken);
            Refl.call(ai, "setExpires_in", Long.valueOf(7200L));
            Refl.call(prefs(), "saveAccountInfo", ai);
            Class<?> hc = Refl.findClass("com.disubang.rider.presenter.net.HttpClient", cl());
            Refl.setStatic(hc, "accountInfo", ai);
            log("token 已刷新并同步回 App");
            setStatus("token 已同步 | uid=" + http.uid);
            return true;
        } catch (Throwable e) {
            log("同步失败: " + e);
            return false;
        }
    }

    // ------------------------------------------------------------------ 楼栋

    private static JSONArray asArray(Object o) {
        try {
            if (o instanceof JSONArray) return (JSONArray) o;
            if (o instanceof JSONObject) return ((JSONObject) o).optJSONArray("data");
            if (o instanceof String) {
                String s = (String) o;
                if (s.startsWith("[")) return new JSONArray(s);
                return new JSONObject(s).optJSONArray("data");
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void doDorms(final Activity a) {
        String tk = http.token;
        if (tk == null || tk.length() == 0) {
            if (!loadToken()) {
                toast("先登录 App");
                return;
            }
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                final JSONObject j = http.dorms();
                final JSONArray arr = asArray(j.opt("data"));
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        populateDorms(a, arr, j);
                    }
                });
            }
        }).start();
    }

    private void populateDorms(Activity a, JSONArray arr, JSONObject j) {
        dormList.clear();
        dormNames.clear();
        if (arr == null || arr.length() == 0) {
            fillDormBox(a);
            setStatus("楼栋为空: " + j.optString("message"));
            return;
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            int id = o.optInt("delivery_id", -1);
            String name = o.optString("build_name", "");
            int attr = o.optInt("attr_type", 0);
            if (id < 0) continue;
            dormNames.put(id, name);
            dormList.add(new Dorm(id, name, attr));
        }
        fillDormBox(a);
        setStatus("楼栋 " + arr.length() + " 个");
    }

    // ------------------------------------------------------------------ 开始 / 停止

    private String floorsText() {
        EditText e = etFloors;
        return e == null ? lastFloors.trim() : e.getText().toString().trim();
    }

    private int intervalMs() {
        try {
            int v = Integer.parseInt(etInterval.getText().toString().trim());
            return Math.max(200, Math.min(10000, v));
        } catch (Exception e) {
            return 800;
        }
    }

    private static String join(Set<Integer> s) {
        StringBuilder sb = new StringBuilder();
        for (Integer i : s) {
            if (sb.length() > 0) sb.append(',');
            sb.append(i);
        }
        return sb.toString();
    }

    private void doStart(Activity a) {
        if (running) {
            toast("已在运行");
            return;
        }
        if (!loadToken()) {
            toast("先在 App 里登录");
            return;
        }
        selDorms.clear();
        selDorms.addAll(checkedDorms());
        if (selDorms.isEmpty()) {
            toast("勾选至少一个楼栋");
            return;
        }
        running = true;
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "dsb-xp-grab");
        worker.start();
        btnStart.setEnabled(false);
        btnStop.setEnabled(true);
        log("启动 | 楼栋=" + selDorms + " 楼层=" + floorsText());
    }

    private void doStop() {
        running = false;
        Thread w = worker;
        if (w != null) {
            w.interrupt();
            worker = null;
        }
        if (btnStart != null) btnStart.setEnabled(true);
        if (btnStop != null) btnStop.setEnabled(false);
        log("停止 | 轮询" + nPoll.get() + " 命中" + nHit.get() + " 抢" + nFire.get()
                + " 成" + nOk.get() + " 败" + nFail.get());
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    private void loop() {
        Set<Integer> floors = FloorUtil.parseSet(floorsText());
        while (running) {
            long t0 = System.currentTimeMillis();
            try {
                pollOnce(floors);
            } catch (Exception e) {
                log("轮询异常 " + e);
                sleep(1500);
            }
            long left = intervalMs() - (System.currentTimeMillis() - t0);
            if (left > 0) sleep(left);
        }
    }

    // ------------------------------------------------------------------ 拉单 / 抢单

    private static String phasesOf(JSONObject o) {
        if (o.optInt("is_relay", 0) == 1 && o.optInt("ps_id", 0) != 0 && o.optInt("relay_id", 0) == 0) {
            return "two";
        }
        return "one";
    }

    private void pollOnce(Set<Integer> floors) {
        nPoll.incrementAndGet();
        JSONObject j = http.orders(join(selDorms), "0", -1, 0, 0, 1, 30);
        String st = j.optString("status");

        if ("401".equals(st)) {
            if (!refreshAndSync()) {
                running = false;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (btnStart != null) btnStart.setEnabled(true);
                        if (btnStop != null) btnStop.setEnabled(false);
                    }
                });
            }
            return;
        }
        if (!"200".equals(st)) {
            log("拉单失败 " + st + " " + j.optString("message"));
            return;
        }

        JSONArray arr = asArray(j.opt("data"));
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            final JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            final int oid = o.optInt("order_id", -1);
            if (oid < 0) continue;
            synchronized (seen) {
                if (seen.contains(oid)) continue;
            }
            int did = o.optInt("delivery_id", -1);
            if (did >= 0 && !selDorms.contains(did)) continue;

            String addr = o.optString("address");
            final Integer ff = FloorUtil.floor(addr, dormNames.get(did));
            if (!floors.isEmpty() && (ff == null || !floors.contains(ff))) continue;

            synchronized (seen) {
                seen.add(oid);
            }
            nHit.incrementAndGet();
            final String phases = phasesOf(o);
            new Thread(new Runnable() {
                @Override
                public void run() {
                    String address = o.optString("address");
                    String money = o.optString("horseman_amount", o.optString("ps_money"));
                    grab(oid, phases, ff, address, money);
                }
            }).start();
        }
    }

    private void grab(int oid, String phases, Integer floor, String address, String money) {
        nFire.incrementAndGet();
        long t0 = System.currentTimeMillis();
        JSONObject r = http.scramble(oid, phases);
        long dt = System.currentTimeMillis() - t0;
        String st = r.optString("status");
        String fl = floor == null ? "?" : (floor + "层");
        if ("200".equals(st)) {
            nOk.incrementAndGet();
            log("✅ " + oid + " ¥" + money + " " + fl + " " + dt + "ms");
        } else {
            nFail.incrementAndGet();
            log("❌ " + oid + " " + st + " " + r.optString("message"));
            synchronized (seen) {
                seen.remove(oid);
            }
        }
        if (address != null && address.length() > 26) address = address.substring(0, 26);
        log("· " + oid + " " + fl + " " + address);
    }
}
