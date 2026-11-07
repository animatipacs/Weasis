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

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import org.opencv.core.Core;

/**
 * Prepares a test JVM to decode DICOM outside an OSGi framework:
 *
 * <ul>
 *   <li>{@code BundleTools}, reached from {@code DicomMediaIO.isReadableDicom()} through {@code
 *       PatientComparator}, reads {@code weasis.pref.dir} and {@code weasis.resources.path} in its
 *       static initializer from the bundle context, or else from the system properties. With no
 *       framework they are given as system properties pointing at a temporary folder.
 *   <li>The native codec (opencv_java) that a platform profile of this module's POM copied into
 *       {@code target/native} is loaded. A platform without a profile fails the calling test: a
 *       decoding guard that is skipped is not evidence.
 * </ul>
 */
final class CodecTestEnvironment {

  /** Set by the platform profiles of {@code weasis-dicom-codec/pom.xml} to the library file. */
  static final String NATIVE_CODEC_PROPERTY = "weasis.test.native.codec";

  private static String loadedPath;

  private CodecTestEnvironment() {}

  /** @return the absolute path of the native codec loaded in this JVM */
  static synchronized String prepare() throws IOException {
    if (loadedPath == null) {
      setFrameworkPropertiesIfAbsent();
      loadedPath = loadNativeCodec();
    }
    return loadedPath;
  }

  private static void setFrameworkPropertiesIfAbsent() throws IOException {
    if (System.getProperty("weasis.pref.dir") == null
        || System.getProperty("weasis.resources.path") == null) {
      File dir = Files.createTempDirectory("weasis-codec-test").toFile();
      dir.deleteOnExit();
      if (System.getProperty("weasis.pref.dir") == null) {
        System.setProperty("weasis.pref.dir", dir.getAbsolutePath());
      }
      if (System.getProperty("weasis.resources.path") == null) {
        System.setProperty("weasis.resources.path", dir.getAbsolutePath());
      }
    }
  }

  private static String loadNativeCodec() {
    String platform =
        "os.name=" + System.getProperty("os.name") + ", os.arch=" + System.getProperty("os.arch");
    String path = System.getProperty(NATIVE_CODEC_PROPERTY);
    if (path == null || path.trim().isEmpty() || path.contains("${")) {
      throw new AssertionError(
          "No native codec profile of weasis-dicom-codec/pom.xml matched this platform ("
              + platform
              + "): the decoding tests cannot run here");
    }
    File library = new File(path);
    if (!library.isFile()) {
      throw new AssertionError(
          "The native codec is missing at " + library.getAbsolutePath() + " (" + platform + ")");
    }
    System.load(library.getAbsolutePath());
    System.out.println(
        "Native codec loaded: "
            + library.getAbsolutePath()
            + " (OpenCV "
            + Core.VERSION
            + ", "
            + platform
            + ")");
    return library.getAbsolutePath();
  }
}
