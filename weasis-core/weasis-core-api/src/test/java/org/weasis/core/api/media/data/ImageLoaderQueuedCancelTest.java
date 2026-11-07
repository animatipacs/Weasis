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

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/**
 * FCR §2 item 1 and its *Concurrency contract* (queued task): when a caller whose task is still
 * queued is interrupted, the task is removed from the pool's queue, the call returns with the
 * interrupt status set and the element's loading mark is cleared, so a later call decodes it. Pool
 * size 1, so the second task stays queued behind the held worker. The property is set before
 * {@link ImageElement} is loaded; Surefire runs each test class in its own JVM ({@code
 * reuseForks=false}).
 */
public class ImageLoaderQueuedCancelTest {

  static {
    System.setProperty("weasis.image.loader.threads", "1");
  }

  private static final long JOIN_SECONDS = 10;

  @Test
  public void queuedTaskIsRemovedWhenItsCallerIsInterrupted() throws Exception {
    BlockingQueue<Runnable> queue = ((ThreadPoolExecutor) ImageElement.IMAGE_LOADER).getQueue();
    BlockingFakeReader.Gate gate = new BlockingFakeReader.Gate();
    ImageElement e1 = new ImageElement(new BlockingFakeReader(gate, new FakePlanarImage()), 0);
    ImageElement e2 = new ImageElement(new BlockingFakeReader(gate, new FakePlanarImage()), 0);
    ImageCaller t1 = new ImageCaller("caller-held", e1, gate);
    ImageCaller t2 = new ImageCaller("caller-queued", e2, gate);
    boolean t2Ended;
    int queuedBefore;
    int queuedAfter;
    boolean e2LoadingAfter;
    try {
      t1.start();
      assertThat(gate.awaitFirstStarted(JOIN_SECONDS, TimeUnit.SECONDS))
          .as("the worker is held in the decode of E1")
          .isTrue();
      t2.start();
      queuedBefore = awaitQueueSize(queue, 1, 5, TimeUnit.SECONDS);
      // E2's loading mark is not read here: isLoading() needs E2's monitor, which the caller holds
      // while it waits inside the synchronized getImage(OpManager, boolean)
      assertThat(queuedBefore).as("tasks queued behind the held worker (E2's)").isEqualTo(1);

      t2.thread.interrupt();
      t2Ended = t2.join(5, TimeUnit.SECONDS);
      queuedAfter = queue.size();
      e2LoadingAfter = e2.isLoading();
    } finally {
      gate.open();
    }
    assertThat(t2Ended).as("the interrupted getImage() returned within 5 s").isTrue();
    assertThat(t2.failure).as("failure of the interrupted caller").isNull();
    assertThat(t2.returned).as("the interrupted getImage() returned").isTrue();
    assertThat(t2.interruptedAtReturn).as("interrupt status at return").isTrue();
    assertThat(t2.gateOpenAtReturn).as("latch open when the interrupted call returned").isFalse();
    assertThat(queuedAfter).as("tasks left in the queue after the interrupt").isZero();
    assertThat(e2LoadingAfter).as("E2 still marked as loading after the interrupt").isFalse();

    assertThat(t1.join(JOIN_SECONDS, TimeUnit.SECONDS)).as("the held call returned").isTrue();
    ImageCaller t3 = new ImageCaller("caller-later", e2, gate).start();
    assertThat(t3.join(JOIN_SECONDS, TimeUnit.SECONDS))
        .as("the later getImage() returned")
        .isTrue();
    assertThat(t3.failure).as("failure of the later caller").isNull();
    assertThat(t3.result).as("image of the later call on E2").isNotNull();
  }

  /** Waits, bounded, until the queue holds {@code size} tasks; returns the last size seen. */
  private static int awaitQueueSize(
      BlockingQueue<Runnable> queue, int size, long timeout, TimeUnit unit)
      throws InterruptedException {
    long deadline = System.nanoTime() + unit.toNanos(timeout);
    while (queue.size() < size && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    return queue.size();
  }
}
