#!/usr/bin/env python3
"""核验 release APK 的 dex 里，**框架要靠名字回调的那些入口点**是否都还在。

为什么必须有这道闸门
--------------------
开了 R8 之后，包里的类名、方法名会被改成 a、b、c。这对**编译期就能确定**的引用
完全无害 —— 改得一致就行。但有一类名字**改不得**：

    框架是通过接口名回调我们的。Activity 的生命周期、SurfaceHolder.Callback、
    View.OnClickListener、MediaPlayer 的各种 Listener、Runnable.run……
    这些方法名是系统那边写死的，改了就等于没实现。

R8 知道这件事（它会保留「覆盖了库方法」的名字），所以正常情况下不用管。
但"正常情况下"这四个字不能当判据 —— 一旦规则文件被改、AGP 升级换了默认规则、
或者有人加了 -keep 之外的干扰项，症状会是：

    APK 装得上、点开就崩，或者画面永远不出来，
    而 logcat 里只有一句 NoSuchMethodError / AbstractMethodError。

更麻烦的是**现有几道闸门都看不见它**：
  · 编译期看不到（源码里名字是对的）
  · lint 看不到
  · check_api_compat.py 看不到（它查的是"平台成员存不存在"，不是"我们的名字对不对"）
  · 协议层 / 策略层测试更看不到 —— 它们编译的是**源码**，不是 dex

所以只能直接反汇编 dex 来核。这就是这个脚本存在的全部理由。

用法：
    python3 tools/check_dex_entrypoints.py <apk>
    python3 tools/check_dex_entrypoints.py dist/juping-<版本号>-release.apk

退出码：0 = 全过；1 = 有入口点丢失（这个包不能发）。

原理：
  1. dexdump -d 反汇编 APK 里的每个 classes*.dex
  2. 建出「类 → 父类 / 实现接口 / 方法名集合」
  3. 按下面的表格逐个核：
     · manifest 组件（Activity / Service / BroadcastReceiver）在不在、父类对不对、
       生命周期回调名有没有被改
     · 凡是实现了**框架接口**的类，接口要求的方法名必须原样在
     · 源码里 `extends Thread` 的类，dex 里也要有同样多的 Thread 子类
       （R8 的纵向合并若把某个 Thread 子类并掉，它的 run() 就再也不会被调用）
  4. 另外核几个**协议常量字符串**：它们一旦在 dex 里找不到，说明这个包
     根本不是当前源码编出来的（陈旧产物），后面所有核验都白做

没开混淆（minifyEnabled false）时这个脚本同样跑得通 —— 那时名字本来就是原样，
全过是应该的。**它不是为了"混淆才需要"，是为了"名字被改过"才需要。**
"""
import os
import re
import subprocess
import sys
import zipfile

# 框架接口 → 它要求我们实现的方法名。
# 只列本项目真正用到的那些；下面会**报告**有没有实现别的框架接口，
# 免得新增一个实现却静默地不在核查范围内。
FRAMEWORK_INTERFACES = {
    'Ljava/lang/Runnable;': ['run'],
    # 内嵌的 Nayuki QR 库：BitBuffer implements Cloneable 并覆写了 clone()。
    # 列进来是为了让「名字被改过」这件事照样被核到 —— 不列的话它只会被
    # 当成「不在核查表内」报出来，闸门照样红。
    'Ljava/lang/Cloneable;': ['clone'],
    'Ljava/util/concurrent/ThreadFactory;': ['newThread'],
    'Landroid/view/SurfaceHolder$Callback;': ['surfaceCreated', 'surfaceChanged',
                                              'surfaceDestroyed'],
    'Landroid/view/View$OnClickListener;': ['onClick'],
    'Landroid/content/ServiceConnection;': ['onServiceConnected', 'onServiceDisconnected'],
    'Landroid/media/MediaPlayer$OnPreparedListener;': ['onPrepared'],
    'Landroid/media/MediaPlayer$OnCompletionListener;': ['onCompletion'],
    'Landroid/media/MediaPlayer$OnErrorListener;': ['onError'],
    'Landroid/media/MediaPlayer$OnSeekCompleteListener;': ['onSeekComplete'],
    'Landroid/media/MediaPlayer$OnBufferingUpdateListener;': ['onBufferingUpdate'],
    'Landroid/media/MediaPlayer$OnVideoSizeChangedListener;': ['onVideoSizeChanged'],
}

# manifest 里声明的组件：类名 → (父类, 必须保留的方法名)
MANIFEST_COMPONENTS = {
    'Lcom/juping/cast/MainActivity;': (
        'Landroid/app/Activity;',
        ['onCreate', 'onDestroy', 'onPause', 'onResume']),
    'Lcom/juping/cast/DlnaRendererService;': (
        'Landroid/app/Service;',
        ['onCreate', 'onStartCommand', 'onBind', 'onDestroy']),
    'Lcom/juping/cast/BootReceiver;': (
        'Landroid/content/BroadcastReceiver;',
        ['onReceive']),
}

# 协议常量。这些字符串是 UPnP / HTTP 规范的一部分，永远不会因为改文案而变 ——
# 所以它们既适合当"入口点没被改坏"的证据，也适合当"这个 dex 是当前源码编的"的证据。
PROTOCOL_LITERALS = [
    'urn:schemas-upnp-org:device:MediaRenderer:1',
    'urn:schemas-upnp-org:service:AVTransport:1',
    'ERROR_OCCURRED',
    'CurrentTransportStatus',
    'RelativeTimePosition',
    '413 Request Entity Too Large',
]

RE_CLASS = re.compile(r"^\s{2}Class descriptor\s+:\s+'(.+)'")
RE_SUPER = re.compile(r"^\s{2}Superclass\s+:\s+'(.+)'")
RE_IFACE = re.compile(r"^\s{4}#\d+\s+:\s+'(L[^']+)'")
RE_METHOD = re.compile(r"^\s{6}name\s+:\s+'(.+)'")
RE_SOURCE_EXTENDS_THREAD = re.compile(r'\bextends\s+Thread\b')


def find_dexdump():
    bt = os.environ.get('ANDROID_BUILD_TOOLS')
    if bt and os.path.exists(os.path.join(bt, 'dexdump')):
        return os.path.join(bt, 'dexdump')
    home = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    if home:
        d = os.path.join(home, 'build-tools')
        if os.path.isdir(d):
            for v in sorted(os.listdir(d), reverse=True):
                p = os.path.join(d, v, 'dexdump')
                if os.path.exists(p):
                    return p
    return None


def parse_dex(path, dexdump):
    out = subprocess.run([dexdump, '-d', path], capture_output=True, text=True,
                         errors='replace').stdout
    sup, ifaces, methods, order = {}, {}, {}, []
    cur = None
    for line in out.splitlines():
        m = RE_CLASS.match(line)
        if m:
            cur = m.group(1)
            order.append(cur)
            sup[cur] = None
            ifaces[cur] = []
            methods[cur] = []
            continue
        if cur is None:
            continue
        m = RE_SUPER.match(line)
        if m:
            sup[cur] = m.group(1)
            continue
        m = RE_IFACE.match(line)
        if m:
            ifaces[cur].append(m.group(1))
            continue
        m = RE_METHOD.match(line)
        if m:
            methods[cur].append(m.group(1))
    return sup, ifaces, methods, order


def main():
    if len(sys.argv) < 2:
        print(__doc__.strip().split('用法：')[1].strip().splitlines()[0], file=sys.stderr)
        return 2
    apk = sys.argv[1]
    if not os.path.isfile(apk):
        print('找不到 APK: %s' % apk, file=sys.stderr)
        return 2

    dexdump = find_dexdump()
    if not dexdump:
        print('  dex入口: 跳过（找不到 dexdump —— 设 ANDROID_HOME 或 ANDROID_BUILD_TOOLS）')
        return 0

    tmp = os.path.join(os.path.dirname(os.path.abspath(apk)) or '.', '.dexcheck')
    os.makedirs(tmp, exist_ok=True)
    sup, ifaces, methods = {}, {}, {}
    names, blobs = [], []
    with zipfile.ZipFile(apk) as z:
        dex_names = [n for n in z.namelist() if re.match(r'classes\d*\.dex$', n)]
        for n in sorted(dex_names):
            p = os.path.join(tmp, os.path.basename(n))
            with open(p, 'wb') as f:
                f.write(z.read(n))
            s, i, m, order = parse_dex(p, dexdump)
            sup.update(s)
            ifaces.update(i)
            methods.update(m)
            names.extend(order)
            with open(p, 'rb') as f:
                blobs.append(f.read())
            os.remove(p)
    try:
        os.rmdir(tmp)
    except OSError:
        pass

    raw = b''.join(blobs)
    failed = []

    def rep(ok, name, detail='', note=''):
        """detail 只在**失败**时打（它是诊断话术）；note 只在通过时打（是简短说明）。

        两者分开写，是因为诊断话术读起来是"这里坏了会怎样"——
        全过的时候也打出来，满屏都是"少了 xxx 就等于没实现"，看着像全红。
        """
        line = '  [%s] %s' % ('PASS' if ok else 'FAIL', name)
        extra = note if ok else detail
        if extra:
            line += '\n         ' + extra
        print(line)
        if not ok:
            failed.append(name)

    print('  （反汇编 %d 个类，来自 %d 个 dex）' % (len(names), len(blobs)))

    # ---- ① manifest 组件与生命周期回调 ----
    for cls, (want_super, want_methods) in sorted(MANIFEST_COMPONENTS.items()):
        short = cls.split('/')[-1].rstrip(';')
        if cls not in methods:
            rep(False, '%s 在 dex 里' % short,
                '类整个不见了 —— manifest 里还声明着它，装上就会崩')
            continue
        rep(True, '%s 在 dex 里' % short)
        rep(sup.get(cls) == want_super, '%s 仍继承 %s' % (short, want_super.split('/')[-1].rstrip(';')),
            '实际父类是 %s。R8 不该动库继承关系 —— 动了说明规则被改坏了'
            % (sup.get(cls) or '(无)'),
            '父类 %s' % (sup.get(cls) or '(无)'))
        have = set(methods[cls])
        miss = [n for n in want_methods if n not in have]
        rep(not miss, '%s 的生命周期回调名没被改' % short,
            '少了 %s。这些名字是系统写死的，改了就等于没实现 ——'
            '症状是"装得上、点开就崩"，而 logcat 里只有 NoSuchMethodError'
            % ', '.join(miss),
            ', '.join(want_methods))

    # ---- ② 实现框架接口的类：接口要求的方法名必须在 ----
    checked = 0
    unknown = []
    for cls in sorted(ifaces):
        for itf in ifaces[cls]:
            if not itf.startswith('Landroid/') and not itf.startswith('Ljava/'):
                continue
            want = FRAMEWORK_INTERFACES.get(itf)
            if want is None:
                unknown.append((cls, itf))
                continue
            checked += 1
            miss = [n for n in want if n not in set(methods[cls])]
            rep(not miss, '%s 实现了 %s，方法名保留'
                % (cls.split('/')[-1], itf.split('/')[-1].rstrip(';')),
                '少了 %s' % ', '.join(miss),
                ', '.join(want))
    if unknown:
        # 不静默跳过：新加一个框架接口实现却没进核查表，等于开了个后门。
        rep(False, '所有框架接口实现都在核查表内',
            '下面这些不在 FRAMEWORK_INTERFACES 里，没被核到：\n         '
            + '\n         '.join('%s → %s' % (c, i) for c, i in unknown))
    else:
        rep(True, '所有框架接口实现都在核查表内（共核了 %d 个）' % checked)

    # ---- ③ Thread 子类不能少 ----
    # R8 的纵向合并若把某个 Thread 子类并进别的类，它的 run() 就再也不会被调用 ——
    # 而这条链路（SSDP 接收循环、HTTP accept 循环、事件重播）静默失效的后果
    # 恰恰是「手机搜不到设备」「投了没反应」。
    src_root = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                            'app', 'src', 'main', 'java')
    src_count = 0
    for dirpath, _, files in os.walk(src_root):
        for fn in files:
            if fn.endswith('.java'):
                with open(os.path.join(dirpath, fn), encoding='utf-8') as f:
                    src_count += len(RE_SOURCE_EXTENDS_THREAD.findall(f.read()))
    dex_count = sum(1 for c in sup if sup.get(c) == 'Ljava/lang/Thread;')
    rep(dex_count >= src_count, 'Thread 子类没有在合并中丢失（源码 %d 个 / dex %d 个）'
        % (src_count, dex_count),
        '源码里有 %d 个 `extends Thread`，dex 里只剩 %d 个。'
        '少掉的那个 run() 永远不会被调用 —— 而它很可能就是 SSDP 接收循环'
        '或 HTTP accept 循环' % (src_count, dex_count),
        '两边的数量一致，接收循环都还在')

    # ---- ④ 协议常量：证明这个 dex 就是当前源码编的 ----
    for lit in PROTOCOL_LITERALS:
        rep(lit.encode('utf-8') in raw, '协议常量还在：%s' % lit,
            'dex 里找不到这个字符串。要么代码被改坏了，要么这个 APK 是陈旧产物 ——'
            '两种情况后面所有核验都不可信',
            '在')

    print()
    if failed:
        print('  !! dex 入口点核查未通过（%d 项）—— 这个包不能发：' % len(failed))
        for n in failed:
            print('       · %s' % n)
        return 1
    print('  结论：框架回调、Thread 子类、协议常量全部完好，R8 输出可用。')
    return 0


if __name__ == '__main__':
    sys.exit(main())
