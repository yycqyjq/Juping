#!/usr/bin/env python3
"""读取 APK 的 minSdkVersion / targetSdkVersion / 包名 / 版本号。

不依赖 aapt、androguard，纯标准库解析二进制 AndroidManifest.xml (AXML)。
用法: python3 apk_info.py <apk路径> [更多apk...]
"""
import struct
import sys
import zipfile

# AXML chunk 类型
RES_STRING_POOL_TYPE = 0x0001
RES_XML_TYPE = 0x0003
RES_XML_START_ELEMENT_TYPE = 0x0102
RES_XML_RESOURCE_MAP_TYPE = 0x0180

# 属性值类型
TYPE_INT_DEC = 0x10
TYPE_INT_HEX = 0x11
TYPE_STRING = 0x03
TYPE_REFERENCE = 0x01

# 我们关心的 android 属性名
WANTED = {
    "minSdkVersion",
    "targetSdkVersion",
    "maxSdkVersion",
    "compileSdkVersion",
    "compileSdkVersionCodename",
}


def parse_string_pool(data, offset):
    """返回 (strings, next_offset)"""
    (chunk_type, header_size, chunk_size) = struct.unpack_from("<HHI", data, offset)
    assert chunk_type == RES_STRING_POOL_TYPE, "not a string pool"
    string_count, style_count, flags, strings_start, styles_start = struct.unpack_from(
        "<IIIII", data, offset + 8
    )
    is_utf8 = bool(flags & (1 << 8))
    offsets = struct.unpack_from("<%dI" % string_count, data, offset + 28)
    base = offset + strings_start
    out = []
    for off in offsets:
        pos = base + off
        if is_utf8:
            # 两段长度（UTF-16 长度 + UTF-8 字节长度），都是 varint
            n = data[pos]
            if n & 0x80:
                n = ((n & 0x7F) << 8) | data[pos + 1]
                pos += 2
            else:
                pos += 1
            n = data[pos]
            if n & 0x80:
                n = ((n & 0x7F) << 8) | data[pos + 1]
                pos += 2
            else:
                pos += 1
            out.append(data[pos : pos + n].decode("utf-8", "replace"))
        else:
            n = struct.unpack_from("<H", data, pos)[0]
            pos += 2
            if n & 0x8000:
                n = ((n & 0x7FFF) << 16) | struct.unpack_from("<H", data, pos)[0]
                pos += 2
            out.append(data[pos : pos + n * 2].decode("utf-16-le", "replace"))
    return out, offset + chunk_size


def parse_manifest(axml):
    """解析 AXML，返回 (package, versionName, versionCode, sdk_dict)"""
    if struct.unpack_from("<H", axml, 0)[0] != RES_XML_TYPE:
        raise ValueError("不是合法的 AXML 文件")

    strings = []
    pos = 8
    total = len(axml)
    pkg = None
    version_name = None
    version_code = None
    sdk = {}

    while pos + 8 <= total:
        chunk_type, header_size, chunk_size = struct.unpack_from("<HHI", axml, pos)
        if chunk_size <= 0:
            break
        if chunk_type == RES_STRING_POOL_TYPE:
            strings, _ = parse_string_pool(axml, pos)
        elif chunk_type == RES_XML_START_ELEMENT_TYPE:
            # 跳过 chunk header(8) + lineNumber(4) + comment(4)
            ns_idx, name_idx = struct.unpack_from("<II", axml, pos + 16)
            attr_start, attr_size, attr_count = struct.unpack_from("<HHH", axml, pos + 24)
            tag = strings[name_idx] if name_idx < len(strings) else ""
            attr_base = pos + 16 + attr_start
            for i in range(attr_count):
                ap = attr_base + i * attr_size
                a_ns, a_name, a_raw = struct.unpack_from("<III", axml, ap)
                a_type = axml[ap + 15]
                a_data = struct.unpack_from("<I", axml, ap + 16)[0]
                key = strings[a_name] if a_name < len(strings) else ""
                if key == "package" and tag == "manifest":
                    pkg = strings[a_data] if a_type == TYPE_STRING and a_data < len(strings) else None
                elif key == "versionName" and tag == "manifest":
                    version_name = strings[a_data] if a_type == TYPE_STRING and a_data < len(strings) else str(a_data)
                elif key == "versionCode" and tag == "manifest":
                    version_code = a_data
                elif key in WANTED:
                    if a_type in (TYPE_INT_DEC, TYPE_INT_HEX):
                        sdk[key] = a_data
                    elif a_type == TYPE_STRING and a_data < len(strings):
                        sdk[key] = strings[a_data]
        pos += chunk_size

    return pkg, version_name, version_code, sdk


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 1
    for path in sys.argv[1:]:
        print("=" * 62)
        print("文件:", path)
        try:
            with zipfile.ZipFile(path) as z:
                axml = z.read("AndroidManifest.xml")
            pkg, vname, vcode, sdk = parse_manifest(axml)
            print("  包名        :", pkg)
            print("  versionName :", vname)
            print("  versionCode :", vcode)
            mn = sdk.get("minSdkVersion")
            tg = sdk.get("targetSdkVersion")
            print("  minSdkVersion    :", mn, "(Android %s)" % sdk_to_android(mn))
            print("  targetSdkVersion :", tg)
            for k in ("maxSdkVersion", "compileSdkVersion"):
                if k in sdk:
                    print("  %-17s: %s" % (k, sdk[k]))
        except Exception as e:
            print("  解析失败:", type(e).__name__, e)
    return 0


def sdk_to_android(api):
    table = {
        1: "1.0", 2: "1.1", 3: "1.5", 4: "1.6", 5: "2.0", 6: "2.0.1", 7: "2.1",
        8: "2.2", 9: "2.3", 10: "2.3.3", 11: "3.0", 12: "3.1", 13: "3.2",
        14: "4.0", 15: "4.0.3", 16: "4.1", 17: "4.2", 18: "4.3", 19: "4.4",
        20: "4.4W", 21: "5.0", 22: "5.1", 23: "6.0", 24: "7.0", 25: "7.1",
        26: "8.0", 27: "8.1", 28: "9", 29: "10", 30: "11", 31: "12", 32: "12L",
        33: "13", 34: "14", 35: "15", 36: "16",
    }
    try:
        return table.get(int(api), "未知")
    except (TypeError, ValueError):
        return "未知"


if __name__ == "__main__":
    sys.exit(main())
