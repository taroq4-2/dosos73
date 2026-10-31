package com.os73.dosos73

/** Cache preparation is separate from completion of the whole runtime install. */
internal class RuntimePreparation {
    private val preparedIdentities = HashSet<String>()

    @Synchronized
    fun prepare(identity: String, preparation: () -> Unit) {
        if (identity in preparedIdentities) return
        preparation()
        // Failed deletion must be retried; later component failures must not undo
        // this preparation after an upstream singleton has initialized.
        preparedIdentities.add(identity)
    }
}
