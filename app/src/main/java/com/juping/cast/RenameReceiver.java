package com.juping.cast;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * 设备改名 —— 全项目唯一的 exported 指令面。
 *
 * <h3>为什么要走 adb 广播而不是界面输入框</h3>
 * 电视盒子大概率没有可用的输入法：弹一个 EditText，遥控器根本调不出键盘。
 * 而 adb 是这个项目已经验证过的运维通道（安装、验收都走它），名字这类
 * 「改一次用一年」的配置，走运维通道比硬造一套 TV 输入 UI 划算得多：
 * <pre>
 *   adb shell am broadcast -a com.juping.cast.APPLY_RENAME --es name "客厅盒子"
 * </pre>
 * 传空串（或不传）= 恢复默认名「聚屏-&lt;型号&gt;」。
 *
 * <h3>护栏（exported = 任何应用都能发这条广播）</h3>
 * <ul>
 *   <li>名字剔除控制字符、限长 32 —— device.xml 侧另有 escapeXml 兜底
 *       （一个裸 &amp; 就能让整份描述变非法 XML），但入口处就该把明显
 *       非法的输入拒掉；</li>
 *   <li>空名写空串而不是跳过 —— 空串在语义上就是「恢复默认」，
 *       与「没收到这条广播」是两回事；</li>
 *   <li>先落盘、再拉服务 —— 顺序反了的话，服务 onCreate 读到的还是
 *       旧名字，广播等于白发。</li>
 * </ul>
 */
public class RenameReceiver extends BroadcastReceiver {

    private static final String TAG = "RenameReceiver";

    /** 改名指令的 action。与 manifest 里的 intent-filter 必须一字不差。 */
    public static final String ACTION_APPLY_RENAME = "com.juping.cast.APPLY_RENAME";

    /** 新名字的 extra 键：{@code --es name "客厅盒子"} */
    public static final String EXTRA_NAME = "name";

    /** 键定义在写入方（这里），服务侧读取时引用这里 —— 同一语义只允许写一处 */
    static final String KEY_FRIENDLY_NAME = "friendly_name";

    /**
     * 名字长度上限（字符）。
     *
     * <p>32 的依据：控制点设备列表一屏放得下两三台设备的名字，
     * 再长就会被截成省略号；而「聚屏-客厅电视柜下面那台」这种需求
     * 32 个字符绰绰有余。防的是恶意广播塞一整段垃圾进 device.xml。
     */
    private static final int MAX_LEN = 32;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_APPLY_RENAME.equals(intent.getAction())) {
            return;
        }
        String raw = intent.getStringExtra(EXTRA_NAME);
        String name = sanitize(raw);
        Log.i(TAG, "收到改名广播：" + (raw == null ? "(未传)" : raw)
                + " → 「" + name + "」");

        // commit() 不是 apply()：同步落盘。理由与 UUID 同一条 ——
        // 紧接着拉起的服务可能立刻读这个名字，进程若在 apply 落盘前被杀，
        // 改名就悄悄丢了（ApplySharedPref 这条 lint 在本项目是刻意关的）。
        SharedPreferences sp = context.getSharedPreferences(
                DlnaRendererService.PREFS, Context.MODE_PRIVATE);
        sp.edit().putString(KEY_FRIENDLY_NAME, name).commit();

        // 服务没在跑也没关系：onCreate 会读到新名字；在跑的话，
        // onStartCommand 收到同名 action 会重建两条链路让名字立即生效。
        try {
            Intent svc = new Intent(context, DlnaRendererService.class);
            svc.setAction(ACTION_APPLY_RENAME);
            context.startService(svc);
        } catch (Exception e) {
            Log.e(TAG, "拉起服务失败（名字已存盘，下次启动生效）", e);
        }
    }

    /**
     * 清洗名字：剔除控制字符、截到上限、trim。
     *
     * <p>刻意**不做** XML 转义 —— 那是 device.xml 生成侧的职责
     * （escapeXml），两边各管一段；在这里转义反而会把「名字里本来
     * 就叫 XXX&amp;YYY」的 &amp; 弄成两层转义。null 一律归空串（恢复默认）。
     */
    static String sanitize(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (char c : raw.trim().toCharArray()) {
            if (c < 0x20) {
                continue;
            }
            if (sb.length() >= MAX_LEN) {
                break;
            }
            sb.append(c);
        }
        return sb.toString().trim();
    }
}
