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

import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.weasis.core.api.explorer.model.DataExplorerModel;
import org.weasis.opencv.data.PlanarImage;

/**
 * The fake {@link MediaReader} of FCR §3: {@link #getImageFragment(MediaElement)} records the call
 * in a shared {@link Gate}, blocks until the gate's latch is released, then returns a small
 * non-null image. Like the native decoder, the wait does not end on an interrupt (the interrupt
 * status is restored when it returns); it is bounded so that a broken run fails instead of hanging.
 */
final class BlockingFakeReader implements MediaReader {

  static final long MAX_BLOCK_SECONDS = 30;

  private static final AtomicInteger IDS = new AtomicInteger();

  /** State shared by the readers of one test: the release latch and what the decodes recorded. */
  static final class Gate {
    private final CountDownLatch release = new CountDownLatch(1);
    private final CountDownLatch firstStarted = new CountDownLatch(1);
    private final List<Thread> decodeThreads = new ArrayList<>();
    private int inProgress;
    private int maxInProgress;
    private int calls;

    void open() {
      release.countDown();
    }

    boolean isOpen() {
      return release.getCount() == 0;
    }

    boolean awaitFirstStarted(long timeout, TimeUnit unit) throws InterruptedException {
      return firstStarted.await(timeout, unit);
    }

    /** Waits until {@code count} decodes are in progress at the same time. */
    synchronized boolean awaitInProgress(int count, long timeout, TimeUnit unit)
        throws InterruptedException {
      long deadline = System.nanoTime() + unit.toNanos(timeout);
      while (inProgress < count) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
          return false;
        }
        TimeUnit.NANOSECONDS.timedWait(this, remaining);
      }
      return true;
    }

    synchronized int inProgress() {
      return inProgress;
    }

    synchronized int maxInProgress() {
      return maxInProgress;
    }

    synchronized int calls() {
      return calls;
    }

    synchronized List<Thread> decodeThreads() {
      return new ArrayList<>(decodeThreads);
    }

    private synchronized void enter() {
      calls++;
      inProgress++;
      maxInProgress = Math.max(maxInProgress, inProgress);
      decodeThreads.add(Thread.currentThread());
      notifyAll();
    }

    private synchronized void exit() {
      inProgress--;
      notifyAll();
    }

    private void block() {
      boolean interrupted = false;
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(MAX_BLOCK_SECONDS);
      try {
        while (true) {
          long remaining = deadline - System.nanoTime();
          if (remaining <= 0) {
            throw new IllegalStateException(
                "The release latch was not opened within " + MAX_BLOCK_SECONDS + " s");
          }
          try {
            if (release.await(remaining, TimeUnit.NANOSECONDS)) {
              return;
            }
          } catch (InterruptedException e) {
            interrupted = true; // like the native decoder, an interrupt does not stop the decode
          }
        }
      } finally {
        if (interrupted) {
          Thread.currentThread().interrupt();
        }
      }
    }
  }

  private final URI uri;
  private final PlanarImage image;
  private final Gate gate;

  BlockingFakeReader(Gate gate, PlanarImage image) {
    this.gate = gate;
    this.image = image;
    // A distinct URI per reader keeps the elements distinct
    this.uri = URI.create("file:///fake/blocking-reader-" + IDS.incrementAndGet() + ".raw");
  }

  @Override
  public PlanarImage getImageFragment(MediaElement media) {
    gate.enter();
    try {
      gate.firstStarted.countDown();
      gate.block();
      return image;
    } finally {
      gate.exit();
    }
  }

  @Override
  public void reset() {
    // nothing to reset
  }

  @Override
  public URI getUri() {
    return uri;
  }

  @Override
  public FileCache getFileCache() {
    return null;
  }

  @Override
  public MediaElement[] getMediaElement() {
    return new MediaElement[0];
  }

  @Override
  public MediaSeries<MediaElement> getMediaSeries() {
    return null;
  }

  @Override
  public boolean delegate(DataExplorerModel explorerModel) {
    return false;
  }

  @Override
  public MediaElement getPreview() {
    return null;
  }

  @Override
  public int getMediaElementNumber() {
    return 1;
  }

  @Override
  public String getMediaFragmentMimeType() {
    return "image/x-blocking-fake"; // NON-NLS
  }

  @Override
  public Map<TagW, Object> getMediaFragmentTags(Object key) {
    return null;
  }

  @Override
  public void close() {
    // nothing to close
  }

  @Override
  public Codec getCodec() {
    return null;
  }

  @Override
  public String[] getReaderDescription() {
    return new String[0];
  }

  @Override
  public Object getTagValue(TagW tag) {
    return null;
  }

  @Override
  public void replaceURI(URI uri) {
    // the URI is fixed
  }

  @Override
  public boolean buildFile(File ouptut) {
    return false;
  }

  @Override
  public void setTag(TagW tag, Object value) {
    // no tags
  }

  @Override
  public void setTagNoNull(TagW tag, Object value) {
    // no tags
  }

  @Override
  public boolean containTagKey(TagW tag) {
    return false;
  }

  @Override
  public Iterator<Entry<TagW, Object>> getTagEntrySetIterator() {
    return Collections.emptyIterator();
  }
}
