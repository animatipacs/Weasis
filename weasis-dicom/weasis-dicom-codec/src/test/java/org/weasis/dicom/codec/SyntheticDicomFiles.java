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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Fragments;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.data.VR;
import org.dcm4che3.io.DicomOutputStream;
import org.dcm4che3.util.UIDUtils;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfInt;
import org.opencv.imgcodecs.Imgcodecs;
import org.weasis.opencv.data.PlanarImage;

/**
 * Writes synthetic DICOM files for the decoding tests: 8-bit MONOCHROME2 gradient pixels, generated
 * UIDs, no person attributes. Compressed frames come from the bundled native encoder ({@link
 * Imgcodecs#dicomJpgWrite}); RLE from {@link #rleEncode}, a PackBits encoder (DICOM PS3.5 Annex
 * G), since dcm4che 5.25.1 ships no RLE writer. Needs {@link CodecTestEnvironment#prepare()} first.
 */
final class SyntheticDicomFiles {

  /** The transfer syntaxes of FCR §3 criterion 6. */
  enum Syntax {
    EXPLICIT_VR_LITTLE_ENDIAN(UID.ExplicitVRLittleEndian, true),
    DEFLATED(UID.DeflatedExplicitVRLittleEndian, true),
    RLE_LOSSLESS(UID.RLELossless, true),
    JPEG_BASELINE(UID.JPEGBaseline8Bit, false),
    JPEG_LOSSLESS(UID.JPEGLosslessSV1, true),
    JPEG_LS_LOSSLESS(UID.JPEGLSLossless, true),
    JPEG_LS_NEAR_LOSSLESS(UID.JPEGLSNearLossless, false),
    JPEG_2000_LOSSLESS(UID.JPEG2000Lossless, true),
    JPEG_2000_LOSSY(UID.JPEG2000, false);

    final String tsuid;
    final boolean lossless;

    Syntax(String tsuid, boolean lossless) {
      this.tsuid = tsuid;
      this.lossless = lossless;
    }

    boolean encapsulated() {
      return this != EXPLICIT_VR_LITTLE_ENDIAN && this != DEFLATED;
    }
  }

  private static final String SECONDARY_CAPTURE = "1.2.840.10008.5.1.4.1.1.7";
  private static final String MULTIFRAME_GRAYSCALE_BYTE_SECONDARY_CAPTURE =
      "1.2.840.10008.5.1.4.1.1.7.2";

  // Parameter indexes of Imgcodecs.dicomJpgWrite (Imgcodecs.DICOM_PARAM_*)
  private static final int COLOR_MODEL_MONOCHROME2 = 3;
  private static final int INTERLEAVE_SAMPLE = 2;

  private SyntheticDicomFiles() {}

  /** Synthetic pixels: a flat band (RLE replicate runs), then a gradient that differs per frame. */
  static byte[] pixels(int rows, int columns, int frame) {
    byte[] data = new byte[rows * columns];
    for (int y = 0; y < rows; y++) {
      for (int x = 0; x < columns; x++) {
        int value = x < 8 ? 40 + frame * 10 : x * 3 + y * 5 + frame * 29;
        data[y * columns + x] = (byte) value;
      }
    }
    return data;
  }

  /** Writes a single-frame file in the given syntax. */
  static File write(File file, Syntax syntax, int rows, int columns) throws IOException {
    return write(file, syntax, rows, columns, 1, 1);
  }

  /**
   * Writes a file of {@code frames} frames. An encapsulated syntax gets an empty basic offset table
   * and each frame split into {@code fragmentsPerFrame} fragments, only the first of which starts
   * with the codestream's start marker.
   */
  static File write(
      File file, Syntax syntax, int rows, int columns, int frames, int fragmentsPerFrame)
      throws IOException {
    Attributes ds = header(rows, columns, frames);
    if (syntax.encapsulated()) {
      Fragments fragments = ds.newFragments(Tag.PixelData, VR.OB, 1 + frames * fragmentsPerFrame);
      fragments.add(new byte[0]); // empty basic offset table
      for (int f = 0; f < frames; f++) {
        byte[] frame = encode(syntax, pixels(rows, columns, f), rows, columns);
        for (byte[] fragment : split(syntax, frame, fragmentsPerFrame)) {
          fragments.add(fragment);
        }
      }
    } else {
      ByteArrayOutputStream all = new ByteArrayOutputStream();
      for (int f = 0; f < frames; f++) {
        byte[] frame = pixels(rows, columns, f);
        all.write(frame, 0, frame.length);
      }
      if (all.size() % 2 != 0) {
        all.write(0);
      }
      ds.setBytes(Tag.PixelData, VR.OB, all.toByteArray());
    }
    try (DicomOutputStream out = new DicomOutputStream(file)) {
      out.writeDataset(ds.createFileMetaInformation(syntax.tsuid), ds);
    }
    return file;
  }

  private static Attributes header(int rows, int columns, int frames) {
    Attributes ds = new Attributes();
    ds.setString(
        Tag.SOPClassUID,
        VR.UI,
        frames > 1 ? MULTIFRAME_GRAYSCALE_BYTE_SECONDARY_CAPTURE : SECONDARY_CAPTURE);
    ds.setString(Tag.SOPInstanceUID, VR.UI, UIDUtils.createUID());
    ds.setString(Tag.StudyInstanceUID, VR.UI, UIDUtils.createUID());
    ds.setString(Tag.SeriesInstanceUID, VR.UI, UIDUtils.createUID());
    ds.setString(Tag.Modality, VR.CS, "OT");
    ds.setInt(Tag.SeriesNumber, VR.IS, 1);
    ds.setInt(Tag.InstanceNumber, VR.IS, 1);
    if (frames > 1) {
      ds.setInt(Tag.NumberOfFrames, VR.IS, frames);
    }
    ds.setInt(Tag.SamplesPerPixel, VR.US, 1);
    ds.setString(Tag.PhotometricInterpretation, VR.CS, "MONOCHROME2");
    ds.setInt(Tag.Rows, VR.US, rows);
    ds.setInt(Tag.Columns, VR.US, columns);
    ds.setInt(Tag.BitsAllocated, VR.US, 8);
    ds.setInt(Tag.BitsStored, VR.US, 8);
    ds.setInt(Tag.HighBit, VR.US, 7);
    ds.setInt(Tag.PixelRepresentation, VR.US, 0);
    return ds;
  }

  /** One frame, compressed in the given encapsulated syntax. */
  static byte[] encode(Syntax syntax, byte[] frame, int rows, int columns) {
    if (syntax == Syntax.RLE_LOSSLESS) {
      return rleEncode(frame, rows, columns);
    }
    int[] params = new int[16];
    params[Imgcodecs.DICOM_PARAM_IMREAD] = Imgcodecs.IMREAD_UNCHANGED;
    params[Imgcodecs.DICOM_PARAM_DCM_IMREAD] = Imgcodecs.DICOM_FLAG_UNSIGNED;
    params[Imgcodecs.DICOM_PARAM_WIDTH] = columns;
    params[Imgcodecs.DICOM_PARAM_HEIGHT] = rows;
    params[Imgcodecs.DICOM_PARAM_COMPONENTS] = 1;
    params[Imgcodecs.DICOM_PARAM_BITS_PER_SAMPLE] = 8;
    params[Imgcodecs.DICOM_PARAM_INTERLEAVE_MODE] = INTERLEAVE_SAMPLE;
    params[Imgcodecs.DICOM_PARAM_COLOR_MODEL] = COLOR_MODEL_MONOCHROME2;
    switch (syntax) {
      case JPEG_BASELINE:
        params[Imgcodecs.DICOM_PARAM_COMPRESSION] = Imgcodecs.DICOM_CP_JPG;
        params[Imgcodecs.DICOM_PARAM_JPEG_MODE] = Imgcodecs.JPEG_baseline;
        params[Imgcodecs.DICOM_PARAM_JPEG_QUALITY] = 75;
        break;
      case JPEG_LOSSLESS:
        params[Imgcodecs.DICOM_PARAM_COMPRESSION] = Imgcodecs.DICOM_CP_JPG;
        params[Imgcodecs.DICOM_PARAM_JPEG_MODE] = Imgcodecs.JPEG_lossless;
        params[Imgcodecs.DICOM_PARAM_JPEG_PREDICTION] = 1;
        params[Imgcodecs.DICOM_PARAM_JPEG_PT_TRANSFORM] = 0;
        break;
      case JPEG_LS_LOSSLESS:
        params[Imgcodecs.DICOM_PARAM_COMPRESSION] = Imgcodecs.DICOM_CP_JPLS;
        params[Imgcodecs.DICOM_PARAM_JPEGLS_LOSSY_ERROR] = 0;
        break;
      case JPEG_LS_NEAR_LOSSLESS:
        params[Imgcodecs.DICOM_PARAM_COMPRESSION] = Imgcodecs.DICOM_CP_JPLS;
        params[Imgcodecs.DICOM_PARAM_JPEGLS_LOSSY_ERROR] = 3;
        break;
      case JPEG_2000_LOSSLESS:
        params[Imgcodecs.DICOM_PARAM_COMPRESSION] = Imgcodecs.DICOM_CP_J2K;
        params[Imgcodecs.DICOM_PARAM_J2K_COMPRESSION_FACTOR] = 0;
        break;
      case JPEG_2000_LOSSY:
        params[Imgcodecs.DICOM_PARAM_COMPRESSION] = Imgcodecs.DICOM_CP_J2K;
        params[Imgcodecs.DICOM_PARAM_J2K_COMPRESSION_FACTOR] = 10;
        break;
      default:
        throw new IllegalArgumentException("Not an encoded syntax: " + syntax);
    }
    Mat source = new Mat(rows, columns, CvType.CV_8UC1);
    Mat encoded = null;
    try {
      source.put(0, 0, frame);
      encoded = Imgcodecs.dicomJpgWrite(source, new MatOfInt(params), "");
      if (encoded.empty()) {
        throw new IllegalStateException("The native encoder returned nothing for " + syntax);
      }
      byte[] bytes = new byte[(int) (encoded.total() * encoded.elemSize())];
      encoded.get(0, 0, bytes);
      checkFrameMarker(syntax, bytes);
      return bytes;
    } finally {
      source.release();
      if (encoded != null) {
        encoded.release();
      }
    }
  }

  /** Checks that the encoder produced the codestream the syntax names (its start of frame). */
  private static void checkFrameMarker(Syntax syntax, byte[] frame) {
    int sof;
    switch (syntax) {
      case JPEG_BASELINE:
        sof = 0xC0; // SOF0, baseline DCT
        break;
      case JPEG_LOSSLESS:
        sof = 0xC3; // SOF3, lossless
        break;
      case JPEG_LS_LOSSLESS:
      case JPEG_LS_NEAR_LOSSLESS:
        sof = 0xF7; // SOF55, JPEG-LS
        break;
      default:
        if (!startsLikeAFrame(syntax, frame, 0)) {
          throw new IllegalStateException("The encoded frame does not start with SOC: " + syntax);
        }
        return;
    }
    if (!startsLikeAFrame(syntax, frame, 0)) {
      throw new IllegalStateException("The encoded frame does not start with SOI: " + syntax);
    }
    for (int i = 2; i + 1 < frame.length; i++) {
      if ((frame[i] & 0xFF) == 0xFF && (frame[i + 1] & 0xFF) == sof) {
        return;
      }
    }
    throw new IllegalStateException(
        "The encoded frame has no start-of-frame marker 0xFF"
            + Integer.toHexString(sof)
            + ": "
            + syntax);
  }

  /**
   * Splits a frame's codestream into {@code count} fragments of even length (only the last one is
   * padded), so that only the first fragment starts with the start marker the reader's fragment
   * index looks for.
   */
  static List<byte[]> split(Syntax syntax, byte[] frame, int count) {
    if (count == 1) {
      return Collections.singletonList(Arrays.copyOf(frame, frame.length + frame.length % 2));
    }
    if (syntax == Syntax.RLE_LOSSLESS) {
      throw new IllegalArgumentException("An RLE frame is written as one fragment");
    }
    List<byte[]> fragments = new ArrayList<>(count);
    int start = 0;
    for (int i = 1; i < count; i++) {
      int cut = Math.max(start + 2, ((frame.length * i) / count) & ~1);
      while (cut < frame.length - 2 && startsLikeAFrame(syntax, frame, cut)) {
        cut += 2;
      }
      if (cut >= frame.length) {
        throw new IllegalStateException("Frame too short to split in " + count + ": " + syntax);
      }
      fragments.add(Arrays.copyOfRange(frame, start, cut));
      start = cut;
    }
    byte[] last = Arrays.copyOfRange(frame, start, frame.length + (frame.length - start) % 2);
    fragments.add(last);
    if (!startsLikeAFrame(syntax, fragments.get(0), 0)) {
      throw new IllegalStateException(
          "The encoded frame does not start with its marker: " + syntax);
    }
    return fragments;
  }

  private static boolean startsLikeAFrame(Syntax syntax, byte[] data, int offset) {
    int b1 = data[offset] & 0xFF;
    int b2 = data[offset + 1] & 0xFF;
    if (syntax == Syntax.JPEG_2000_LOSSLESS || syntax == Syntax.JPEG_2000_LOSSY) {
      return (b1 == 0xFF && b2 == 0x4F) || (b1 == 0 && b2 == 0); // SOC, or a JP2 signature box
    }
    return b1 == 0xFF && b2 == 0xD8; // SOI
  }

  /** RLE Lossless (DICOM PS3.5 Annex G) of one 8-bit, one-sample frame: one segment. */
  static byte[] rleEncode(byte[] frame, int rows, int columns) {
    ByteArrayOutputStream segment = new ByteArrayOutputStream();
    for (int y = 0; y < rows; y++) {
      packBitsRow(frame, y * columns, columns, segment);
    }
    if (segment.size() % 2 != 0) {
      segment.write(0);
    }
    byte[] header = new byte[64];
    putIntLE(header, 0, 1); // number of segments
    putIntLE(header, 4, 64); // offset of segment 1
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(header, 0, header.length);
    byte[] data = segment.toByteArray();
    out.write(data, 0, data.length);
    return out.toByteArray();
  }

  private static void packBitsRow(byte[] src, int offset, int length, ByteArrayOutputStream out) {
    int i = 0;
    while (i < length) {
      int run = 1;
      while (i + run < length && run < 128 && src[offset + i + run] == src[offset + i]) {
        run++;
      }
      if (run >= 2) {
        out.write(1 - run); // replicate run: -(n - 1)
        out.write(src[offset + i]);
        i += run;
      } else {
        int literal = 1;
        while (i + literal < length
            && literal < 128
            && !(i + literal + 1 < length
                && src[offset + i + literal] == src[offset + i + literal + 1])) {
          literal++;
        }
        out.write(literal - 1); // literal run: n - 1
        out.write(src, offset + i, literal);
        i += literal;
      }
    }
  }

  private static void putIntLE(byte[] b, int off, int v) {
    b[off] = (byte) v;
    b[off + 1] = (byte) (v >>> 8);
    b[off + 2] = (byte) (v >>> 16);
    b[off + 3] = (byte) (v >>> 24);
  }

  /** The decoded pixels of an 8-bit image, row by row. */
  static byte[] bytesOf(PlanarImage img) {
    if (img == null) {
      throw new AssertionError("The decode returned no image");
    }
    if (CvType.depth(img.type()) != CvType.CV_8U) {
      throw new AssertionError(
          "Expected an 8-bit image, got type " + CvType.typeToString(img.type()));
    }
    byte[] data = new byte[img.width() * img.height() * img.channels()];
    img.get(0, 0, data);
    return data;
  }
}
