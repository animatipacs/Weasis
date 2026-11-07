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

/**
 * The size of the image loader pool (FCR §2 items 1 and 2): the property {@code
 * weasis.image.loader.threads} when it is an integer of 1 or more, otherwise {@code max(2,
 * min(min(16, maxHeapMB / 100), 2 x availableProcessors))}, with a warning when a value was given
 * and rejected. The size is computed from its arguments, so no JVM is needed per value.
 */
public class ImageLoaderPoolSizeTest {

  private static final long MB = 1024L * 1024L;

  private final List<String> warnings = new ArrayList<>();

  private int size(String property, int processors, long maxHeapMB) {
    return ImageElement.imageLoaderPoolSize(property, processors, maxHeapMB * MB, warnings::add);
  }

  @Test
  public void propertyOneRestoresTheSingleThread() {
    assertThat(size("1", 8, 4096)).isEqualTo(1);
    assertThat(warnings).as("warnings").isEmpty();
  }

  @Test
  public void propertySetsTheSize() {
    assertThat(size("6", 8, 4096)).isEqualTo(6);
    assertThat(warnings).as("warnings").isEmpty();
  }

  @Test
  public void propertyBelowOneFallsBackToTheFormulaWithAWarning() {
    assertThat(size("0", 8, 4096)).isEqualTo(16);
    assertThat(warnings).as("warnings").hasSize(1);
    assertThat(warnings.get(0)).contains("weasis.image.loader.threads").contains("'0'");
  }

  @Test
  public void propertyNotAnIntegerFallsBackToTheFormulaWithAWarning() {
    assertThat(size("abc", 8, 4096)).isEqualTo(16);
    assertThat(warnings).as("warnings").hasSize(1);
    assertThat(warnings.get(0)).contains("weasis.image.loader.threads").contains("'abc'");
  }

  @Test
  public void propertyUnsetUsesTheFormula() {
    // max(2, min(min(16, 4096 / 100), 2 x 8)) = 16
    assertThat(size(null, 8, 4096)).isEqualTo(16);
    assertThat(warnings).as("warnings").isEmpty();
  }

  @Test
  public void formulaNeverGoesBelowTwo() {
    // max(2, min(min(16, 150 / 100), 2 x 1)) = max(2, 1) = 2
    assertThat(size(null, 1, 150)).isEqualTo(2);
    assertThat(warnings).as("warnings").isEmpty();
  }

  @Test
  public void heapBoundsTheSize() {
    // max(2, min(min(16, 600 / 100), 2 x 8)) = 6
    assertThat(size(null, 8, 600)).isEqualTo(6);
    assertThat(warnings).as("warnings").isEmpty();
  }

  @Test
  public void processorsBoundTheSize() {
    // max(2, min(min(16, 4096 / 100), 2 x 2)) = 4
    assertThat(size(null, 2, 4096)).isEqualTo(4);
    assertThat(warnings).as("warnings").isEmpty();
  }
}
