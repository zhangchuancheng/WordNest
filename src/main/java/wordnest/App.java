package wordnest;

import javax.swing.*;
import java.awt.*;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;

public final class App {
    private static FileChannel lockChannel;
    private static FileLock lock;
    public static void main(String[] args) {
        if (args.length > 0 && "--self-check".equals(args[0])) {
            System.setProperty("java.awt.headless", "true");
            System.exit(args.length == 2 ? PackageCheck.run(args[1]) : 2);
            return;
        }
        Theme.install();
        Path dataDir = Path.of(System.getProperty("wordnest.dataDir",
                Path.of(System.getenv().getOrDefault("LOCALAPPDATA", System.getProperty("user.home")), "WordNest").toString()));
        try {
            Files.createDirectories(dataDir);
            lockChannel = FileChannel.open(dataDir.resolve("app.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            lock = lockChannel.tryLock();
            if (lock == null) { JOptionPane.showMessageDialog(null, "拾词已经在运行，请查看任务栏中的窗口。", "拾词", JOptionPane.INFORMATION_MESSAGE); return; }
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try { if (lock != null) lock.release(); if (lockChannel != null) lockChannel.close(); } catch (Exception ignored) { }
            }));
        } catch (Exception e) {
            JOptionPane.showMessageDialog(null, "无法打开本地数据目录：\n" + dataDir + "\n" + e.getMessage(), "拾词启动失败", JOptionPane.ERROR_MESSAGE); return;
        }
        SwingUtilities.invokeLater(() -> {
            try { new MainWindow(dataDir).setVisible(true); }
            catch (Exception e) { JOptionPane.showMessageDialog(null, "启动失败：" + e.getMessage(), "拾词", JOptionPane.ERROR_MESSAGE); }
        });
    }
}
