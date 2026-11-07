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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/**
 * The threads of the image loader pool (FCR §2 item 1): with {@code weasis.image.loader.threads=3},
 * three tasks run at the same time, each on a daemon thread named {@code Image Loader-<n>} with
 * {@code n >= 1}, and a fourth waits for one of them. The property is set before {@link
 * ImageElement} is loaded; Surefire runs each test class in its own JVM ({@code reuseForks=false}).
 */
public class ImageLoaderThreadsTest {

  static {
    System.setProperty("weasis.image.loader.threads", "3");
  }

  private static final int THREADS = 3;

  @Test
  public void tasksRunOnDaemonThreadsNamedImageLoaderN() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch started = new CountDownLatch(THREADS);
    CountDownLatch fourthStarted = new CountDownLatch(1);
    List<Future<Thread>> held = new ArrayList<>();
    Future<Thread> fourth;
    boolean allStarted;
    boolean fourthStartedWhileHeld;
    try {
      for (int i = 0; i < THREADS; i++) {
        held.add(
            ImageElement.IMAGE_LOADER.submit(
                () -> {
                  started.countDown();
                  release.await(30, TimeUnit.SECONDS);
                  return Thread.currentThread();
                }));
      }
      allStarted = started.await(5, TimeUnit.SECONDS);
      fourth =
          ImageElement.IMAGE_LOADER.submit(
              () -> {
                fourthStarted.countDown();
                return Thread.currentThread();
              });
      // Observation window of 500 ms with the three threads held
      fourthStartedWhileHeld = fourthStarted.await(500, TimeUnit.MILLISECONDS);
    } finally {
      release.countDown();
    }
    assertThat(allStarted).as("%d tasks running at the same time within 5 s", THREADS).isTrue();
    assertThat(fourthStartedWhileHeld)
        .as("a fourth task started while the %d pool threads were held", THREADS)
        .isFalse();

    Set<String> names = new HashSet<>();
    for (Future<Thread> future : held) {
      Thread thread = future.get(10, TimeUnit.SECONDS);
      assertThat(thread.isDaemon()).as("%s is a daemon thread", thread.getName()).isTrue();
      assertThat(thread.getName()).as("pool thread name").matches("Image Loader-[1-9][0-9]*");
      names.add(thread.getName());
    }
    assertThat(names).as("names of the pool threads that ran the held tasks").hasSize(THREADS);
    Thread fourthThread = fourth.get(10, TimeUnit.SECONDS);
    assertThat(names)
        .as("the fourth task ran on one of the pool threads")
        .contains(fourthThread.getName());
  }
}
