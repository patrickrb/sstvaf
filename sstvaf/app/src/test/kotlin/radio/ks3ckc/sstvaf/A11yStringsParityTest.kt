package radio.ks3ckc.sstvaf

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Guards the accessibility-only strings added by the TalkBack semantics pass:
 * every locale must carry the same keys as the base `values/` file, or a
 * translated build silently falls back to English for spoken labels.
 *
 * Same no-Robolectric file-IO style as [LanguagePickerTest]'s parity checks:
 * the translation folders are plain files under src/main/res.
 */
class A11yStringsParityTest {
    private val a11yKeys =
        listOf(
            "a11y_close_sheet",
            "a11y_stepper_decrease",
            "a11y_stepper_increase",
            "a11y_show_password",
            "a11y_hide_password",
            "log_heatmap_description",
        )

    private val localeDirs =
        listOf(
            "values",
            "values-ar",
            "values-cs",
            "values-es",
            "values-fr",
            "values-in",
            "values-it",
            "values-ja",
            "values-ko",
            "values-nl",
            "values-pl",
            "values-ru",
            "values-tr",
            "values-uk",
            "values-zh-rCN",
            "values-zh-rTW",
        )

    @Test
    fun `every locale defines every a11y key`() {
        for (dir in localeDirs) {
            val text = resFile("$dir/strings_compose.xml").readText()
            for (key in a11yKeys) {
                if ("""<string name="$key">""" !in text) {
                    throw AssertionError("$dir/strings_compose.xml is missing <string name=\"$key\">")
                }
            }
        }
    }

    @Test
    fun `no locale defines an a11y key twice`() {
        for (dir in localeDirs) {
            val text = resFile("$dir/strings_compose.xml").readText()
            for (key in a11yKeys) {
                val needle = """<string name="$key">"""
                val count = Regex(Regex.escape(needle)).findAll(text).count()
                assertThat(count).isEqualTo(1)
            }
        }
    }

    @Test
    fun `parameterized a11y keys keep their positional placeholders in every locale`() {
        // A translation that drops or renumbers a placeholder crashes
        // getString() at runtime; check the argument sets match the base file.
        val parameterized =
            mapOf(
                "a11y_stepper_decrease" to setOf("%1\$s"),
                "a11y_stepper_increase" to setOf("%1\$s"),
                "log_heatmap_description" to setOf("%1\$d", "%2\$d"),
            )
        for (dir in localeDirs) {
            val text = resFile("$dir/strings_compose.xml").readText()
            for ((key, expected) in parameterized) {
                val match = Regex("""<string name="$key">(.*?)</string>""").find(text)
                val value = match?.groupValues?.get(1) ?: throw AssertionError("$dir missing $key")
                val found = Regex("""%\d+\$[sd]""").findAll(value).map { it.value }.toSet()
                if (found != expected) {
                    throw AssertionError("$dir/$key placeholders $found != $expected")
                }
            }
        }
    }

    /** Resolve a path under src/main/res regardless of the test working dir. */
    private fun resFile(rel: String): File {
        var dir: File? = File("").absoluteFile
        repeat(8) {
            val d = dir ?: return@repeat
            for (base in listOf(File(d, "src/main/res"), File(d, "sstvaf/app/src/main/res"))) {
                if (base.isDirectory) return File(base, rel)
            }
            dir = d.parentFile
        }
        throw IllegalStateException("Could not locate src/main/res from ${File("").absolutePath}")
    }
}
