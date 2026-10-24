package wordnest;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;

public final class App {
    private static FileChannel lockChannel;
    private static FileLock lock;
    private static ActivationRequests activationRequests;
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
            if (lock == null) {
                lockChannel.close();
                requestExistingWindow(dataDir);
                return;
            }
            activationRequests = ActivationRequests.start(dataDir);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try { if (activationRequests != null) activationRequests.close(); } catch (Exception ignored) { }
                try { if (lock != null) lock.release(); if (lockChannel != null) lockChannel.close(); } catch (Exception ignored) { }
            }));
        } catch (Exception e) {
            JOptionPane.showMessageDialog(null, "无法打开本地数据目录：\n" + dataDir + "\n" + e.getMessage(), "词巢启动失败", JOptionPane.ERROR_MESSAGE); return;
        }
        SwingUtilities.invokeLater(() -> {
            try {
                MainWindow window = new MainWindow(dataDir);
                Timer activationTimer = new Timer(400, event -> {
                    try { activationRequests.poll(window::restoreFromTray); }
                    catch (Exception ignored) { /* A failed request must not stop normal dictionary use. */ }
                });
                window.addWindowListener(new WindowAdapter() {
                    @Override public void windowClosed(WindowEvent event) {
                        activationTimer.stop();
                        try { activationRequests.close(); } catch (Exception ignored) { }
                    }
                });
                window.setVisible(true);
                activationTimer.start();
            }
            catch (Exception e) { JOptionPane.showMessageDialog(null, "启动失败：" + e.getMessage(), "词巢", JOptionPane.ERROR_MESSAGE); }
        });
    }

    private static void requestExistingWindow(Path dataDir) {
        try {
            if (ActivationRequests.requestAndAwait(dataDir, 2_000) == ActivationRequests.Result.ACKNOWLEDGED) return;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) { }
        JOptionPane.showMessageDialog(null,
                "词巢已经在运行，但暂时没有收到显示窗口的确认。\n"
                        + "请从系统托盘的词巢图标选择“显示词巢”，或查看任务栏。\n"
                        + "如果仍在使用旧版，请先退出旧版后再打开新版。",
                "词巢", JOptionPane.INFORMATION_MESSAGE);
    }
}
