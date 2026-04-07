package com.elsworne.stubline.http;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Configured request execution strategy for the JDK HTTP server. */
public enum ServerExecutor {
  THREAD_PER_REQUEST,
  FIXED_POOL,
  VIRTUAL;

  public ExecutorService create(int poolSize) {
    if (poolSize < 1) throw new IllegalArgumentException("poolSize must be positive");
    return switch (this) {
      case THREAD_PER_REQUEST -> Executors.newThreadPerTaskExecutor(Thread.ofPlatform().factory());
      case FIXED_POOL -> Executors.newFixedThreadPool(poolSize);
      case VIRTUAL -> Executors.newVirtualThreadPerTaskExecutor();
    };
  }
}
