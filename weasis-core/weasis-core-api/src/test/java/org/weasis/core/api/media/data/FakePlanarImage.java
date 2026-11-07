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

import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.weasis.opencv.data.PlanarImage;

/**
 * A small non-null image that needs no native library: 2x2, 8-bit, one channel. The 8-bit branch
 * of {@link ImageElement#findMinMaxValues(PlanarImage, boolean)} never reaches native code, and
 * {@link #toMat()} throws so that an unexpected native path fails loudly.
 */
final class FakePlanarImage implements PlanarImage {

  private static final int SIDE = 2;

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
    return CvType.CV_8U;
  }

  @Override
  public long elemSize() {
    return 1;
  }

  @Override
  public long elemSize1() {
    return 1;
  }

  @Override
  public void release() {
    // nothing to release
  }

  @Override
  public Size size() {
    return new Size(SIDE, SIDE);
  }

  @Override
  public int type() {
    return CvType.CV_8UC1;
  }

  @Override
  public int height() {
    return SIDE;
  }

  @Override
  public int width() {
    return SIDE;
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
    throw new UnsupportedOperationException("FakePlanarImage has no native buffer");
  }

  @Override
  public Mat toMat() {
    throw new UnsupportedOperationException("FakePlanarImage has no native buffer");
  }

  @Override
  public long physicalBytes() {
    return (long) SIDE * SIDE;
  }

  @Override
  public void close() {
    // nothing to release
  }
}
