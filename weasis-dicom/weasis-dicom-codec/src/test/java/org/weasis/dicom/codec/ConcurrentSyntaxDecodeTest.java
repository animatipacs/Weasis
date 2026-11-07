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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.weasis.core.api.media.data.MediaElement;
import org.weasis.dicom.codec.SyntheticDicomFiles.Syntax;
import org.weasis.opencv.data.PlanarImage;

/**
 * FCR §3 criterion 6 ({@code [guard]}): single-frame fixtures in each listed transfer syntax,
 * decoded from eight threads for 20 rounds, each thread through its own {@link DicomImageElement}
 * and {@link DicomMediaIO}, are pixel-identical to a separate serial decode of the same file. On
 * the unchanged code the single image loader thread serializes the decodes; once the pool exists
 * this checks that the bundled native codec is thread-safe.
 */
public class ConcurrentSyntaxDecodeTest {

  private static final int ROWS = 64;
  private static final int COLUMNS = 64;
  private static final int THREADS = 8;
  private static final int ROUNDS = 20;

  @ClassRule public static final TemporaryFolder FOLDER = new TemporaryFolder();

  private static final Map<Syntax, File> FILES = new EnumMap<>(Syntax.class);
  private static final Map<Syntax, byte[]> SERIAL = new EnumMap<>(Syntax.class);

  @BeforeClass
  public static void generateFixturesAndDecodeSerially() throws Exception {
    CodecTestEnvironment.prepare();
    for (Syntax syntax : Syntax.values()) {
      File file =
          SyntheticDicomFiles.write(FOLDER.newFile(syntax.name() + ".dcm"), syntax, ROWS, COLUMNS);
      FILES.put(syntax, file);
      byte[] serial = decodeThroughElement(file);
      // The fixture is valid only if a lossless syntax gives back the generated pixels, and a
      // lossy one decodes to an image of the same size that is not the generated pixels
      byte[] generated = SyntheticDicomFiles.pixels(ROWS, COLUMNS, 0);
      if (syntax.lossless) {
        assertThat(serial)
            .as("serial decode of the generated %s fixture", syntax)
            .isEqualTo(generated);
      } else {
        assertThat(serial)
            .as("serial decode of the generated %s fixture", syntax)
            .hasSize(generated.length)
            .isNotEqualTo(generated);
      }
      SERIAL.put(syntax, serial);
    }
  }

  /** Decodes the only frame of a file through a new reader and its element's getImage(). */
  private static byte[] decodeThroughElement(File file) {
    DicomMediaIO reader = new DicomMediaIO(file);
    MediaElement[] elements = reader.getMediaElement();
    assertThat(elements).as("elements of %s", file.getName()).hasSize(1);
    DicomImageElement element = (DicomImageElement) elements[0];
    PlanarImage img = element.getImage();
    try {
      return SyntheticDicomFiles.bytesOf(img);
    } finally {
      // Evicting the entry also closes the reader and releases the image, as the cache does
      element.removeImageFromCache();
    }
  }

  @Test
  public void eachSyntaxDecodedFromEightThreadsIsPixelIdenticalToTheSerialDecode()
      throws Exception {
    List<String> failures = Collections.synchronizedList(new ArrayList<>());
    Map<Syntax, AtomicInteger> decoded = new EnumMap<>(Syntax.class);
    for (Syntax syntax : Syntax.values()) {
      decoded.put(syntax, new AtomicInteger());
    }

    for (int round = 0; round < ROUNDS; round++) {
      CyclicBarrier start = new CyclicBarrier(THREADS);
      List<Thread> threads = new ArrayList<>();
      for (int t = 0; t < THREADS; t++) {
        final int r = round;
        final int id = t;
        Thread thread =
            new Thread(
                () -> {
                  try {
                    start.await(30, TimeUnit.SECONDS);
                    for (Syntax syntax : Syntax.values()) {
                      try {
                        byte[] pixels = decodeThroughElement(FILES.get(syntax));
                        if (Arrays.equals(pixels, SERIAL.get(syntax))) {
                          decoded.get(syntax).incrementAndGet();
                        } else {
                          failures.add(
                              syntax + " round " + r + " thread " + id + ": pixels differ");
                        }
                      } catch (Throwable e) {
                        failures.add(syntax + " round " + r + " thread " + id + ": " + e);
                      }
                    }
                  } catch (Exception e) {
                    failures.add("round " + r + " thread " + id + ": " + e);
                  }
                },
                "syntax-decoder-" + t);
        threads.add(thread);
        thread.start();
      }
      for (Thread thread : threads) {
        thread.join(TimeUnit.SECONDS.toMillis(120));
        assertThat(thread.isAlive()).as("%s still running after 120 s", thread.getName()).isFalse();
      }
    }

    for (Syntax syntax : Syntax.values()) {
      System.out.println(
          "Criterion 6: " + syntax + " (" + syntax.tsuid + ") pixel-identical decodes: "
              + decoded.get(syntax).get() + "/" + (THREADS * ROUNDS));
    }
    assertThat(failures).as("decodes that differ from the serial decode, or fail").isEmpty();
    for (Syntax syntax : Syntax.values()) {
      assertThat(decoded.get(syntax).get())
          .as("%s identical decodes", syntax)
          .isEqualTo(THREADS * ROUNDS);
    }
  }
}
