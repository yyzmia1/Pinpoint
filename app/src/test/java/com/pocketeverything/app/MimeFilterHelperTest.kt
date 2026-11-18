package com.pocketeverything.app

import com.pocketeverything.app.picker.MimeFilterHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MimeFilterHelperTest {

    @Test
    fun `extractAcceptedMimes drops wildcard all, normalizes case and params, and merges extras`() {
        assertEquals(emptySet<String>(), MimeFilterHelper.extractAcceptedMimes("*/*", null))
        assertEquals(emptySet<String>(), MimeFilterHelper.extractAcceptedMimes("  */* ; charset=utf-8 ", arrayOf("*/*")))

        val merged = MimeFilterHelper.extractAcceptedMimes(
            primaryType = "IMAGE/*, application/pdf; qs=0.8",
            extraTypes = arrayOf(
                "text/plain; charset=utf-8",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "invalid_token",
                "*/*"
            )
        )
        assertEquals(
            setOf(
                "image/*",
                "application/pdf",
                "text/plain",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            ),
            merged
        )
    }

    @Test
    fun `matches and matchingExtensions handle category wildcards and exact types accurately`() {
        assertTrue(MimeFilterHelper.matches("pdf", emptySet()))
        assertTrue(MimeFilterHelper.matches("", emptySet()))

        val imgOnly = setOf("image/*")
        assertTrue(MimeFilterHelper.matches("JPG", imgOnly))
        assertTrue(MimeFilterHelper.matches(".webp", imgOnly))
        assertFalse(MimeFilterHelper.matches("mp4", imgOnly))
        assertFalse(MimeFilterHelper.matches("", imgOnly))
        assertTrue(MimeFilterHelper.matchingExtensions(imgOnly).containsAll(setOf("jpg", "png", "webp", "heic")))

        val docOnly = setOf(
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        )
        assertTrue(MimeFilterHelper.matches("pdf", docOnly))
        assertTrue(MimeFilterHelper.matches("DOCX", docOnly))
        assertFalse(MimeFilterHelper.matches("txt", docOnly))
        assertEquals(setOf("pdf", "docx"), MimeFilterHelper.matchingExtensions(docOnly))

        val textOnly = setOf("text/*")
        assertTrue(MimeFilterHelper.matches("md", textOnly))
        assertTrue(MimeFilterHelper.matches("kt", textOnly))
        assertFalse(MimeFilterHelper.matches("zip", textOnly))
    }

    @Test
    fun `mimeTypeForExtension and describeAcceptedMimes return expected defaults and labels`() {
        assertEquals("application/pdf", MimeFilterHelper.mimeTypeForExtension(".PDF"))
        assertEquals("application/octet-stream", MimeFilterHelper.mimeTypeForExtension("unknownxyz"))
        assertEquals("全部格式", MimeFilterHelper.describeAcceptedMimes(emptySet()))
        assertEquals("仅图片", MimeFilterHelper.describeAcceptedMimes(setOf("image/*")))
        assertEquals("仅 PDF", MimeFilterHelper.describeAcceptedMimes(setOf("application/pdf")))
        assertEquals("限定格式 (2)", MimeFilterHelper.describeAcceptedMimes(setOf("image/*", "application/pdf")))
    }
}
