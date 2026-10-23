package wordnest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;

/** Data-only recovery and v1 migration checks; every file lives in a new temporary directory. */
public final class FavoritesRecoveryTest {
    private static int assertions;

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("wordnest-favorites-recovery-");
        try {
            testRetainedNotes(root.resolve("retained"));
            testExplicitMembership(root.resolve("membership"));
            testLegacyMigration(root.resolve("legacy"));
            testExistingMigrationBackups(root.resolve("backups"));
            testRejectedFiles(root.resolve("invalid"));
            testFailedChanges(root.resolve("failed"));
            testMigrationBackupFailure(root.resolve("failed-migration"));
            System.out.println("PASS: " + assertions
                    + " favorites recovery/migration assertions; temporary files only, no desktop or user data");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void testRetainedNotes(Path directory) throws Exception {
        FavoritesStore store = new FavoritesStore(directory);
        WordEntry hello = entry("Hello");
        String note = "我的例句\nHello, world!\t保留中文、换行和符号 ☀";
        check(new SavedWord(hello, note, 1).favorite(), "Three-argument SavedWord remains a favorite");
        store.save(hello, note);
        SavedWord original = store.get("hello").orElseThrow();
        store.remove(" HELLO ");
        check(!store.contains("hello") && store.list().isEmpty(), "Removal hides the word from favorites");
        SavedWord retained = store.get("hello").orElseThrow();
        check(!retained.favorite() && retained.note().equals(note), "Removal retains the complete personal note");
        check(retained.entry().equals(hello), "Removal retains every dictionary field");
        check(retained.savedAt() > original.savedAt(), "Recent removal receives its own change time");
        check(store.listUnfavorited().equals(List.of(retained)), "Recovery list contains the removed entry");
        expectUnsupported(() -> store.listUnfavorited().clear(), "Recovery snapshot is immutable");

        byte[] onceRemoved = Files.readAllBytes(directory.resolve("favorites.tsv"));
        store.remove("hello");
        store.remove("missing");
        check(Arrays.equals(onceRemoved, Files.readAllBytes(directory.resolve("favorites.tsv"))),
                "Repeated or missing removal leaves the saved record unchanged");
        FavoritesStore reopened = new FavoritesStore(directory);
        check(!reopened.contains("hello") && reopened.get("hello").orElseThrow().equals(retained),
                "Retained note and cancelled membership survive restart");
        reopened.save(hello, "取消收藏后继续修改的最新例句");
        check(!reopened.contains("hello"), "Editing a retained note does not silently favorite it again");
        check(reopened.list().isEmpty() && reopened.listUnfavorited().size() == 1,
                "Editing retains the word in the recovery list");
        check(reopened.restoreFavorite(" HeLLo "), "Restore accepts a normalized spelling");
        check(reopened.get("hello").orElseThrow().note().equals("取消收藏后继续修改的最新例句"),
                "Restore uses the latest note rather than the note captured before cancellation");
        check(reopened.contains("hello") && reopened.listUnfavorited().isEmpty(), "Restore changes membership only");
        FavoritesStore restored = new FavoritesStore(directory);
        check(restored.get("hello").orElseThrow().equals(reopened.get("hello").orElseThrow()),
                "Restored favorite and updated note survive another restart");
        byte[] restoredBytes = Files.readAllBytes(directory.resolve("favorites.tsv"));
        check(!restored.restoreFavorite("hello") && !restored.restoreFavorite("missing"),
                "Restore is harmless for already-favorited or absent entries");
        check(Arrays.equals(restoredBytes, Files.readAllBytes(directory.resolve("favorites.tsv"))),
                "No-op restore does not rewrite or retimestamp entries");
        check(!Files.exists(directory.resolve("favorites.tsv.v1-backup")), "New v2 stores do not create a fake v1 backup");
    }

    private static void testExplicitMembership(Path directory) throws Exception {
        FavoritesStore store = new FavoritesStore(directory);
        store.setFavorite(entry("first"), "保留笔记但不加入收藏", false);
        check(!store.contains("first") && store.get("first").isPresent(), "Explicit false can retain a new entry");
        store.save(entry("second"), "正常的新收藏");
        check(store.contains("second"), "Ordinary save favorites a new word by default");
        store.setFavorite(entry("second"), "最新编辑与取消一起保存", false);
        check(store.listUnfavorited().stream().map(s -> s.entry().word()).toList().equals(List.of("second", "first")),
                "Recovery entries are ordered by most recent change");
        store.setFavorite(entry("first"), "恢复时明确提交的笔记", true);
        check(store.contains("first") && store.get("first").orElseThrow().note().equals("恢复时明确提交的笔记"),
                "Explicit favorite saves note and state together");
        check(store.list().size() == 1 && store.listUnfavorited().size() == 1,
                "Favorite and retained-note lists remain separate");
        FavoritesStore reopened = new FavoritesStore(directory);
        check(reopened.list().equals(store.list()) && reopened.listUnfavorited().equals(store.listUnfavorited()),
                "Both memberships and their order survive disk round trip");
        check(Files.readAllLines(directory.resolve("favorites.tsv")).stream().skip(1)
                        .allMatch(line -> line.split("\t", -1).length == 9),
                "V2 keeps entry, note, timestamp and state in one nine-column record");
    }

    private static void testLegacyMigration(Path directory) throws Exception {
        Files.createDirectories(directory);
        WordEntry hello = entry("Hello");
        String note = "旧版笔记\n保留全部字段\t以及回车\r\n";
        byte[] original = ("WORDNEST-FAVORITES\t1\r\n"
                + row(hello, note, 11, null) + "\r\n"
                + row(entry("world"), "另一条旧笔记", 22, null) + "\r\n").getBytes(StandardCharsets.UTF_8);
        Path file = directory.resolve("favorites.tsv");
        Files.write(file, original);
        FavoritesStore store = new FavoritesStore(directory);
        check(store.list().size() == 2 && store.listUnfavorited().isEmpty(), "All valid v1 entries load as favorites");
        check(store.get("hello").orElseThrow().equals(new SavedWord(hello, note, 11)),
                "V1 fields and timestamps load without data loss");
        check(Arrays.equals(Files.readAllBytes(file), original), "Opening v1 does not modify the original file");
        check(backupFiles(directory).isEmpty(), "Opening v1 alone does not write a migration backup");
        store.remove("hello");
        check(Files.readAllLines(file).get(0).equals("WORDNEST-FAVORITES\t2"), "First mutation upgrades to v2");
        Path backup = directory.resolve("favorites.tsv.v1-backup");
        check(Arrays.equals(Files.readAllBytes(backup), original), "Migration preserves exact v1 bytes before replacement");
        check(new FavoritesStore(directory).get("hello").orElseThrow().note().equals(note),
                "Upgraded cancelled entry retains its original note");
        check(new FavoritesStore(directory).get("world").orElseThrow().savedAt() == 22,
                "Migrating another entry does not change untouched timestamps");
        store.save(hello, "迁移后的编辑");
        check(backupFiles(directory).size() == 1 && Arrays.equals(Files.readAllBytes(backup), original),
                "Later v2 changes neither replace nor multiply the original migration backup");
        Path recoverDirectory = directory.resolve("legacy-restore-check");
        Files.createDirectory(recoverDirectory);
        Files.copy(backup, recoverDirectory.resolve("favorites.tsv"));
        FavoritesStore fromBackup = new FavoritesStore(recoverDirectory);
        check(fromBackup.contains("hello") && fromBackup.get("hello").orElseThrow().note().equals(note),
                "The migration backup is itself a complete readable old-format store");
    }

    private static void testExistingMigrationBackups(Path root) throws Exception {
        for (boolean identical : List.of(true, false)) {
            Path directory = root.resolve(identical ? "same" : "different");
            Files.createDirectories(directory);
            byte[] original = ("WORDNEST-FAVORITES\t1\n" + row(entry("apple"), "旧笔记", 10, null) + "\n")
                    .getBytes(StandardCharsets.UTF_8);
            byte[] olderBackup = identical ? original : "An earlier backup must remain untouched".getBytes(StandardCharsets.UTF_8);
            Path backup = directory.resolve("favorites.tsv.v1-backup");
            Files.write(directory.resolve("favorites.tsv"), original);
            Files.write(backup, olderBackup);
            new FavoritesStore(directory).remove("apple");
            check(Arrays.equals(Files.readAllBytes(backup), olderBackup), "Existing migration backup is never overwritten");
            List<Path> backups = backupFiles(directory);
            check(backups.size() == (identical ? 1 : 2), "Identical backup reused; different old backup preserved separately");
            boolean originalPreserved = false;
            for (Path path : backups) originalPreserved |= Arrays.equals(Files.readAllBytes(path), original);
            check(originalPreserved, "Current v1 bytes are backed up even when an older backup already exists");
        }
    }

    private static void testRejectedFiles(Path directory) throws Exception {
        Files.createDirectories(directory);
        Path file = directory.resolve("favorites.tsv");
        String valid = row(entry("apple"), "note", 10, "true");
        List<String> badFiles = List.of(
                "WORDNEST-FAVORITES\t3\n" + valid + "\n",
                "WORDNEST-FAVORITES\t2\n" + row(entry("apple"), "note", 10, null) + "\n",
                "WORDNEST-FAVORITES\t2\n" + row(entry("apple"), "note", 10, "FALSE") + "\n",
                "WORDNEST-FAVORITES\t2\n" + row(entry("apple"), "note", 10, "maybe") + "\n",
                "WORDNEST-FAVORITES\t2\n" + row(entry("apple"), "note", -1, "false") + "\n",
                "WORDNEST-FAVORITES\t2\n" + valid + "\n" + row(entry("APPLE"), "duplicate", 11, "false") + "\n",
                "WORDNEST-FAVORITES\t1\n" + valid + "\n",
                "WORDNEST-FAVORITES\t2\nYXBwbGU=\t\t\t\t\t\t!!!\t10\ttrue\n");
        for (String bad : badFiles) {
            byte[] bytes = bad.getBytes(StandardCharsets.UTF_8);
            Files.write(file, bytes);
            expectIOException(() -> new FavoritesStore(directory), "Bad version, row, state or record rejected");
            check(Arrays.equals(Files.readAllBytes(file), bytes), "Rejected file is preserved byte for byte");
        }
        check(backupFiles(directory).isEmpty(), "Invalid files are never treated as migrated data");
    }

    private static void testFailedChanges(Path directory) throws Exception {
        FavoritesStore store = new FavoritesStore(directory);
        store.save(entry("kept"), "已收藏的原笔记");
        store.setFavorite(entry("removed"), "取消收藏后保留的笔记", false);
        List<SavedWord> favorites = store.list();
        List<SavedWord> removed = store.listUnfavorited();
        Path file = directory.resolve("favorites.tsv");
        Path preserved = directory.resolve("preserved-v2.tsv");
        byte[] original = Files.readAllBytes(file);
        Files.move(file, preserved);
        Files.createDirectory(file);
        Path marker = file.resolve("keep.txt");
        Files.writeString(marker, "preserve this target directory");
        expectIOException(() -> store.remove("kept"), "Failed cancellation is reported");
        expectIOException(() -> store.restoreFavorite("removed"), "Failed restore is reported");
        expectIOException(() -> store.save(entry("removed"), "不能成为已保存内容"), "Failed retained-note edit is reported");
        expectIOException(() -> store.setFavorite(entry("new"), "不能插入", true), "Failed new explicit favorite is reported");
        check(store.list().equals(favorites) && store.listUnfavorited().equals(removed),
                "All failed operations preserve both in-memory lists and notes");
        check(Arrays.equals(Files.readAllBytes(preserved), original) && Files.readString(marker).contains("preserve"),
                "Failed writes do not touch the saved file or blocking target contents");
        check(noTemporaryFiles(directory), "Failed writes leave no partial temporary data");
        Files.delete(marker); Files.delete(file); Files.move(preserved, file);
        check(store.restoreFavorite("removed"), "Restore can succeed after the storage problem is repaired");
        check(new FavoritesStore(directory).get("removed").orElseThrow().note().equals("取消收藏后保留的笔记"),
                "Retry restores the original note rather than a failed edit");
    }

    private static void testMigrationBackupFailure(Path directory) throws Exception {
        Files.createDirectories(directory);
        byte[] original = ("WORDNEST-FAVORITES\t1\n" + row(entry("apple"), "迁移前的笔记", 10, null) + "\n")
                .getBytes(StandardCharsets.UTF_8);
        Path file = directory.resolve("favorites.tsv");
        Files.write(file, original);
        FavoritesStore store = new FavoritesStore(directory);
        Path preserved = directory.resolve("preserved-v1.tsv");
        Files.move(file, preserved);
        Files.createDirectory(file);
        Path marker = file.resolve("keep.txt");
        Files.writeString(marker, "unrelated contents");
        expectIOException(() -> store.remove("apple"), "Migration refuses to replace data when its old source cannot be backed up");
        check(store.contains("apple") && store.get("apple").orElseThrow().note().equals("迁移前的笔记"),
                "Backup failure leaves membership and note unchanged");
        check(backupFiles(directory).isEmpty() && noTemporaryFiles(directory), "Failed backup and new-format temporary file are removed");
        check(Arrays.equals(Files.readAllBytes(preserved), original) && Files.readString(marker).equals("unrelated contents"),
                "Failed migration preserves old bytes and unrelated blocking directory contents");
        Files.delete(marker); Files.delete(file); Files.move(preserved, file);
        store.remove("apple");
        check(Arrays.equals(Files.readAllBytes(directory.resolve("favorites.tsv.v1-backup")), original),
                "Retry still creates the required complete v1 backup");
        check(!new FavoritesStore(directory).contains("apple"), "Migration retry persists the intended soft cancellation");
    }

    private static List<Path> backupFiles(Path directory) throws IOException {
        try (var files = Files.list(directory)) {
            return files.filter(path -> path.getFileName().toString().startsWith("favorites.tsv.v1-backup")).toList();
        }
    }

    private static boolean noTemporaryFiles(Path directory) throws IOException {
        try (var files = Files.list(directory)) {
            return files.noneMatch(path -> path.getFileName().toString().endsWith(".tmp"));
        }
    }

    private static String row(WordEntry entry, String note, long savedAt, String state) {
        String row = String.join("\t", encoded(entry.word()), encoded(entry.phonetic()), encoded(entry.translation()),
                encoded(entry.definition()), encoded(entry.tags()), encoded(entry.exchange()), encoded(note), Long.toString(savedAt));
        return state == null ? row : row + "\t" + state;
    }

    private static String encoded(String value) { return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)); }
    private static WordEntry entry(String word) {
        return new WordEntry(word, "həˈləʊ", "中文释义\n另一义项", "English definition.\nSecond meaning.", "ielts cet4", "s:words");
    }
    private static void expectIOException(CheckedAction action, String label) throws Exception {
        try { action.run(); }
        catch (IOException expected) { assertions++; return; }
        throw new AssertionError("Expected IOException: " + label);
    }
    private static void expectUnsupported(Runnable action, String label) {
        try { action.run(); }
        catch (UnsupportedOperationException expected) { assertions++; return; }
        throw new AssertionError("Expected UnsupportedOperationException: " + label);
    }
    private static void check(boolean condition, String label) {
        assertions++;
        if (!condition) throw new AssertionError(label);
    }
    @FunctionalInterface private interface CheckedAction { void run() throws Exception; }
}
