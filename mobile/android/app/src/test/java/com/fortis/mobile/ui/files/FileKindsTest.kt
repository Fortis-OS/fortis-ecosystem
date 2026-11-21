package com.fortis.mobile.ui.files

import org.junit.Assert.assertEquals
import org.junit.Test

class FileKindsTest {
    @Test fun kindsFromNames() {
        assertEquals(FileKind.Folder, FileKind.of("Photos", true))
        assertEquals(FileKind.Image, FileKind.of("IMG_0001.JPG", false))
        assertEquals(FileKind.Pdf, FileKind.of("report.pdf", false))
        assertEquals(FileKind.Code, FileKind.of("main.rs", false))
        assertEquals(FileKind.Archive, FileKind.of("backup.tar.gz", false))
        assertEquals(FileKind.Other, FileKind.of("Makefile", false))
        assertEquals(FileKind.Other, FileKind.of("weird.xyz", false))
    }

    @Test fun sizes() {
        assertEquals("512 B", humanSize(512))
        assertEquals("93 KB", humanSize(93 * 1024 + 100))
        assertEquals("1.5 MB", humanSize(1_572_864))
        assertEquals("2.0 GB", humanSize(2L shl 30))
    }

    @Test fun unknownDateIsBlank() = assertEquals("", humanDate(0))
}
