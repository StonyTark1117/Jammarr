package stonytark.jammarr.server;

import java.util.List;

public final class QueueOperations {
    public enum Result { APPLIED, PERMISSION_DENIED, INVALID_INDEX, FULL, NOTHING_TO_ADD }

    public static AppendResult append(List<QueueTrack> queue, List<QueueTrack> additions, int limit) {
        int room = Math.max(0, limit - queue.size());
        if (room == 0) return new AppendResult(Result.FULL, 0);
        int accepted = Math.min(room, additions.size());
        if (accepted == 0) return new AppendResult(Result.NOTHING_TO_ADD, 0);
        queue.addAll(additions.subList(0, accepted));
        return new AppendResult(Result.APPLIED, accepted);
    }

    public static Result remove(List<QueueTrack> queue, int index, boolean operator) {
        if (!operator) return Result.PERMISSION_DENIED;
        if (index < 0 || index >= queue.size()) return Result.INVALID_INDEX;
        queue.remove(index); return Result.APPLIED;
    }

    public static Result move(List<QueueTrack> queue, int index, int delta, boolean operator) {
        if (!operator) return Result.PERMISSION_DENIED;
        int target = index + delta;
        if (index <= 0 || target <= 0 || index >= queue.size() || target >= queue.size()) return Result.INVALID_INDEX;
        QueueTrack value = queue.remove(index); queue.add(target, value); return Result.APPLIED;
    }

    public record AppendResult(Result result, int accepted) {}
    private QueueOperations() {}
}
