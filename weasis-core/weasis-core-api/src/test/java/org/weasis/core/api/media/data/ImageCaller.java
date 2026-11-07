/*
 * Copyright (c) 2009-2020 Weasis Team and other contributors.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at https://www.eclipse.org/legal/epl-2.0, or the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package org.weasis.core.api.media.data;

import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.Collection;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import org.weasis.opencv.data.PlanarImage;

/**
 * A caller thread of {@link ImageElement#getImage()} that records what it observed when the call
 * returned: the image, its interrupt status and whether the fake reader's latch was open.
 */
final class ImageCaller {

  private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();

  final Thread thread;
  private final ImageElement element;
  private final BlockingFakeReader.Gate gate;
  private final CyclicBarrier startBarrier;

  volatile PlanarImage result;
  volatile boolean returned;
  volatile boolean interruptedAtReturn;
  volatile boolean gateOpenAtReturn;
  volatile Throwable failure;

  ImageCaller(String name, ImageElement element, BlockingFakeReader.Gate gate) {
    this(name, element, gate, null);
  }

  /** @param startBarrier passed immediately before calling getImage(), or null */
  ImageCaller(
      String name, ImageElement element, BlockingFakeReader.Gate gate, CyclicBarrier startBarrier) {
    this.element = element;
    this.gate = gate;
    this.startBarrier = startBarrier;
    this.thread = new Thread(this::call, name);
  }

  private void call() {
    try {
      if (startBarrier != null) {
        startBarrier.await(10, TimeUnit.SECONDS);
      }
      result = element.getImage();
      interruptedAtReturn = Thread.currentThread().isInterrupted();
      gateOpenAtReturn = gate.isOpen();
      returned = true;
    } catch (Throwable e) {
      failure = e;
    }
  }

  ImageCaller start() {
    thread.start();
    return this;
  }

  /** @return true if the call ended within the timeout */
  boolean join(long timeout, TimeUnit unit) throws InterruptedException {
    thread.join(Math.max(1, unit.toMillis(timeout)));
    return !thread.isAlive();
  }

  /**
   * The caller thread's state, the lock it waits for and that lock's owner; null once the thread
   * has ended.
   */
  ThreadInfo threadInfo() {
    return THREADS.getThreadInfo(thread.getId());
  }

  /** Each caller's thread state and the lock it waits for, for a failure message. */
  static String describe(Collection<ImageCaller> callers) {
    StringBuilder text = new StringBuilder();
    for (ImageCaller caller : callers) {
      ThreadInfo info = caller.threadInfo();
      text.append(caller.thread.getName()).append('=');
      if (info == null) {
        text.append("ended");
      } else {
        text.append(info.getThreadState());
        LockInfo lock = info.getLockInfo();
        if (lock != null) {
          text.append(" on ").append(lock);
        }
      }
      text.append("; ");
    }
    return text.toString();
  }

  /** Joins every caller, each with the timeout; returns how many are still running. */
  static int joinAll(Collection<ImageCaller> callers, long timeout, TimeUnit unit)
      throws InterruptedException {
    int alive = 0;
    for (ImageCaller caller : callers) {
      if (!caller.join(timeout, unit)) {
        alive++;
      }
    }
    return alive;
  }
}
