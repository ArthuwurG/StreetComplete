import com.beust.klaxon.JsonObject
import com.beust.klaxon.Parser
import com.esotericsoftware.yamlbeans.YamlConfig
import com.esotericsoftware.yamlbeans.YamlWriter
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.jsoup.Jsoup
import java.io.FileWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.util.Locale
import java.util.TreeMap

/** Get the translator credits from POEditor by extracting the information from various HTML pages*/
open class GetTranslatorCreditsTask : DefaultTask() {
    @get:Input lateinit var targetFile: String
    @get:Input lateinit var languageCodes: Collection<String>
    @get:Input lateinit var cookie: String
    @get:Input lateinit var phpsessid: String

    private val limitTranslationContributionsByName = mapOf(
        // once did a huge refactoring on the strings
        "Karl Ove Hufthammer" to listOf("nn"),
        // admin, changes strings etc
        "Mateusz Konieczny" to listOf("pl"),
        // once did a huge refactoring on the strings (quotation marks)
        "Flo Edelmann" to listOf("de"),
    )

    @TaskAction
    fun run() {
        // map of language tag -> translator name -> translation count
        val resultMap = mutableMapOf<String, MutableMap<String, Int>>()

        // POEditor displays language names. What we need however are language tags
        val tagsByName = languageCodes.associateBy { tagToName(it) }

        // 1. get all users and their ids
        val users = queryUsersOnAllPages()

        // 2. for each user, query his contributions and add it to the result map
        var i = 0
        for (user in users) {
            println("user ${++i}/${users.size}")
            val translationsByName = queryTranslatorStats(user.id)
            translationsByName?.forEach { (languageName, count) ->
                val tag = tagsByName[languageName]
                if (tag != null) {
                    val limitTags = limitTranslationContributionsByName[user.name]
                    if (limitTags == null || limitTags.contains(tag)) {
                        val forCountryCode = resultMap.getOrPut(tag, { mutableMapOf() })
                        forCountryCode[user.name] = (forCountryCode[user.name] ?: 0) + count
                    }
                }
            }
        }

        // 2. remove "en" - that's not a translation but the source language
        resultMap.remove("en")

        // 3. sort contributors by contributions
        val sortedResultMap = TreeMap<String, LinkedHashMap<String, Int>>()
        resultMap.forEach { (languageTag, contributorsMap) ->
            val sortedContributorMap = LinkedHashMap<String, Int>(contributorsMap.size)
            contributorsMap.entries.sortedByDescending { it.value }.forEach { (key, value) ->
                sortedContributorMap[key] = value
            }
            sortedResultMap[languageTag] = sortedContributorMap
        }

        // 4. write the result map to file
        val fileWriter = FileWriter(targetFile, false)
        fileWriter.write("# Do not edit. This file is generated by GetTranslatorCreditsTask.kt\n")
        val config = YamlConfig().apply {
            writeConfig.setWriteClassname(YamlConfig.WriteClassName.NEVER)
            writeConfig.setEscapeUnicode(false)
        }
        val writer = YamlWriter(fileWriter, config)
        writer.write(sortedResultMap)
        writer.close()
        fileWriter.close()
    }

    /** Scrape all contributors HTML pages for the user names and ids */
    private fun queryUsersOnAllPages(): List<User> {
        val result = mutableListOf<User>()
        var i = 1
        while (true) {
            println("page $i")
            val users = queryUsers(i++)
            if (users.isEmpty()) break
            result += users
        }
        return result
    }

    /** Scrape the given contributors HTML page for the user names and ids */
    private fun queryUsers(pageIndex: Int): List<User> {
        val doc = Jsoup.connect("https://poeditor.com/contributors/")
            .data(
                "page", pageIndex.toString(),
                // to make sure that website is in English, i.e. language names are in English
                "Accept-Language", "en-US,en;q=0.5"
            )
            .cookie("login", cookie)
            .cookie("PHPSESSID", phpsessid)
            .get()

        return doc.select("div[id^=userRow]").map { contributor ->
            val id = contributor.id().substringAfter("userRow").toInt()
            val name = contributor.select("span.user-name").text()
            val avatarUrl = contributor.select("img.avatar").attr("src")
            User(id, name, avatarUrl)
        }
    }

    /** returns a map of POEditor language name to number of characters translated, e.g.
     *  "Portuguese (BR)" -> 123
     *  "German" -> 12 */
    private fun queryTranslatorStats(userId: Int): Map<String, Int>? {
        val url = URL("https://poeditor.com/contributors/contributor_stats")
        val connection = url.openConnection() as HttpURLConnection
        val cookieEncoded = URLEncoder.encode(cookie, "UTF-8")
        val phpSessidEncoded = URLEncoder.encode(phpsessid, "UTF-8")
        val today = LocalDate.now().toString()
        try {
            connection.doOutput = true
            connection.requestMethod = "POST"
            connection.setRequestProperty("Cookie", "login=$cookieEncoded;PHPSESSID=$phpSessidEncoded")
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            connection.outputStream.bufferedWriter().use { it.write(
                "id_project=97843&start=2016-01-01&stop=$today&user=$userId"
            ) }
            val response = Parser.default().parse(connection.inputStream) as JsonObject
            val languages = response.obj("table")?.array<JsonObject>("languages")
            return languages?.mapNotNull {
                val chars = it.obj("chars")?.obj("target")?.int("unformat")
                val language = it.obj("language")?.string("name")
                if (chars != null && language != null) language to chars else null
            }?.associate { it }
        } finally {
            connection.disconnect()
        }
    }
}

data class User(
    val id: Int,
    val name: String,
    val avatarUrl: String?
)

/** Convert language tag to how the language is named in English POEditor UI
 *  e.g. en -> English, en-US -> English (US). */
private fun tagToName(tag: String): String =
    when (tag) {
        "en-GB" -> "English (UK)"
        "en" -> "English (US)"
        else -> {
            val locale = Locale.forLanguageTag(tag)
            val scriptName = if (locale.script != "") " (" + locale.getDisplayScript(Locale.ENGLISH) + ")" else ""
            val countryName = if (locale.country != "") " (" + locale.country + ")" else ""
            val langName = locale.getDisplayLanguage(Locale.ENGLISH)
            langName + countryName + scriptName
        }
    }
