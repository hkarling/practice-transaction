package io.hkarling.transaction.support;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class ConcurrentExecutionRunner {

  public static ConcurrentExecutionResult runConcurrently(int threadCount, Runnable action)
      throws InterruptedException {
    CountDownLatch readyLatch = new CountDownLatch(threadCount);
    CountDownLatch startLatch = new CountDownLatch(1);
    AtomicInteger successCount = new AtomicInteger();
    List<Exception> failures = new CopyOnWriteArrayList<>();

    ExecutorService pool = Executors.newFixedThreadPool(threadCount);
    for (int i = 0; i < threadCount; i++) {
      pool.submit(() -> {
        try {
          readyLatch.countDown();
          startLatch.await();
          action.run();
          successCount.incrementAndGet();
        } catch (Exception e) {
          failures.add(e);
        }
      });
    }

    readyLatch.await(5, TimeUnit.SECONDS);
    startLatch.countDown();
    pool.shutdown();
    pool.awaitTermination(30, TimeUnit.SECONDS);

    return new ConcurrentExecutionResult(successCount.get(), failures);
  }
}
