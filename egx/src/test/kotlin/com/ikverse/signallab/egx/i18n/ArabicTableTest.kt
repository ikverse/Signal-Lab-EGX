package com.ikverse.signallab.egx.i18n

import com.ikverse.signallab.egx.view.PAGES
import com.ikverse.signallab.engine.egx.EgxStrategies
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals

/**
 * Every text of the app has its Arabic row (item 37): the literal English of every `t("...")` in the sources, the
 * strategies' names and one-line descriptions, the pages of the menu, and the counted parts of Today's sentence.
 */
class ArabicTableTest {
    private val table = Tr.parse(File("src/main/assets/egx/strings_ar.tsv").readText(Charsets.UTF_8))

    /** Every English text the app can show. */
    fun texts(): Set<String> {
        val call = Regex("\\bt\\(\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
        val out = HashSet<String>()
        File("src/main/kotlin").walkTopDown().filter { it.extension == "kt" }.forEach { f ->
            for (m in call.findAll(f.readText(Charsets.UTF_8))) {
                val s = m.groupValues[1].replace("\\$", "$").replace("\\\"", "\"").replace("\\n", "\n")
                if ("\$verb" !in s) out.add(s)
            }
        }
        for (s in EgxStrategies.all) { out.add(s.name); out.add(s.summary) }
        for (l in EgxStrategies.technical) { out.add(l.name); out.add(l.summary) }
        out.addAll(PAGES)
        for (v in listOf("buy", "sell")) out.addAll(listOf("$v one", "$v two", "$v %1\$d", "$v %1\$d (11+)"))
        return out
    }

    @Test
    fun everyTextHasAnArabicRow() {
        val missing = texts().filter { it !in table }.sorted()
        File("build/arabic-missing.txt").apply { parentFile.mkdirs() }.writeText(missing.joinToString("\n"))
        assertEquals(emptyList(), missing, "${missing.size} texts with no Arabic row (listed in build/arabic-missing.txt)")
    }

    @Test
    fun placeholdersMatchBetweenTheLanguages() {
        val ph = Regex("""%\d\$[sd]""")
        val bad = table.filter { (en, ar) -> ph.findAll(en).map { it.value }.toSet() != ph.findAll(ar).map { it.value }.toSet() }.keys
        assertEquals(emptySet(), bad)
    }
}
