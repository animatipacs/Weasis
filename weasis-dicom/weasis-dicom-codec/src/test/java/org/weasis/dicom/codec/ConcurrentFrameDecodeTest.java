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
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.weasis.core.api.media.data.MediaElement;
import org.weasis.dicom.codec.SyntheticDicomFiles.Syntax;
import org.weasis.opencv.data.PlanarImage;

/**
 * FCR §3 criterion 4 ({@code [new behaviour]}): the frames of a fresh multi-frame file with several
 * fragments per frame, decoded from eight threads through one {@link DicomMediaIO}, all decode with
 * the pixels of a serial decode, and {@code Cannot match all the fragments to all the frames!} is
 * never raised. Each of the 50 repetitions starts from a new reader, whose lazy fragment index is
 * built by the first decodes.
 *
 * <p>The threads call {@link DicomMediaIO#getImageFragment(MediaElement)} directly, not {@code
 * getImage()}: the race lives in the reader, and the single image loader thread of the unchanged
 * code would serialize {@code getImage()} and hide it.
 */
public class ConcurrentFrameDecodeTest {

  private static final int ROWS = 64;
  private static final int COLUMNS = 64;
  private static final int FRAMES = 8;
  private static final int FRAGMENTS_PER_FRAME = 3;
  private static final int REPETITIONS = 50;

  @ClassRule public static final TemporaryFolder FOLDER = new TemporaryFolder();

  @BeforeClass
  public static void prepare() throws Exception {
    CodecTestEnvironment.prepare();
  }

  @Test
  public void jpeg2000LosslessFramesDecodedFromEightThreadsThroughOneReader() throws Exception {
    assertFramesDecodeConcurrentlyThroughOneReader(Syntax.JPEG_2000_LOSSLESS);
  }

  @Test
  public void jpegBaselineFramesDecodedFromEightThreadsThroughOneReader() throws Exception {
    assertFramesDecodeConcurrentlyThroughOneReader(Syntax.JPEG_BASELINE);
  }

  private void assertFramesDecodeConcurrentlyThroughOneReader(Syntax syntax) throws Exception {
    File file =
        SyntheticDicomFiles.write(
            FOLDER.newFile(syntax.name() + "-multiframe.dcm"),
            syntax,
            ROWS,
            COLUMNS,
            FRAMES,
            FRAGMENTS_PER_FRAME);
    byte[][] serial = decodeSerially(file, syntax);

    int failedRepetitions = 0;
    int decodedFrames = 0;
    Map<String, Integer> failuresByKind = new TreeMap<>();
    for (int repetition = 0; repetition < REPETITIONS; repetition++) {
      List<String> failures = decodeAllFramesConcurrently(file, serial);
      if (!failures.isEmpty()) {
        failedRepetitions++;
      }
      decodedFrames += FRAMES - failures.size();
      for (String failure : failures) {
        failuresByKind.merge(failure, 1, Integer::sum);
      }
    }

    String summary =
        String.format(
            "%s, %d frames x %d fragments, %d repetitions of %d threads through one reader each:"
                + " %d repetitions failed, %d of %d frames decoded identical to the serial decode;"
                + " failures by kind %s",
            syntax,
            FRAMES,
            FRAGMENTS_PER_FRAME,
            REPETITIONS,
            FRAMES,
            failedRepetitions,
            decodedFrames,
            REPETITIONS * FRAMES,
            failuresByKind);
    System.out.println("Criterion 4: " + summary);
    assertThat(failuresByKind).as(summary).isEmpty();
  }

  /** Decodes every frame one after the other through a new reader. */
  private static byte[][] decodeSerially(File file, Syntax syntax) throws Exception {
    DicomMediaIO reader = new DicomMediaIO(file);
    try {
      MediaElement[] elements = reader.getMediaElement();
      assertThat(elements).as("elements of the %s fixture", syntax).hasSize(FRAMES);
      byte[][] frames = new byte[FRAMES][];
      for (int i = 0; i < FRAMES; i++) {
        frames[i] = decode(reader, elements[i]);
        // The fixture is valid only if a lossless frame, reassembled from its fragments, gives back
        // the generated pixels of that frame
        if (syntax.lossless) {
          assertThat(frames[i])
              .as("serial decode of frame %d of the %s fixture", i, syntax)
              .isEqualTo(SyntheticDicomFiles.pixels(ROWS, COLUMNS, i));
        } else {
          assertThat(frames[i])
              .as("serial decode of frame %d of the %s fixture", i, syntax)
              .hasSize(ROWS * COLUMNS);
        }
      }
      return frames;
    } finally {
      reader.close();
    }
  }

  /** One repetition: a new reader, each frame decoded by its own thread, all released together. */
  private static List<String> decodeAllFramesConcurrently(File file, byte[][] serial)
      throws Exception {
    DicomMediaIO reader = new DicomMediaIO(file);
    List<String> failures = Collections.synchronizedList(new ArrayList<>());
    try {
      MediaElement[] elements = reader.getMediaElement();
      CyclicBarrier start = new CyclicBarrier(FRAMES);
      List<Thread> threads = new ArrayList<>();
      for (int i = 0; i < FRAMES; i++) {
        final int frame = i;
        Thread thread =
            new Thread(
                () -> {
                  try {
                    start.await(30, TimeUnit.SECONDS);
                    byte[] pixels = decode(reader, elements[frame]);
                    if (!Arrays.equals(pixels, serial[frame])) {
                      failures.add("pixels differ from the serial decode");
                    }
                  } catch (Throwable e) {
                    failures.add(String.valueOf(e));
                  }
                },
                "frame-decoder-" + i);
        threads.add(thread);
        thread.start();
      }
      for (Thread thread : threads) {
        thread.join(TimeUnit.SECONDS.toMillis(60));
        if (thread.isAlive()) {
          failures.add(thread.getName() + " still running after 60 s");
        }
      }
    } finally {
      reader.close();
    }
    return failures;
  }

  private static byte[] decode(DicomMediaIO reader, MediaElement element) throws Exception {
    PlanarImage img = reader.getImageFragment(element);
    try {
      return SyntheticDicomFiles.bytesOf(img);
    } finally {
      if (img != null) {
        img.release();
      }
    }
  }
}
