package com.privee.app

import com.privee.app.ui.AuthProblem
import com.privee.app.ui.authProblemResource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

class LanguageResourcesTest {
    private val resources = File("src/main/res")
    private val placeholders = Regex("%(\\d+)\\$([sd])")

    private fun strings(directory: String): Map<String, Element> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File(resources, "$directory/strings.xml"))
        val nodes = document.getElementsByTagName("string")
        val entries = (0 until nodes.length).map { nodes.item(it) as Element }
        assertEquals(entries.size, entries.map { it.getAttribute("name") }.toSet().size, "Duplicate resource in $directory")
        return entries.associateBy { it.getAttribute("name") }
    }

    @Test
    fun `all languages have complete catalogs and matching typed placeholders`() {
        val defaults = strings("values").filterValues { it.getAttribute("translatable") != "false" }
        for (directory in listOf("values-it", "values-b+pt+PT", "values-es", "values-fr")) {
            val translated = strings(directory)
            assertEquals(defaults.keys, translated.keys, directory)
            for ((key, original) in defaults) {
                val text = translated.getValue(key).textContent
                assertTrue(text.isNotBlank(), "$directory/$key")
                assertEquals(
                    placeholders.findAll(original.textContent).map { it.value }.toSet(),
                    placeholders.findAll(text).map { it.value }.toSet(),
                    "$directory/$key",
                )
            }
        }
    }

    @Test
    fun `only five explicit languages are supported with European Portuguese`() {
        assertEquals(listOf("en", "it", "pt-PT", "es", "fr"), AppLanguage.entries.map { it.tag })
        assertEquals(AppLanguage.Portuguese, AppLanguage.fromTag("pt-pt"))
        assertNull(AppLanguage.fromTag("pt-BR"))
        assertNull(AppLanguage.fromTag(null))
        assertNull(AppLanguage.fromTag(""))
        assertNull(AppLanguage.fromTag("system"))
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File(resources, "xml/locales_config.xml"))
        val nodes = document.getElementsByTagName("locale")
        assertEquals(AppLanguage.entries.map { it.tag }, (0 until nodes.length).map {
            (nodes.item(it) as Element).getAttribute("android:name")
        })
    }

    @Test
    fun `application errors are resources not raw API codes`() {
        assertEquals(R.string.auth_invalid, authProblemResource(AuthProblem.InvalidCredentials))
        assertEquals(R.string.http_error, authProblemResource(AuthProblem.Http))
        assertEquals(R.string.auth_unreachable, authProblemResource(AuthProblem.Unreachable))
        assertEquals(R.string.validation_error, authProblemResource(AuthProblem.Validation))
    }
}
