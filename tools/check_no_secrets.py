#!/usr/bin/env python3
"""发布前核查：被跟踪的文件里有没有混进密钥的真实值。

这个仓库是公开的，而 release 签名密钥一旦泄漏，任何人都能伪造出
「能覆盖升级到已装设备上」的 APK —— 比普通源码泄漏严重得多。

所以把它做成一道自动闸门，而不是靠人记得去翻。

判据按**值的性质**分两类，不能一视同仁（第一版就没分，结果报了一堆
假阳性 —— 检查器喊狼来了，跟不检查一样糟）：

  1. 密码类（key 名含 password / passwd / secret / token / pwd）
     —— **必须零命中**。这是硬判据，命中即失败，且**不打印值的任何片段**。
  2. 路径 / 别名类（storeFile / keyAlias …）
     —— 本身不是秘密。`storeFile` 出现在 README 里是**有意为之**（告诉人
     密钥库该放哪，而 .jks 本身没入库）；`keyAlias` 常常就是项目名，
     会命中包名 `com.juping.cast` 里的子串。这类只作提示，不影响结论。

用法：
    ./tools/check_no_secrets.py            # 在仓库根目录跑
退出码：0 = 干净；1 = 密码泄漏或敏感文件被跟踪；2 = 环境问题
"""
import pathlib
import re
import subprocess
import sys

# 值属于「秘密」的键名特征 —— 这些一旦命中就是真泄漏
SECRET_KEY = re.compile(r'pass|pwd|secret|token|credential|apikey|api_key', re.I)

# 值本身不该进仓库的文件名特征
BAD_FILE = re.compile(r'keystore|\.jks$|\.keystore$|local\.properties|'
                      r'signing\.properties|\.env$|\.p12$|\.pem$|\.pfx$', re.I)

# 文本匹配时要跳过的（体积大且必然不含密钥）
SKIP_BLOB = re.compile(r'\.(png|jpg|jpeg|gif|jar|apk|aab|so|dex)$', re.I)

PROPS = ('keystore.properties', 'signing.properties', 'local.properties')


def repo_root():
    out = subprocess.run(['git', 'rev-parse', '--show-toplevel'],
                         capture_output=True, text=True)
    if out.returncode != 0:
        return None
    return pathlib.Path(out.stdout.strip())


def tracked_files(root):
    out = subprocess.run(['git', 'ls-files'], cwd=root,
                         capture_output=True, text=True)
    return out.stdout.split()


def load_blobs(root, names):
    blobs = {}
    for f in names:
        if SKIP_BLOB.search(f):
            blobs[f] = ''
            continue
        try:
            blobs[f] = (root / f).read_text(encoding='utf-8', errors='replace')
        except Exception:
            blobs[f] = ''
    return blobs


def read_props(root):
    """读签名配置。值只留在内存里，绝不打印。"""
    entries = []
    for name in PROPS:
        p = root / name
        if not p.exists():
            continue
        for line in p.read_text(encoding='utf-8').splitlines():
            line = line.strip()
            if not line or line.startswith('#') or '=' not in line:
                continue
            k, v = line.split('=', 1)
            entries.append((name, k.strip(), v.strip()))
    return entries


def main():
    root = repo_root()
    if root is None:
        print('不在 git 仓库里', file=sys.stderr)
        return 2

    names = tracked_files(root)
    blobs = load_blobs(root, names)
    entries = read_props(root)

    print('仓库   : %s' % root)
    print('跟踪文件: %d 个' % len(names))
    if not entries:
        print('签名配置: 未找到（%s 都不存在）—— 只做文件名与模式检查'
              % ' / '.join(PROPS))
    print()

    failures = []

    # ── 1. 敏感文件名 ──
    print('=== ① 敏感文件是否被跟踪 ===')
    bad_names = [f for f in names if BAD_FILE.search(f)]
    if bad_names:
        for f in bad_names:
            print('  [FAIL] 被跟踪：%s' % f)
        failures.append('敏感文件被跟踪')
    else:
        print('  [PASS] 无密钥库 / local.properties / .env 等被跟踪')

    # ── 2. 密码类真实值 ──
    print()
    print('=== ② 密码类真实值是否出现在任何被跟踪文件里 ===')
    secret_vals = [(k, v) for _, k, v in entries if SECRET_KEY.search(k) and v]
    if not secret_vals:
        print('  [SKIP] 没有可用的密码类值（配置缺失？）')
    for k, v in secret_vals:
        hits = sorted(f for f, t in blobs.items() if v in t)
        if hits:
            # 注意：连值的长度都不打印
            print('  [FAIL] %s 的真实值出现在：%s' % (k, ', '.join(hits)))
            failures.append('%s 泄漏' % k)
        else:
            print('  [PASS] %s 的真实值零命中（%d 个文件里都没有）'
                  % (k, len(blobs)))

    # ── 3. 明文密码模式 ──
    print()
    print('=== ③ 源码里有没有硬编码密码 ===')
    pat = re.compile(r'(storePassword|keyPassword|password|passwd)\s*=\s*'
                     r'["\'][^"\'$][^"\']*["\']', re.I)
    hits = [(f, i + 1) for f, t in blobs.items()
            for i, line in enumerate(t.splitlines())
            if pat.search(line)]
    if hits:
        for f, ln in hits:
            print('  [FAIL] %s:%d 疑似硬编码密码' % (f, ln))
        failures.append('硬编码密码')
    else:
        print('  [PASS] 无硬编码密码（只允许读配置或走环境变量）')

    # ── 4. 路径 / 别名类（提示，不影响结论）──
    print()
    print('=== ④ 路径 / 别名类（提示项，不算泄漏）===')
    benign = [(k, v) for _, k, v in entries if not SECRET_KEY.search(k) and v]
    if not benign:
        print('  （无）')
    for k, v in benign:
        hits = sorted(f for f, t in blobs.items() if v in t)
        note = '出现在 %s' % ', '.join(hits[:3]) if hits else '未出现'
        print('  [INFO] %-12s = %-34s %s' % (k, v, note))
    if benign:
        print('  说明：这类值本身不是秘密。storeFile 出现在 README 是刻意公开的'
              '（.jks 并未入库）；keyAlias 常常就是项目名，会命中包名子串。')

    print()
    print('=' * 62)
    if failures:
        print(' 结论：发现 %d 类问题 —— 不要公开这个仓库！' % len(failures))
        for f in failures:
            print('   · %s' % f)
        return 1
    print(' 结论：干净 —— 没有任何密钥真实值、明文密码或敏感文件被跟踪')
    return 0


if __name__ == '__main__':
    sys.exit(main())
