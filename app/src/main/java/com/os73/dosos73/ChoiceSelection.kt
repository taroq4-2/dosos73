package com.os73.dosos73

/** A quality change must not silently switch the selected container or audio language. */
internal fun qualityChoices(choices: List<Choice>, selected: Choice?): List<Choice> =
    if (selected == null) emptyList()
    else choices.filter {
        it.kind == selected.kind && it.extension == selected.extension && it.language == selected.language
    }.distinctBy { it.quality }
