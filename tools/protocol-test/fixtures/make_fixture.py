#!/usr/bin/env python3
"""生成 selftest 用的极小 fixture APK（测试夹具，**不是发布产物**）。

它存在的唯一理由：给 tools/protocol-test/verify-device-selftest.sh 一个
**能被 tools/apk_info.py 真正解析出 minSdk** 的最小 APK，让「真机验收脚本自测」
不再依赖 dist/ 里的发布产物 —— CI 干净检出里根本没有 dist/，那正是协议闸门
在 Ubuntu 上全红的根因（见 verify-device-selftest.sh 顶部注释）。

用法:
    python3 tools/protocol-test/fixtures/make_fixture.py

输出:
    tools/protocol-test/fixtures/selftest-min-sdk-14.apk

内容：仅一个二进制 AndroidManifest.xml (AXML)，字段：
    package          = com.juping.selftest.fixture
    minSdkVersion    = 14   （与 app 的 minSdk 一致，正面自测用）
    targetSdkVersion = 15   （与目标设备 API 一致）
故意**不带**任何 dex / 资源 / 签名 —— 它不被安装，只被 apk_info.py 读 minSdk。
"""
import os
import struct
import zipfile

# AXML chunk 类型
RES_XML_TYPE = 0x0003
RES_STRING_POOL_TYPE = 0x0001
RES_XML_START_ELEMENT_TYPE = 0x0102
RES_XML_END_ELEMENT_TYPE = 0x0103

# Res_value 数据类型
TYPE_STRING = 0x03
TYPE_INT_DEC = 0x10

NO_INDEX = 0xFFFFFFFF

# 字符串池（顺序即索引，属性里的 name/ns/data 都按索引引用）
STRINGS = [
    "manifest",                                   # 0
    "package",                                    # 1
    "com.juping.selftest.fixture",                # 2
    "minSdkVersion",                              # 3
    "targetSdkVersion",                           # 4
    "http://schemas.android.com/apk/res/android", # 5
]
IDX_MANIFEST, IDX_PACKAGE, IDX_PKG_VALUE = 0, 1, 2
IDX_MIN_SDK, IDX_TARGET_SDK, IDX_ANDROID_NS = 3, 4, 5


def _string_pool(strings):
    """构造 UTF-8 字符串池 chunk（与 apk_info.py 的 UTF-8 分支严格对齐）。"""
    data = bytearray()
    offsets = []
    for s in strings:
        b = s.encode("utf-8")
        assert len(s) < 0x80 and len(b) < 0x80, "fixture 只用 ASCII，单字节长度前缀"
        offsets.append(len(data))
        data.append(len(s))        # UTF-16 码元数
        data.append(len(b))        # UTF-8 字节数
        data.extend(b)
        data.append(0x00)          # 收尾 NUL
    count = len(strings)
    header_size = 28
    strings_start = header_size + 4 * count
    chunk_size = strings_start + len(data)
    chunk = struct.pack("<HHI", RES_STRING_POOL_TYPE, header_size, chunk_size)
    chunk += struct.pack("<IIIII", count, 0, 0x00000100, strings_start, 0)
    chunk += b"".join(struct.pack("<I", o) for o in offsets)
    chunk += bytes(data)
    return chunk


def _attr(ns, name, value_type, data):
    """一个 ResXMLTree_attribute（20 字节）：ns / name / rawValue / Res_value。"""
    return struct.pack("<IIIHBBI", ns, name, NO_INDEX, 8, 0, value_type, data)


def build_axml():
    pool = _string_pool(STRINGS)

    attrs = [
        _attr(NO_INDEX, IDX_PACKAGE, TYPE_STRING, IDX_PKG_VALUE),
        _attr(IDX_ANDROID_NS, IDX_MIN_SDK, TYPE_INT_DEC, 14),
        _attr(IDX_ANDROID_NS, IDX_TARGET_SDK, TYPE_INT_DEC, 15),
    ]
    # ResXMLTree_node(16) + attrExt(20) + 属性(20*n)
    start_size = 16 + 20 + 20 * len(attrs)
    start = struct.pack("<HHI", RES_XML_START_ELEMENT_TYPE, 16, start_size)
    start += struct.pack("<II", 1, NO_INDEX)               # lineNumber, comment
    start += struct.pack(
        "<IIHHHHHH", NO_INDEX, IDX_MANIFEST, 20, 20, len(attrs), 0, 0, 0
    )
    start += b"".join(attrs)

    # 收尾的 </manifest>（apk_info.py 不读它，但让 AXML 结构完整）
    end_size = 16 + 8
    end = struct.pack("<HHI", RES_XML_END_ELEMENT_TYPE, 16, end_size)
    end += struct.pack("<II", 1, NO_INDEX)
    end += struct.pack("<II", NO_INDEX, IDX_MANIFEST)

    total = 8 + len(pool) + start_size + end_size
    return struct.pack("<HHI", RES_XML_TYPE, 8, total) + pool + start + end


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    out = os.path.join(here, "selftest-min-sdk-14.apk")
    axml = build_axml()
    # 固定时间戳 —— 让 fixture 逐字节可复现（便于 diff / 审计）
    info = zipfile.ZipInfo("AndroidManifest.xml", date_time=(2020, 1, 1, 0, 0, 0))
    info.compress_type = zipfile.ZIP_DEFLATED
    info.external_attr = 0o644 << 16
    with zipfile.ZipFile(out, "w") as z:
        z.writestr(info, axml)
    print("已生成:", out, "(%d 字节)" % os.path.getsize(out))


if __name__ == "__main__":
    main()
