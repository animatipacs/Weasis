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

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.management.ThreadInfo;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/**
 * FCR §3 criteria 2 and 8 with the pool size set to 1. {@code IMAGE_LOADER} reads its size once per
 * JVM, so the property is set before {@link ImageElement} is loaded and Surefire runs each test
 * class in its own JVM ({@code reuseForks=false}).
 */
public class ImageLoaderSingleThreadTest {

  static {
    System.setProperty("weasis.image.loader.threads", "1");
  }

  private static final long JOIN_SECONDS = 30;
  private static final int CALLERS = 32;

  /** Criterion 2 ({@code [guard]}): with a pool size of 1, two decodes never overlap. */
  @Test
  public void twoElementsNeverDecodeAtTheSameTime() throws Exception {
    BlockingFakeReader.Gate gate = new BlockingFakeReader.Gate();
    ImageCaller t1 = caller("caller-1", gate);
    ImageCaller t2 = caller("caller-2", gate);
    boolean overlapped;
    try {
      t1.start();
      t2.start();
      assertThat(gate.awaitFirstStarted(10, TimeUnit.SECONDS))
          .as("the first getImageFragment() started")
          .isTrue();
      // Observation window of 500 ms with the latch closed
      overlapped = gate.awaitInProgress(2, 500, TimeUnit.MILLISECONDS);
    } finally {
      gate.open();
      assertThat(ImageCaller.joinAll(Arrays.asList(t1, t2), JOIN_SECONDS, TimeUnit.SECONDS))
          .as("callers still running after the latch opened")
          .isZero();
    }
    assertThat(overlapped).as("a second getImageFragment() started during the window").isFalse();
    assertThat(gate.maxInProgress())
        .as("maximum in-progress getImageFragment() calls")
        .isEqualTo(1);
    assertThat(gate.calls()).as("getImageFragment() calls").isEqualTo(2);
    assertThat(t1.result).as("image of caller 1").isNotNull();
    assertThat(t2.result).as("image of caller 2").isNotNull();
  }

  /** Criterion 8 ({@code [guard]}): with many more callers than threads, no decode on a caller. */
  @Test
  public void decodesNeverRunOnACallersThread() throws Exception {
    BlockingFakeReader.Gate gate = new BlockingFakeReader.Gate();
    List<ImageCaller> callers = new ArrayList<>();
    for (int i = 0; i < CALLERS; i++) {
      callers.add(caller("caller-" + i, gate));
    }
    try {
      callers.forEach(ImageCaller::start);
      assertThat(gate.awaitFirstStarted(10, TimeUnit.SECONDS))
          .as("the first getImageFragment() started")
          .isTrue();
      assertAllCallersWaitBehindTheHeldWorker(callers, gate, 10, TimeUnit.SECONDS);
    } finally {
      gate.open();
      assertThat(ImageCaller.joinAll(callers, JOIN_SECONDS, TimeUnit.SECONDS))
          .as("callers still running after the latch opened")
          .isZero();
    }

    Set<Thread> callerThreads = new HashSet<>();
    for (ImageCaller caller : callers) {
      callerThreads.add(caller.thread);
      assertThat(caller.failure).as("failure of %s", caller.thread.getName()).isNull();
      assertThat(caller.result).as("image of %s", caller.thread.getName()).isNotNull();
    }
    List<Thread> decodeThreads = gate.decodeThreads();
    assertThat(decodeThreads).as("threads that ran getImageFragment()").hasSize(CALLERS);
    for (Thread decodeThread : decodeThreads) {
      assertThat(callerThreads)
          .as("getImageFragment() ran on %s, a caller's thread", decodeThread.getName())
          .doesNotContain(decodeThread);
      assertThat(decodeThread.getName()).as("decode thread name").startsWith("Image Loader");
    }
  }

  /**
   * Waits, bounded, until the backlog of criterion 8 is in place before the latch opens, and fails
   * if it is not: the single worker is held in the one decode started so far, and every caller is
   * {@code WAITING} for its result, one for the held decode and the others for decodes not yet
   * started. A caller that ran a decode on its own thread would start a second one, so the backlog
   * would never be in place.
   */
  private static void assertAllCallersWaitBehindTheHeldWorker(
      List<ImageCaller> callers, BlockingFakeReader.Gate gate, long timeout, TimeUnit unit)
      throws InterruptedException {
    long deadline = System.nanoTime() + unit.toNanos(timeout);
    boolean inPlace = false;
    while (!inPlace && System.nanoTime() < deadline) {
      int waiting = 0;
      for (ImageCaller caller : callers) {
        ThreadInfo info = caller.threadInfo();
        if (info != null && info.getThreadState() == Thread.State.WAITING) {
          waiting++;
        }
      }
      inPlace = waiting == callers.size() && gate.calls() == 1 && gate.inProgress() == 1;
      if (!inPlace) {
        Thread.sleep(10);
      }
    }
    assertThat(inPlace)
        .as(
            "within %d %s, all %d callers WAITING while the worker is held in the one"
                + " getImageFragment() call (calls=%d, in progress=%d); callers: %s",
            timeout,
            unit,
            callers.size(),
            gate.calls(),
            gate.inProgress(),
            ImageCaller.describe(callers))
        .isTrue();
  }

  private static ImageCaller caller(String name, BlockingFakeReader.Gate gate) {
    return new ImageCaller(
        name, new ImageElement(new BlockingFakeReader(gate, new FakePlanarImage()), 0), gate);
  }
}
