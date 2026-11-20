package app.stemwise.quality.runner;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/** Shrinks against the same production execution and independent failure identity. */
public final class DeterministicTraceMinimizer {
    private DeterministicTraceMinimizer() {}
    public static <T> List<T> minimize(List<T> original, String failureId,
            Function<List<T>, String> evaluate) {
        if (!Objects.equals(evaluate.apply(original), failureId))
            throw new IllegalArgumentException("Original trace does not reproduce " + failureId);
        List<T> current = original;
        int chunkSize = Math.max(1, current.size() / 2);
        while (!current.isEmpty() && chunkSize >= 1) {
            boolean removed = false;
            for (int start = 0; start < current.size(); start += chunkSize) {
                List<T> candidate = new ArrayList<>(current);
                candidate.subList(start, Math.min(current.size(), start + chunkSize)).clear();
                if (Objects.equals(evaluate.apply(candidate), failureId)) {
                    current = candidate;
                    removed = true;
                    break;
                }
            }
            if (!removed) chunkSize /= 2;
            else chunkSize = Math.min(chunkSize, Math.max(1, current.size()));
        }
        return current;
    }
}
