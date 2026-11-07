/*
 * Copyright (c) 2009-2020 Weasis Team and other contributors.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at https://www.eclipse.org/legal/epl-2.0, or the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package org.weasis.dicom.codec;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.ref.SoftReference;
import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.imageio.plugins.dcm.DicomMetaData;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.weasis.core.api.media.data.SoftHashMap;
import org.weasis.dicom.codec.SyntheticDicomFiles.Syntax;

/**
 * The header cache of {@link DicomMediaIO} is shared by every reader: decodes on the image loader
 * threads read and cache headers while the image cache's evictions close readers, which removes
 * their headers, and the soft references' clearing evicts headers. Its maps are plain hash maps, so
 * every method of the cache that reads or changes them holds the cache's monitor.
 *
 * <p>Each test starts an operation on another thread while the test holds that monitor: the
 * operation must wait for the monitor (its thread is blocked on that very object), then complete
 * once the test releases it. Without the lock, the operation completes while the monitor is held.
 */
public class HeaderCacheLockTest {

  private static final long WAIT_MILLIS = TimeUnit.SECONDS.toMillis(5);

  @ClassRule public static final TemporaryFolder FOLDER = new TemporaryFolder();

  private static File file;

  @BeforeClass
  public static void prepare() throws Exception {
    CodecTestEnvironment.prepare();
    file =
        SyntheticDicomFiles.write(
            FOLDER.newFile("header.dcm"), Syntax.EXPLICIT_VR_LITTLE_ENDIAN, 16, 16);
  }

  /** An operation on the header cache, run on another thread. */
  private interface Operation {
    void run() throws Exception;
  }

  @Test
  public void readingAHeaderWaitsForTheCacheMonitor() throws Exception {
    DicomMediaIO reader = new DicomMediaIO(file);
    try {
      assertWaitsForTheCacheMonitor("reading the header of a reader", reader::getMediaElement);
      assertThat(headerCache().containsKey(reader)).as("the header read is cached").isTrue();
    } finally {
      reader.close();
    }
  }

  @Test
  public void closingAReaderWaitsForTheCacheMonitor() throws Exception {
    DicomMediaIO reader = new DicomMediaIO(file);
    reader.getMediaElement();
    assertThat(headerCache().containsKey(reader)).as("the header read is cached").isTrue();
    assertWaitsForTheCacheMonitor("closing a reader", reader::close);
    assertThat(headerCache().containsKey(reader)).as("the header cached after close()").isFalse();
  }

  @Test
  public void everyMethodOfTheCacheWaitsForItsMonitor() throws Exception {
    SoftHashMap<DicomMediaIO, DicomMetaData> cache = headerCache();
    DicomMediaIO key = new DicomMediaIO(file);
    DicomMetaData header = new DicomMetaData(null, new Attributes());
    Object absent = new Object();
    assertWaitsForTheCacheMonitor("put()", () -> cache.put(key, header));
    assertWaitsForTheCacheMonitor("get()", () -> cache.get(key));
    assertWaitsForTheCacheMonitor("containsKey()", () -> cache.containsKey(absent));
    assertWaitsForTheCacheMonitor("size()", cache::size);
    assertWaitsForTheCacheMonitor("entrySet()", cache::entrySet);
    assertWaitsForTheCacheMonitor("remove()", () -> cache.remove(key));
    assertWaitsForTheCacheMonitor(
        "removeElement()", () -> cache.removeElement(new SoftReference<>(header)));
    assertWaitsForTheCacheMonitor("expungeStaleEntries()", cache::expungeStaleEntries);
    assertWaitsForTheCacheMonitor("clear()", cache::clear);
  }

  /**
   * Starts {@code operation} on another thread while this thread holds the header cache's monitor,
   * and checks that it waits for that monitor and completes once it is released.
   */
  private static void assertWaitsForTheCacheMonitor(String what, Operation operation)
      throws Exception {
    Object cache = headerCache();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread caller =
        new Thread(
            () -> {
              try {
                operation.run();
              } catch (Throwable e) {
                failure.set(e);
              }
            },
            "header-cache-caller");
    boolean waited;
    synchronized (cache) {
      caller.start();
      waited = blockedOn(caller, cache);
    }
    caller.join(WAIT_MILLIS);
    assertThat(caller.isAlive())
        .as("%s completed within 5 s once the cache's monitor was released", what)
        .isFalse();
    assertThat(failure.get()).as("failure of %s", what).isNull();
    assertThat(waited)
        .as("%s waited for the header cache's monitor while another thread held it", what)
        .isTrue();
  }

  /**
   * Whether {@code thread} blocks on the monitor of {@code lock} within 5 s; false as soon as the
   * thread ends without blocking on it.
   */
  private static boolean blockedOn(Thread thread, Object lock) throws InterruptedException {
    int identity = System.identityHashCode(lock);
    long deadline = System.currentTimeMillis() + WAIT_MILLIS;
    while (System.currentTimeMillis() < deadline) {
      if (thread.getState() == Thread.State.BLOCKED) {
        ThreadInfo info = ManagementFactory.getThreadMXBean().getThreadInfo(thread.getId());
        LockInfo lockInfo = info == null ? null : info.getLockInfo();
        if (lockInfo != null && lockInfo.getIdentityHashCode() == identity) {
          return true;
        }
      } else if (thread.getState() == Thread.State.TERMINATED) {
        return false;
      }
      Thread.sleep(1);
    }
    return false;
  }

  @SuppressWarnings("unchecked")
  private static SoftHashMap<DicomMediaIO, DicomMetaData> headerCache() throws Exception {
    Field field = DicomMediaIO.class.getDeclaredField("HEADER_CACHE");
    field.setAccessible(true);
    return (SoftHashMap<DicomMediaIO, DicomMetaData>) field.get(null);
  }
}
