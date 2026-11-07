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
import java.util.List;
import org.junit.Test;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.weasis.opencv.data.PlanarImage;

/**
 * The released-buffer guard (FCR §2 item 5): an image whose native buffer was released by a cache
 * eviction has no dimension ({@code width() <= 0}, as a released {@code Mat}). When the cache entry
 * of an element is evicted between its decode and the return of {@code getImage()}, the element is
 * decoded again, once, instead of returning the released image; when the buffer is released while
 * the minimum and maximum are computed, the element stays readable.
 */
public class ReleasedBufferTest {

  /** Evicts its own cache entry once, right after the decode put it there. */
  static class EvictedElement extends ImageElement {
    final List<ReleasableImage> decoded = new ArrayList<>();
    int type = CvType.CV_8UC1;
    boolean evictAfterDecode;

    EvictedElement() {
      super(new BlockingFakeReader(new BlockingFakeReader.Gate(), new FakePlanarImage()), 0);
    }

    @Override
    protected PlanarImage loadImage() {
      ReleasableImage image = new ReleasableImage(type);
      decoded.add(image);
      return image;
    }

    @Override
    public void setTag(TagW tag, Object value) {
      super.setTag(tag, value);
      // startImageLoading() sets this tag after it put the decoded image in the cache
      if (evictAfterDecode && TagW.ImageCache.equals(tag) && Boolean.TRUE.equals(value)) {
        evictAfterDecode = false;
        removeImageFromCache(); // the eviction releases the image
      }
    }
  }

  @Test
  public void anImageEvictedBetweenDecodeAndReturnIsDecodedAgain() {
    EvictedElement element = new EvictedElement();
    element.evictAfterDecode = true;

    PlanarImage image = element.getImage();

    assertThat(element.decoded).as("decodes").hasSize(2);
    assertThat(element.decoded.get(0).released)
        .as("first decode released by the eviction")
        .isTrue();
    assertThat(image).as("image returned").isSameAs(element.decoded.get(1));
    assertThat(image.width()).as("width of the image returned").isPositive();
    assertThat(element.isReadable()).as("element readable").isTrue();
    assertThat(element.isImageInCache()).as("second decode in the cache").isTrue();
  }

  @Test
  public void anImageReleasedWhileItsMinMaxIsComputedLeavesTheElementReadable() {
    EvictedElement element = new EvictedElement();
    // 16-bit: the minimum and maximum go through toMat(), which releases the image and fails
    element.type = CvType.CV_16UC1;

    element.getImage();

    assertThat(element.decoded).as("decodes").hasSize(1);
    assertThat(element.decoded.get(0).released).as("released while computing").isTrue();
    assertThat(element.isReadable()).as("element readable after the release").isTrue();
    assertThat(element.isImageInCache()).as("released image left in the cache").isFalse();

    element.type = CvType.CV_8UC1;
    PlanarImage image = element.getImage();
    assertThat(element.decoded).as("decodes after a later getImage()").hasSize(2);
    assertThat(image).as("image of the later getImage()").isSameAs(element.decoded.get(1));
  }

  /**
   * A 2x2 image with no native buffer whose {@link #release()} leaves it without dimension, as a
   * released {@code Mat}. A 16-bit one releases itself in {@link #toMat()}, then fails, as a buffer
   * released by a concurrent eviction while it is read.
   */
  static final class ReleasableImage implements PlanarImage {
    private final int type;
    volatile boolean released;

    ReleasableImage(int type) {
      this.type = type;
    }

    @Override
    public int channels() {
      return 1;
    }

    @Override
    public int dims() {
      return 2;
    }

    @Override
    public int depth() {
      return CvType.depth(type);
    }

    @Override
    public long elemSize() {
      return CvType.ELEM_SIZE(type);
    }

    @Override
    public long elemSize1() {
      return CvType.ELEM_SIZE(type);
    }

    @Override
    public void release() {
      released = true;
    }

    @Override
    public Size size() {
      return new Size(width(), height());
    }

    @Override
    public int type() {
      return type;
    }

    @Override
    public int height() {
      return released ? 0 : 2;
    }

    @Override
    public int width() {
      return released ? 0 : 2;
    }

    @Override
    public double[] get(int row, int column) {
      return new double[] {0};
    }

    @Override
    public int get(int i, int j, byte[] pixelData) {
      return 0;
    }

    @Override
    public int get(int i, int j, short[] data) {
      return 0;
    }

    @Override
    public int get(int i, int j, int[] data) {
      return 0;
    }

    @Override
    public int get(int i, int j, float[] data) {
      return 0;
    }

    @Override
    public int get(int i, int j, double[] data) {
      return 0;
    }

    @Override
    public void assignTo(Mat dstImg) {
      throw new UnsupportedOperationException("ReleasableImage has no native buffer");
    }

    @Override
    public Mat toMat() {
      release();
      throw new UnsupportedOperationException("ReleasableImage was released while it was read");
    }

    @Override
    public long physicalBytes() {
      return 4L * elemSize();
    }

    @Override
    public void close() {
      release();
    }
  }
}
