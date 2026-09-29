package com.juping.cast;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 开机自启动。
 *
 * <p>收到开机广播后<b>只启动服务、不弹界面</b> —— TV 开机就该能直接投屏，
 * 不需要用户再去点一次 App。
 *
 * <h3>Android 的限制（必须知道）</h3>
 * 从 Android 3.1 起，处于「已安装但从未被启动过」状态的应用收不到
 * BOOT_COMPLETED。也就是说：<b>装完必须手动打开一次，之后开机自启才会生效。</b>
 * 这不是 bug，是系统的安全设计，绕不过去。
 *
 * <h3>为什么要监听三个 action</h3>
 * 各厂商的「快速开机」不走标准 BOOT_COMPLETED：
 * <ul>
 *   <li>{@code BOOT_COMPLETED} —— 标准开机</li>
 *   <li>{@code QUICKBOOT_POWERON} —— 多数国产盒子的快速启动</li>
 *   <li>{@code com.htc.intent.action.QUICKBOOT_POWERON} —— HTC 系</li>
 * </ul>
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }
        String action = intent.getAction();
        Log.i(TAG, "收到开机广播: " + action);

        try {
            Intent service = new Intent(context, DlnaRendererService.class);
            context.startService(service);
            Log.i(TAG, "已拉起投屏接收服务");
        } catch (Exception e) {
            Log.e(TAG, "拉起服务失败", e);
        }
    }
}
