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
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.assertj.core.api.SoftAssertions;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.weasis.core.api.media.data.MediaElement;
import org.weasis.dicom.codec.SyntheticDicomFiles.Syntax;
import org.weasis.opencv.data.PlanarImage;

/**
 * FCR §3 criterion 5 ({@code [new behaviour]}): with one frame of a multi-frame reader being
 * decoded, {@code close()} called on the same reader from another thread does not disturb the
 * decode, and once the decode has ended the reset has run: the reader's stream is closed and its
 * parse state ({@code iis}, {@code dis}, {@code tsuid}) is cleared, as after a {@code close()} with
 * no decode in flight. The private fields are read by reflection; the reader gains no accessor.
 *
 * <p>While the decode is held, {@code close()} returns and the state the decode reads ({@code dis},
 * {@code tsuid}) must still be set: the test pins the plan's design, where decodes in flight are
 * counted around the protected {@code getImageFragment(MediaElement, int)} and a {@code reset()}
 * that arrives meanwhile is deferred until the last one ends (FCR §2 item 4 also allows a {@code
 * close()} that waits instead; that design would not return while the decode is held). {@code dis}
 * and {@code tsuid} carry the reset check: {@code iis} is null after every header read, so its
 * check holds for any implementation.
 *
 * <p>A second scenario, on a multi-frame RLE lossless reader, checks what that reset leaves behind:
 * the reader's header is no longer in the header cache, as after a {@code close()} with no decode
 * in flight, and a decode of another frame on the same reader equals its undisturbed decode.
 */
public class ReaderCloseDuringDecodeTest {

  private static final int ROWS = 64;
  private static final int COLUMNS = 64;
  private static final int FRAMES = 8;
  private static final int FRAME = 3;

  @ClassRule public static final TemporaryFolder FOLDER = new TemporaryFolder();

  private static File file;

  @BeforeClass
  public static void prepare() throws Exception {
    CodecTestEnvironment.prepare();
    file =
        SyntheticDicomFiles.write(
            FOLDER.newFile("multiframe.dcm"), Syntax.JPEG_2000_LOSSLESS, ROWS, COLUMNS, FRAMES, 3);
  }

  /**
   * A reader whose protected decode signals that it started, then waits for the test before it
   * decodes. The public {@code getImageFragment(MediaElement)} calls it, so the wait is inside the
   * decode as the reader sees it.
   */
  static class GatedDicomMediaIO extends DicomMediaIO {
    final CountDownLatch started = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);

    GatedDicomMediaIO(File source) {
      super(source);
    }

    @Override
    protected PlanarImage getImageFragment(MediaElement media, int frame) throws Exception {
      started.countDown();
      if (!release.await(30, TimeUnit.SECONDS)) {
        throw new IllegalStateException("The test did not release the decode within 30 s");
      }
      return super.getImageFragment(media, frame);
    }
  }

  @Test
  public void closeDuringADecodeKeepsItsPixelsAndResetsTheReaderAfterIt() throws Exception {
    byte[] undisturbed = undisturbedDecodeOfFrame();

    GatedDicomMediaIO reader = new GatedDicomMediaIO(file);
    MediaElement[] elements = reader.getMediaElement();
    assertThat(elements).hasSize(FRAMES);

    AtomicReference<byte[]> decoded = new AtomicReference<>();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread decoder =
        new Thread(
            () -> {
              try {
                PlanarImage img = reader.getImageFragment(elements[FRAME]);
                try {
                  decoded.set(SyntheticDicomFiles.bytesOf(img));
                } finally {
                  if (img != null) {
                    img.release();
                  }
                }
              } catch (Throwable e) {
                failure.set(e);
              }
            },
            "frame-decoder");
    Thread closer = new Thread(reader::close, "reader-closer");
    try {
      decoder.start();
      assertThat(reader.started.await(10, TimeUnit.SECONDS))
          .as("the decode of frame %d started", FRAME)
          .isTrue();
      assertThat(new Object[] {field(reader, "dis"), field(reader, "tsuid")})
          .as("dis and tsuid, which the decode reads, set before close() is called")
          .doesNotContainNull();
      closer.start();
      closer.join(TimeUnit.SECONDS.toMillis(10));
      assertThat(closer.isAlive()).as("close() returned within 10 s").isFalse();
      Object disDuring = field(reader, "dis");
      Object tsuidDuring = field(reader, "tsuid");
      assertThat(new Object[] {disDuring, tsuidDuring})
          .as(
              "close() returned while the decode of frame %d was held and did not clear the state"
                  + " the decode reads: dis=%s, tsuid=%s",
              FRAME,
              disDuring,
              tsuidDuring)
          .doesNotContainNull();
    } finally {
      reader.release.countDown();
      decoder.join(TimeUnit.SECONDS.toMillis(30));
    }
    assertThat(decoder.isAlive()).as("the decode ended within 30 s").isFalse();

    assertThat(failure.get()).as("failure of the decode during close()").isNull();
    assertThat(decoded.get())
        .as("frame %d decoded while close() ran equals an undisturbed decode", FRAME)
        .isEqualTo(undisturbed);

    Object iis = field(reader, "iis");
    Object dis = field(reader, "dis");
    Object tsuid = field(reader, "tsuid");
    assertThat(new Object[] {iis, dis, tsuid})
        .as(
            "after the decode ended, the reset of the close() called during it has run, as after a"
                + " close() with no decode in flight: iis=%s, dis=%s, tsuid=%s",
            iis,
            dis,
            tsuid)
        .containsOnlyNulls();
  }

  /**
   * The same close() during a decode, on a multi-frame RLE lossless reader, where the next decode
   * depends on the transfer syntax the reader holds: once the held decode has ended, the reset of
   * the close() has run as after a close() with no decode in flight, which includes the removal of
   * the reader's header from the header cache, and a decode of another frame on the same reader
   * equals its undisturbed decode. A close() reaches a reader whose other frames are still in use
   * when the image cache evicts one of its frames.
   */
  @Test
  public void closeDuringADecodeLeavesTheReaderReadyToDecodeAnotherFrame() throws Exception {
    File rle =
        SyntheticDicomFiles.write(
            FOLDER.newFile("multiframe-rle.dcm"), Syntax.RLE_LOSSLESS, ROWS, COLUMNS, FRAMES, 1);
    int nextFrame = FRAME + 1;
    byte[] undisturbedNext = undisturbedDecodeOfFrame(rle, nextFrame);

    GatedDicomMediaIO reader = new GatedDicomMediaIO(rle);
    MediaElement[] elements = reader.getMediaElement();
    assertThat(elements).hasSize(FRAMES);

    AtomicReference<byte[]> decoded = new AtomicReference<>();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread decoder =
        new Thread(
            () -> {
              try {
                decoded.set(decode(reader, elements[FRAME]));
              } catch (Throwable e) {
                failure.set(e);
              }
            },
            "rle-frame-decoder");
    Thread closer = new Thread(reader::close, "rle-reader-closer");
    try {
      decoder.start();
      assertThat(reader.started.await(10, TimeUnit.SECONDS))
          .as("the decode of RLE frame %d started", FRAME)
          .isTrue();
      closer.start();
      closer.join(TimeUnit.SECONDS.toMillis(10));
      assertThat(closer.isAlive()).as("close() returned within 10 s").isFalse();
    } finally {
      reader.release.countDown();
      decoder.join(TimeUnit.SECONDS.toMillis(30));
    }
    assertThat(decoder.isAlive()).as("the decode ended within 30 s").isFalse();
    assertThat(failure.get()).as("failure of the decode during close()").isNull();
    assertThat(decoded.get())
        .as("RLE frame %d decoded while close() ran equals an undisturbed decode", FRAME)
        .isEqualTo(SyntheticDicomFiles.pixels(ROWS, COLUMNS, FRAME));

    boolean headerCachedAfter = headerCache().containsKey(reader);
    Object tsuidAfter = field(reader, "tsuid");
    byte[] next = decode(reader, elements[nextFrame]);

    SoftAssertions softly = new SoftAssertions();
    softly
        .assertThat(next)
        .as(
            "RLE frame %d decoded on the same reader after the decode held during close() ended"
                + " (tsuid=%s then) equals its undisturbed decode",
            nextFrame,
            tsuidAfter)
        .isEqualTo(undisturbedNext);
    softly
        .assertThat(headerCachedAfter)
        .as(
            "the reader's header is still in the header cache after the decode held during close()"
                + " ended, unlike after a close() with no decode in flight")
        .isFalse();
    softly.assertAll();
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

  /**
   * The undisturbed decode of a frame of the given file, through a plain reader that is then closed
   * with no decode in flight; checks that the close() removed the reader's header from the cache.
   */
  private static byte[] undisturbedDecodeOfFrame(File source, int frame) throws Exception {
    DicomMediaIO reader = new DicomMediaIO(source);
    byte[] pixels = decode(reader, reader.getMediaElement()[frame]);
    reader.close();
    assertThat(pixels)
        .as("undisturbed decode of frame %d of %s", frame, source.getName())
        .isEqualTo(SyntheticDicomFiles.pixels(ROWS, COLUMNS, frame));
    assertThat(headerCache().containsKey(reader))
        .as("header cached after a close() with no decode in flight")
        .isFalse();
    return pixels;
  }

  private static Map<?, ?> headerCache() throws Exception {
    Field field = DicomMediaIO.class.getDeclaredField("HEADER_CACHE");
    field.setAccessible(true);
    return (Map<?, ?>) field.get(null);
  }

  /**
   * The undisturbed decode of the frame, through a plain reader that is then closed with no decode
   * in flight: the reference for the pixels and for the state a close() leaves.
   */
  private static byte[] undisturbedDecodeOfFrame() throws Exception {
    DicomMediaIO reader = new DicomMediaIO(file);
    byte[] pixels;
    PlanarImage img = reader.getImageFragment(reader.getMediaElement()[FRAME]);
    try {
      pixels = SyntheticDicomFiles.bytesOf(img);
    } finally {
      img.release();
    }
    reader.close();
    assertThat(pixels)
        .as("undisturbed decode of frame %d", FRAME)
        .isEqualTo(SyntheticDicomFiles.pixels(ROWS, COLUMNS, FRAME));
    assertThat(new Object[] {field(reader, "iis"), field(reader, "dis"), field(reader, "tsuid")})
        .as("iis, dis and tsuid after a close() with no decode in flight")
        .containsOnlyNulls();
    return pixels;
  }

  private static Object field(DicomMediaIO reader, String name) throws Exception {
    Field field = DicomMediaIO.class.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(reader);
  }
}
