package com.redline.worldcore.server.cube.dirty;

import com.redline.worldcore.api.cube.LevelCube;
import com.redline.worldcore.api.pos.CubePos;
import com.redline.worldcore.server.storage.CubeRegionStorage;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/** Single-threaded Region3D dirty save worker used by M14.1.1. */
public final class CubeAsyncSaveWorker {
    private final ExecutorService executor;
    private final Queue<Completion> completions = new ConcurrentLinkedQueue<>();
    private final AtomicInteger inFlight = new AtomicInteger();

    public CubeAsyncSaveWorker(String name) {
        ThreadFactory factory = task -> {
            Thread thread = new Thread(task, name);
            thread.setDaemon(true);
            return thread;
        };
        this.executor = Executors.newSingleThreadExecutor(factory);
    }

    public void submit(CubeRegionStorage storage, CubeSaveWork work, LevelCube snapshot) {
        inFlight.incrementAndGet();
        executor.submit(() -> {
            long startNanos = System.nanoTime();
            try {
                storage.flushOnly(snapshot);
                long elapsedMicros = Math.max(1L, (System.nanoTime() - startNanos) / 1_000L);
                completions.add(Completion.success(work, elapsedMicros));
            } catch (RuntimeException exception) {
                long elapsedMicros = Math.max(1L, (System.nanoTime() - startNanos) / 1_000L);
                completions.add(Completion.failure(work, elapsedMicros, exception.getMessage()));
            } finally {
                inFlight.decrementAndGet();
            }
        });
    }

    public Completion pollCompletion() {
        return completions.poll();
    }

    public int pendingCompletions() {
        return completions.size();
    }

    public int inFlight() {
        return inFlight.get();
    }

    public record Completion(CubeSaveWork work, long elapsedMicros, boolean success, String error) {
        public CubePos cubePos() {
            return work.cubePos();
        }

        static Completion success(CubeSaveWork work, long elapsedMicros) {
            return new Completion(work, elapsedMicros, true, "");
        }

        static Completion failure(CubeSaveWork work, long elapsedMicros, String error) {
            return new Completion(work, elapsedMicros, false, error == null ? "unknown" : error);
        }
    }
}
