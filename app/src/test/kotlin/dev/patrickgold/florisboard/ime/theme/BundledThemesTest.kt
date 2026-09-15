/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.ime.theme

import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.florisboard.lib.snygg.SnyggJsonConfiguration
import org.florisboard.lib.snygg.SnyggStylesheet
import java.io.File

/**
 * The bundled theme extensions, checked as data rather than as code.
 *
 * A stylesheet is hand-written JSON that the app only ever loads at runtime, and the loader is forgiving
 * by design — [SnyggJsonConfiguration] can be told to skip an unknown property or an unparsable value so
 * a theme somebody shared still works. That forgiveness is wrong for *our own* themes: a mistyped colour
 * or a `var(--typo)` would simply go missing on the keyboard, in one state of one element, and nobody
 * would find it. So these parse with everything strict, and additionally resolve every variable, which
 * the parser itself does not do.
 */
class BundledThemesTest : FunSpec({
    val themeDir: File = run {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        val suffix = "src/main/assets/ime/theme"
        var found: File? = null
        while (dir != null && found == null) {
            found = File(dir, "app/$suffix").takeIf { it.isDirectory }
                ?: File(dir, suffix).takeIf { it.isDirectory }
            dir = dir.parentFile
        }
        found ?: error("theme assets not found from ${System.getProperty("user.dir")}")
    }
    val extensions = themeDir.listFiles()!!.filter { it.isDirectory }.sortedBy { it.name }

    // Every element the keyboard can style, plus the `-icon` / `-text` children that QuickActionButton
    // and friends build by string concatenation and which therefore never appear in the enum.
    val knownElements = FlorisImeUi.elementNames.flatMap { listOf(it, "$it-icon", "$it-text") }.toSet()

    context("every bundled stylesheet parses with nothing ignored") {
        withData(
            nameFn = { "${it.parentFile.parentFile.name}/${it.name}" },
            extensions.flatMap { ext -> File(ext, "stylesheets").listFiles()!!.sortedBy { it.name }.toList() },
        ) { file ->
            SnyggStylesheet.fromJson(file.readText(), SnyggJsonConfiguration.of()).getOrThrow()
        }
    }

    context("the theme list and the stylesheet files agree") {
        withData(nameFn = { it.name }, extensions) { ext ->
            val manifest = Json.parseToJsonElement(File(ext, "extension.json").readText()).jsonObject
            val declared = manifest["themes"]!!.jsonArray
                .map { it.jsonObject["id"]!!.jsonPrimitive.content }
            val onDisk = File(ext, "stylesheets").listFiles()!!.map { it.name.removeSuffix(".json") }
            declared.sorted() shouldBe onDisk.sorted()
            declared.toSet().size shouldBe declared.size
        }
    }

    context("every rule names an element the keyboard actually styles") {
        withData(
            nameFn = { "${it.parentFile.parentFile.name}/${it.name}" },
            extensions.flatMap { ext -> File(ext, "stylesheets").listFiles()!!.sortedBy { it.name }.toList() },
        ) { file ->
            val sheet = Json.parseToJsonElement(file.readText()).jsonObject
            val unknown = sheet.keys
                .filterNot { it.startsWith("$") || it.startsWith("@") }
                .map { it.substringBefore('[').substringBefore(':') }
                .filterNot { it in knownElements }
                .distinct()
            unknown.shouldBeEmpty()
        }
    }

    context("every var() reference resolves against the same sheet's defines") {
        withData(
            nameFn = { "${it.parentFile.parentFile.name}/${it.name}" },
            extensions.flatMap { ext -> File(ext, "stylesheets").listFiles()!!.sortedBy { it.name }.toList() },
        ) { file ->
            val sheet = Json.parseToJsonElement(file.readText()).jsonObject
            val defines = (sheet["@defines"] as? JsonObject)?.keys.orEmpty()
            val varRef = """var\((--[a-z0-9-]+)\)""".toRegex()
            val dangling = sheet.entries
                .filterNot { it.key.startsWith("$") || it.key.startsWith("@") }
                .flatMap { (rule, props) ->
                    props.jsonObject.entries.flatMap { (prop, value) ->
                        varRef.findAll(value.jsonPrimitive.content)
                            .map { it.groupValues[1] }
                            .filterNot { it in defines }
                            .map { "$rule { $prop: var($it) }" }
                    }
                }
            dangling.shouldBeEmpty()
        }
    }
})
