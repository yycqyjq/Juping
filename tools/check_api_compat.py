#!/usr/bin/env python3
"""对着目标平台的 android.jar，逐个核验 APK 里引用的每个平台 API 是否真的存在。

为什么需要这个 —— lint 的 NewApi 已经能查 API 越界，但它依赖内置数据库，
而且项目里通常还关掉了一批检查。一旦某项被关掉时顺带掩盖了 API 问题，
lint 不会吭声。这个脚本是**独立于 lint 的第二道判据**：

    代码引用的每个平台成员，在目标版本的 android.jar 里到底存不存在。

不存在的，真机上就是 NoSuchMethodError / NoSuchFieldError 崩溃 ——
而编译期（对着新版 android.jar）完全不会报错。这是老设备 APK 的头号杀手。

用法：
    python3 tools/check_api_compat.py <apk> [基线]

    <基线> 可以是三种形式：
      · 省略          —— 从 APK 的 minSdkVersion 自动推出 API level，
                        去 $ANDROID_HOME/platforms/android-<N>/android.jar 找
      · 纯数字 15     —— 同上，但显式指定 API level
      · 一个 .jar 路径 —— 直接用这个 jar

    # 例：
    python3 tools/check_api_compat.py dist/juping-<版本号>-release.apk
    python3 tools/check_api_compat.py dist/juping-<版本号>-release.apk 15
    python3 tools/check_api_compat.py dist/juping-<版本号>-release.apk \\
        /path/to/android-4.0.4/android.jar

需要 JAVA_HOME 指向一个带 javap 的 JDK，以及 ANDROID_HOME / ANDROID_BUILD_TOOLS 里的 dexdump。

原理：
  1. dexdump -d 反汇编 APK，抠出所有 `L类;.成员:描述符` 形态的引用
  2. 对每个被引用的平台类，用 `javap -s -protected` 拿到它在目标 android.jar 里的
     完整 (成员名, JVM 描述符) 集合 —— 并且**沿 extends / implements 递归向上找**，
     这样继承来的方法（如 Button 上的 setOnClickListener，其实声明在 View 上）不会误报
  3. 逐个比对，列出「引用了但目标平台没有」的成员

踩过的三个坑（都已在代码里修掉，改动前先读一遍）：
  · 描述符正则不能排除 ';' —— `Ljava/lang/String;` 的尾分号是描述符的一部分。
    排掉它会让所有「返回对象类型」的方法全部误报，而返回 void 的却正常，
    症状非常有迷惑性。
  · javap 要用 `-protected` 而不是 `-public`。onCreate / onPause 这些生命周期
    回调都是 protected，用 -public 会看不到，全部误报成「缺失」。
  · 构造函数的声明是 `public android.media.MediaPlayer();` —— 类名和左括号之间
    **没有空格**，所以「空白 + 名字 + (」那套正则会漏掉所有构造函数。
"""
import os
import re
import subprocess
import sys
from collections import defaultdict

# dexdump 反汇编里的引用形态（注意是 `.` 不是 `->`）：
#   |0002: invoke-virtual {v4}, Landroid/content/Intent;.getAction:()Ljava/lang/String; // method
#   |0000: iget-object v1, v0, Lcom/foo/Bar;.this$0:Lcom/foo/Bar; // field
REF_RE = re.compile(r'(L[\w/$]+;)\.([\w$<>]+):(\S+)')

PLATFORM_PREFIXES = (
    'android/', 'java/', 'javax/', 'org/apache/', 'org/xml/', 'org/json/',
    'org/w3c/', 'dalvik/',
)

# javap 成员声明行的解析
RE_METHOD = re.compile(r'^(?:public|protected)\s+.*?([\w$.]+)\s*\(')
RE_FIELD = re.compile(r'^(?:public|protected)\s+.*?\s([\w$]+)\s*;$')
RE_DESC = re.compile(r'^descriptor:\s*(\S+)$')
RE_EXTENDS = re.compile(r'\bextends\s+([\w.$]+)')
RE_IMPLEMENTS = re.compile(r'\bimplements\s+([\w.$]+(?:\s*,\s*[\w.$]+)*)')


def find_java_home():
    jh = os.environ.get('JAVA_HOME')
    if jh and os.path.exists(os.path.join(jh, 'bin', 'javap')):
        return jh
    for d in os.environ.get('PATH', '').split(os.pathsep):
        if d and os.path.exists(os.path.join(d, 'javap')):
            return os.path.dirname(d)
    return None


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


def collect_refs(apk, dexdump_bin, tmpdir):
    """反汇编 APK，返回 {类(slash形式): {(成员名, 描述符)}} × 2（方法 / 字段）"""
    dump = os.path.join(tmpdir, 'dexdump_apicheck.txt')
    with open(dump, 'w', encoding='utf-8', errors='replace') as f:
        subprocess.run([dexdump_bin, '-d', apk], stdout=f,
                       stderr=subprocess.DEVNULL, check=False)

    methods, fields = defaultdict(set), defaultdict(set)
    with open(dump, encoding='utf-8', errors='replace') as f:
        for line in f:
            for m in REF_RE.finditer(line):
                cls = m.group(1)[1:-1]
                if not cls.startswith(PLATFORM_PREFIXES):
                    continue
                member, desc = m.group(2), m.group(3)
                (methods if desc.startswith('(') else fields)[cls].add((member, desc))
    return methods, fields


class JarIndex:
    """按需查询 android.jar，并缓存结果（含父类/接口链递归）"""

    def __init__(self, javap, jar):
        self.javap = javap
        self.jar = jar
        self._cache = {}      # cls -> (members, parents) 或 None
        self._resolved = {}   # cls -> 含继承的完整成员集合

    def _raw(self, cls):
        if cls in self._cache:
            return self._cache[cls]
        dotted = cls.replace('/', '.')
        try:
            p = subprocess.run([self.javap, '-s', '-protected', '-classpath',
                                self.jar, dotted],
                               capture_output=True, text=True, timeout=60)
        except subprocess.TimeoutExpired:
            self._cache[cls] = None
            return None
        if p.returncode != 0 or 'Error:' in p.stderr:
            self._cache[cls] = None
            return None

        simple = dotted.rsplit('.', 1)[-1]
        members = set()
        parents = []
        pending = None
        for raw in p.stdout.splitlines():
            line = raw.strip()
            # 类声明行：抓 extends / implements
            if re.match(r'^(?:public|protected|abstract|final|class|interface)', line) \
                    and '{' in line and '(' not in line:
                for mm in RE_EXTENDS.finditer(line):
                    parents.append(mm.group(1).replace('.', '/'))
                for mm in RE_IMPLEMENTS.finditer(line):
                    for x in mm.group(1).split(','):
                        x = x.strip()
                        if x:
                            parents.append(x.replace('.', '/'))
                continue

            mm = RE_METHOD.match(line)
            if mm:
                name = mm.group(1).rsplit('.', 1)[-1]
                pending = '<init>' if name == simple else name
                continue
            fm = RE_FIELD.match(line)
            if fm:
                pending = fm.group(1)
                continue
            dm = RE_DESC.match(line)
            if dm and pending:
                members.add((pending, dm.group(1)))
                pending = None

        self._cache[cls] = (members, parents)
        return self._cache[cls]

    def members(self, cls, _seen=None):
        """含继承链的完整成员集合；类不存在返回 None"""
        if cls in self._resolved:
            return self._resolved[cls]
        if _seen is None:
            _seen = set()
        if cls in _seen:
            return set()
        _seen.add(cls)

        raw = self._raw(cls)
        if raw is None:
            # 不缓存 None 到 _resolved，因为可能是递归中的临时失败
            return None
        members, parents = raw
        total = set(members)
        for p in parents:
            sub = self.members(p, _seen)
            if sub:
                total |= sub
        self._resolved[cls] = total
        return total


def find_aapt2():
    bt = os.environ.get('ANDROID_BUILD_TOOLS')
    if bt and os.path.exists(os.path.join(bt, 'aapt2')):
        return os.path.join(bt, 'aapt2')
    home = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    if home:
        d = os.path.join(home, 'build-tools')
        if os.path.isdir(d):
            for v in sorted(os.listdir(d), reverse=True):
                p = os.path.join(d, v, 'aapt2')
                if os.path.exists(p):
                    return p
    return None


def apk_min_sdk(apk):
    """从 APK 里读出 minSdkVersion（API level）"""
    aapt2 = find_aapt2()
    if not aapt2:
        return None
    try:
        p = subprocess.run([aapt2, 'dump', 'badging', apk],
                           capture_output=True, text=True, timeout=120)
    except subprocess.TimeoutExpired:
        return None
    m = re.search(r"^sdkVersion:'(\d+)'", p.stdout, re.M)
    return int(m.group(1)) if m else None


def resolve_baseline(apk, arg):
    """把 [基线] 参数解析成 jar 路径"""
    home = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')

    if arg and arg.endswith('.jar'):
        return arg if os.path.exists(arg) else None

    api = int(arg) if (arg and arg.isdigit()) else None
    if api is None:
        api = apk_min_sdk(apk)
        if api is None:
            print('无法从 APK 读出 minSdkVersion —— 请显式给出基线', file=sys.stderr)
            return None

    if not home:
        print('未设置 ANDROID_HOME，无法定位 platforms/android-%d' % api, file=sys.stderr)
        return None
    cand = os.path.join(home, 'platforms', 'android-%d' % api, 'android.jar')
    if os.path.exists(cand):
        return cand
    print('找不到 API %d 的基线: %s' % (api, cand), file=sys.stderr)
    print('  可用: %s' % ', '.join(
        sorted(os.listdir(os.path.join(home, 'platforms')))
        if os.path.isdir(os.path.join(home, 'platforms')) else []), file=sys.stderr)
    return None


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 2

    apk = sys.argv[1]
    if not os.path.exists(apk):
        print('找不到: %s' % apk, file=sys.stderr)
        return 2

    jar = resolve_baseline(apk, sys.argv[2] if len(sys.argv) > 2 else None)
    if not jar:
        return 2

    jh = find_java_home()
    if not jh:
        print('找不到 javap —— 请设置 JAVA_HOME', file=sys.stderr)
        return 2
    dexdump = find_dexdump()
    if not dexdump:
        print('找不到 dexdump —— 请设置 ANDROID_BUILD_TOOLS 或 ANDROID_HOME', file=sys.stderr)
        return 2

    tmpdir = os.environ.get('TMPDIR', '/tmp')
    methods, fields = collect_refs(apk, dexdump, tmpdir)

    all_classes = sorted(set(methods) | set(fields))
    n_methods = sum(len(v) for v in methods.values())
    n_fields = sum(len(v) for v in fields.values())

    print('=' * 66)
    print('APK : %s' % apk)
    print('基线: %s' % jar)
    print('=' * 66)
    print('被引用的平台类 %d 个 · 方法 %d 个 · 字段 %d 个'
          % (len(all_classes), n_methods, n_fields))
    print()

    idx = JarIndex(os.path.join(jh, 'bin', 'javap'), jar)

    missing_class, missing_member = [], []
    for cls in all_classes:
        have = idx.members(cls)
        if have is None:
            missing_class.append(cls)
            continue
        for name, desc in sorted(methods.get(cls, ())):
            if name == '<clinit>':
                continue
            if (name, desc) not in have:
                missing_member.append((cls, name, desc))
        for name, desc in sorted(fields.get(cls, ())):
            if (name, desc) not in have:
                missing_member.append((cls, name, desc))

    if missing_class:
        print('!! 目标平台根本没有这些类 —— 引用即崩溃')
        for c in missing_class:
            print('   %s' % c.replace('/', '.'))
        print()

    if missing_member:
        print('!! 这些成员在目标平台（含继承链）里都找不到')
        for c, n, d in missing_member:
            print('   %s.%s%s' % (c.replace('/', '.'), n, d))
        print()
    else:
        print('所有引用的成员都在目标平台（含继承链）里找到了。')

    print()
    total = n_methods + n_fields
    if not missing_class and not missing_member:
        print('结论：%d 个平台引用全部命中，无 API 越界。' % total)
        return 0
    print('结论：%d 个引用中 %d 个在目标平台不存在 —— 真机上必崩，必须处理。'
          % (total, len(missing_member) + len(missing_class)))
    return 1


if __name__ == '__main__':
    sys.exit(main())
