package top.niunaijun.blackbox.closecode;


import java.io.File;
import java.io.FileFilter;

import top.niunaijun.blackbox.app.configuration.AppLifecycleCallback;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.core.env.BEnvironment;
import top.niunaijun.blackbox.utils.Slog;

/**
 * Per-app native library injection.
 *
 * Libraries dropped into BEnvironment.getInjectLibDir(pkg) are System.load()'d
 * inside the virtual app's process from beforeCreateApplication — i.e. before
 * the virtual app's Application class is instantiated (BActivityThread
 * .handleBindApplication). A sibling "<name>.disabled" marker file skips a lib.
 * Each load is individually guarded: one broken lib must never abort a launch.
 */
public class Entry {
    private static final String TAG = "Lib Injection";
    private static final String DISABLED_SUFFIX = ".disabled";

    public static void attach() {
        BlackBoxCore.get().addAppLifecycleCallback(new AppLifecycleCallback() {
            @Override
            public void beforeCreateApplication(String packageName, String processName,
                                                android.content.Context context, int userId) {
                File dir = BEnvironment.getInjectLibDir(packageName);
                if (dir == null || !dir.isDirectory()) {
                    return;
                }
                File[] libs = dir.listFiles(new FileFilter() {
                    @Override
                    public boolean accept(File f) {
                        return f.isFile() && f.getName().endsWith(".so");
                    }
                });
                if (libs == null || libs.length == 0) {
                    return;
                }
                for (File lib : libs) {
                    if (new File(lib.getAbsolutePath() + DISABLED_SUFFIX).exists()) {
                        Slog.d(TAG, "Skipping disabled lib: " + lib.getName() + " for " + packageName);
                        continue;
                    }
                    try {
                        System.load(lib.getAbsolutePath());
                        Slog.d(TAG, "Injected native lib before Application created: "
                                + lib.getAbsolutePath() + " for package: " + packageName);
                    } catch (Throwable t) {
                        Slog.e(TAG, "Failed to inject native lib: " + lib.getAbsolutePath()
                                + " for package: " + packageName, t);
                    }
                }
            }
        });
        Slog.d(TAG, "Custom closed code initialized!");
    }
}
