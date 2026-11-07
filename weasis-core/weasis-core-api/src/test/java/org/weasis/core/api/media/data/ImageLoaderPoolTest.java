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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/**
 * FCR §3 criteria 1, 3 and 7 with a pool of 4 image loader threads. {@code IMAGE_LOADER} reads its
 * size once per JVM, so the property is set before {@link ImageElement} is loaded and Surefire runs
 * each test class in its own JVM ({@code reuseForks=false}).
 */
public class ImageLoaderPoolTest {

  static {
    System.setProperty("weasis.image.loader.threads", "4");
  }

  private static final long JOIN_SECONDS = 10;

  /** Criterion 1 ({@code [new behaviour]}): two elements decode at the same time. */
  @Test
  public void twoElementsDecodeAtTheSameTime() throws Exception {
    BlockingFakeReader.Gate gate = new BlockingFakeReader.Gate();
    ImageCaller t1 = caller("caller-1", gate);
    ImageCaller t2 = caller("caller-2", gate);
    boolean overlapped;
    boolean noneReturned;
    try {
      t1.start();
      t2.start();
      overlapped = gate.awaitInProgress(2, 5, TimeUnit.SECONDS);
      noneReturned = !t1.returned && !t2.returned;
    } finally {
      gate.open();
      assertThat(ImageCaller.joinAll(Arrays.asList(t1, t2), JOIN_SECONDS, TimeUnit.SECONDS))
          .as("callers still running after the latch opened")
          .isZero();
    }
    assertThat(overlapped)
        .as(
            "both getImageFragment() calls in progress at the same time within 5 s: the in-progress"
                + " count stayed at %d for 5 s",
            gate.maxInProgress())
        .isTrue();
    assertThat(noneReturned).as("neither getImage() returned before the overlap").isTrue();
    assertThat(t1.result).as("image of caller 1").isNotNull();
    assertThat(t2.result).as("image of caller 2").isNotNull();
  }

  /** Criterion 3 ({@code [guard]}): eight callers of one element share one decode. */
  @Test
  public void eightCallersOfOneElementShareOneDecode() throws Exception {
    BlockingFakeReader.Gate gate = new BlockingFakeReader.Gate();
    FakePlanarImage image = new FakePlanarImage();
    ImageElement element = new ImageElement(new BlockingFakeReader(gate, image), 0);
    CyclicBarrier startBarrier = new CyclicBarrier(8);
    List<ImageCaller> callers = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      callers.add(new ImageCaller("caller-" + i, element, gate, startBarrier).start());
    }
    try {
      // The first decode is in progress, so the barrier has opened for all eight
      assertThat(gate.awaitFirstStarted(JOIN_SECONDS, TimeUnit.SECONDS))
          .as("the first getImageFragment() started")
          .isTrue();
      awaitNoneRunnable(callers, 5, TimeUnit.SECONDS);
    } finally {
      gate.open();
      assertThat(ImageCaller.joinAll(callers, JOIN_SECONDS, TimeUnit.SECONDS))
          .as("callers still running after the latch opened")
          .isZero();
    }
    assertThat(gate.calls()).as("getImageFragment() calls").isEqualTo(1);
    for (ImageCaller caller : callers) {
      assertThat(caller.failure).as("failure of %s", caller.thread.getName()).isNull();
      assertThat(caller.result).as("image of %s", caller.thread.getName()).isSameAs(image);
    }
  }

  /** Criterion 7 ({@code [guard]}): an interrupted caller returns; a later call decodes. */
  @Test
  public void interruptedCallerReturnsAndALaterCallDecodes() throws Exception {
    BlockingFakeReader.Gate gate = new BlockingFakeReader.Gate();
    ImageElement element = new ImageElement(new BlockingFakeReader(gate, new FakePlanarImage()), 0);
    ImageCaller t1 = new ImageCaller("caller-interrupted", element, gate).start();
    boolean t1Ended;
    try {
      assertThat(gate.awaitFirstStarted(JOIN_SECONDS, TimeUnit.SECONDS))
          .as("getImageFragment() of caller 1 started")
          .isTrue();
      t1.thread.interrupt();
      t1Ended = t1.join(5, TimeUnit.SECONDS);
    } catch (Throwable e) {
      gate.open();
      throw e;
    }
    try {
      assertThat(t1Ended).as("the interrupted getImage() returned within 5 s").isTrue();
      assertThat(t1.failure).as("failure of the interrupted caller").isNull();
      assertThat(t1.returned).as("the interrupted getImage() returned").isTrue();
      assertThat(t1.interruptedAtReturn).as("interrupt status at return").isTrue();
      assertThat(t1.gateOpenAtReturn).as("latch open when the interrupted call returned").isFalse();
    } finally {
      gate.open();
    }

    // A later call on the same element from another thread decodes it. Its getImageFragment() may
    // overlap the abandoned one: nothing here depends on that.
    ImageCaller t2 = new ImageCaller("caller-later", element, gate).start();
    assertThat(t2.join(JOIN_SECONDS, TimeUnit.SECONDS))
        .as("the later getImage() returned")
        .isTrue();
    assertThat(t1.join(JOIN_SECONDS, TimeUnit.SECONDS)).isTrue();
    assertThat(t2.failure).as("failure of the later caller").isNull();
    assertThat(t2.result).as("image of the later call").isNotNull();
  }

  private static ImageCaller caller(String name, BlockingFakeReader.Gate gate) {
    return new ImageCaller(
        name, new ImageElement(new BlockingFakeReader(gate, new FakePlanarImage()), 0), gate);
  }

  /** Bounded wait until no caller is runnable (each waits for the decode or for the element). */
  private static void awaitNoneRunnable(List<ImageCaller> callers, long timeout, TimeUnit unit)
      throws InterruptedException {
    long deadline = System.nanoTime() + unit.toNanos(timeout);
    while (System.nanoTime() < deadline) {
      boolean anyRunnable = false;
      for (ImageCaller caller : callers) {
        if (caller.thread.getState() == Thread.State.RUNNABLE) {
          anyRunnable = true;
          break;
        }
      }
      if (!anyRunnable) {
        return;
      }
      Thread.sleep(10);
    }
  }
}
