package wordnest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Shared isolated cache for real packaged-data tests, never the user's WordNest directory. */
final class DictionaryTestResources {
    private DictionaryTestResources() { }
    static DictionaryRepository load() throws IOException {
        String configured = System.getProperty("wordnest.testCache");
        Path cache = configured == null ? Files.createTempDirectory("wordnest-test-dictionary-") : Path.of(configured);
        return DictionaryRepository.loadBundled(cache);
    }
}
