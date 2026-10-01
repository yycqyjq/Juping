package android.content;

import java.io.File;

/**
 * android.content.Context 的桌面替身。
 *
 * <p>只为了让 {@code LocalStore} 能在桌面 JVM 上**编译通过**：本测试只断言它的
 * 静态方法 {@code sanitize()}（纯函数），不构造 {@code LocalStore} 实例 ——
 * 所以这里的实现在测试里根本不会被调用，只需要把类型补齐。
 */
public abstract class Context {

    public abstract Context getApplicationContext();

    public abstract File getFilesDir();
}