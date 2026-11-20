package app.stemwise.ui

import app.stemwise.model.StemRoleContract
import app.stemwise.model.StemSourceCatalogue
import app.stemwise.ui.player.nativeplayer.FullPlayerStemRole
import java.util.Locale

/** One musical identity and glyph per source, shared by the chooser and mixer. */
internal val sourcePartIconRoles: Map<String, FullPlayerStemRole> =
    FullPlayerStemRole.entries.associateBy { it.name.lowercase(Locale.ROOT).replace('_', '-') }
        .filterKeys { it != "other" }
        .also { require(it.keys == StemSourceCatalogue.sources.map { source -> source.id }.toSet() + "remaining") }

internal fun sourcePartIconRole(sourceId: String): FullPlayerStemRole =
    sourcePartIconRoles[StemRoleContract.normalize(sourceId)] ?: FullPlayerStemRole.OTHER
