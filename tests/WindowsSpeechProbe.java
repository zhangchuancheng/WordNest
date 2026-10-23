package wordnest;

import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.*;

/** Explicit local verification: lists English SAPI voices and writes a temporary WAV. NEVER plays audio. */
public final class WindowsSpeechProbe {
    public static void main(String[] args) throws Exception {
        if (!Boolean.getBoolean("wordnest.speech.probe")) throw new IllegalArgumentException("Enable explicit -Dwordnest.speech.probe=true");
        try (WindowsSpeechEngine engine = new WindowsSpeechEngine()) {
            var voices = engine.voices(new SpeechService.Cancellation());
            System.out.println("Installed enabled English SAPI voices: " + voices.size());
            for (var voice : voices) System.out.println(voice.name() + " | " + voice.culture());
            if (voices.isEmpty()) { System.out.println("NO_ENGLISH_VOICE: no audio generated or played"); return; }
            Path wave = Files.createTempFile("wordnest-speech-check-", ".wav");
            try {
                engine.saveWave(new SpeechService.Request("WordNest can read this sentence. Quoted 'words' stay literal.", voices.get(0).name(), 0), wave, new SpeechService.Cancellation());
                byte[] bytes = Files.readAllBytes(wave);
                if (bytes.length <= 44 || !new String(bytes, 0, 4, StandardCharsets.US_ASCII).equals("RIFF")
                        || !new String(bytes, 8, 4, StandardCharsets.US_ASCII).equals("WAVE")) throw new AssertionError("Invalid WAV output");
                boolean signal = false; int audioBytes = 0;
                for (int offset = 12; offset + 8 <= bytes.length;) {
                    String chunk = new String(bytes, offset, 4, StandardCharsets.US_ASCII);
                    int length = ByteBuffer.wrap(bytes, offset + 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
                    if (length < 0 || (long) offset + 8 + length > bytes.length) throw new AssertionError("Invalid WAV chunk length");
                    if (chunk.equals("data")) {
                        audioBytes = length;
                        for (int i = offset + 8; i < offset + 8 + length; i++) if (bytes[i] != 0) { signal = true; break; }
                        break;
                    }
                    offset += 8 + length + (length & 1);
                }
                if (!signal) throw new AssertionError("WAV contains no signal");
                System.out.println("WAV_OK bytes=" + bytes.length + "; dataBytes=" + audioBytes + "; header=RIFF/WAVE; nonzero audio payload; no playback; temporary file removed after check");
            } finally { Files.deleteIfExists(wave); }
        }
    }
}
