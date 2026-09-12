package com.clipmind.android.data

/** Picker helpers operate only on the last successful, account-scoped response. */
object AiModelCatalog {
    fun search(models: List<String>, query: String): List<String> =
        models.filter { it.contains(query.trim(), ignoreCase = true) }

    fun ordered(models: List<String>, current: String, recent: List<String>): List<String> {
        val available = models.filter(AiDefaults::validModel).distinct()
        return (listOf(current) + recent).filter { it in available }.distinct() +
            available.filterNot { it == current || it in recent }
    }
}
