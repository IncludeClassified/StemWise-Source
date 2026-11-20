package app.stemwise.model

enum class StemSourceGroup(val label: String) {
    VOCALS("Vocals"),
    DRUMS_AND_PERCUSSION("Drums & percussion"),
    GUITARS_AND_STRINGS("Guitars & strings"),
    KEYS_AND_SYNTHS("Keys & synths"),
    WINDS("Winds"),
    OTHER("Other sources")
}

data class StemSource(val id: String, val label: String, val group: StemSourceGroup)

/** User-facing source identities. Provider model identities never belong in this catalogue. */
object StemSourceCatalogue {
    // Playback budget; Remaining adds one lane. Keep the complete catalogue independently.
    const val MAX_TARGETS = app.stemwise.MAX_SELECTED_TARGETS
    val defaultSelection: List<String> = listOf("vocals", "bass", "drums", "guitar", "piano")

    val sources: List<StemSource> = buildList {
        fun add(group: StemSourceGroup, vararg entries: Pair<String, String>) {
            entries.forEach { (id, label) -> add(StemSource(id, label, group)) }
        }
        add(StemSourceGroup.VOCALS,
            "vocals" to "All vocals", "lead-vocals" to "Lead vocals", "backing-vocals" to "Backing vocals",
            "male-vocals" to "Male vocals", "female-vocals" to "Female vocals", "choir" to "Choir",
            "soprano-vocals" to "Soprano vocals", "alto-vocals" to "Alto vocals",
            "tenor-vocals" to "Tenor vocals", "bass-vocals" to "Bass vocals")
        add(StemSourceGroup.DRUMS_AND_PERCUSSION,
            "drums" to "All drums", "kick" to "Kick", "snare" to "Snare", "toms" to "Toms",
            "cymbals" to "All cymbals", "hi-hat" to "Hi-hat", "ride" to "Ride", "crash" to "Crash",
            "percussion" to "All percussion", "tambourine" to "Tambourine", "marimba" to "Marimba",
            "glockenspiel" to "Glockenspiel", "timpani" to "Timpani", "triangle" to "Triangle",
            "congas" to "Congas", "bells" to "Bells", "wind-chimes" to "Wind chimes",
            "xylophone" to "Xylophone", "vibraphone" to "Vibraphone", "metal-bars" to "Metal bars",
            "clap" to "Claps", "cowbell" to "Cowbell")
        add(StemSourceGroup.GUITARS_AND_STRINGS,
            "bass" to "Bass", "guitar" to "All guitars", "acoustic-guitar" to "Acoustic guitar",
            "electric-guitar" to "Electric guitar", "lead-guitar" to "Lead guitar", "rhythm-guitar" to "Rhythm guitar",
            "plucked-strings" to "All plucked strings", "harp" to "Harp", "mandolin" to "Mandolin",
            "banjo" to "Banjo", "sitar" to "Sitar", "ukulele" to "Ukulele", "dobro" to "Dobro",
            "strings" to "All bowed strings", "violin" to "Violin", "viola" to "Viola",
            "cello" to "Cello", "double-bass" to "Double bass")
        add(StemSourceGroup.KEYS_AND_SYNTHS,
            "keys" to "All keys", "piano" to "Piano", "digital-piano" to "Digital piano",
            "organ" to "Organ", "synth" to "Synthesizer", "harpsichord" to "Harpsichord",
            "accordion" to "Accordion", "rhodes" to "Rhodes", "clavinet" to "Clavinet",
            "mellotron" to "Mellotron", "celesta" to "Celesta")
        add(StemSourceGroup.WINDS,
            "wind" to "All wind instruments", "brass" to "All brass", "woodwind" to "All woodwinds",
            "saxophone" to "Saxophone", "flute" to "Flute", "trumpet" to "Trumpet", "trombone" to "Trombone",
            "oboe" to "Oboe", "clarinet" to "Clarinet", "french-horn" to "French horn",
            "harmonica" to "Harmonica", "tuba" to "Tuba", "bassoon" to "Bassoon", "bagpipes" to "Bagpipes",
            "whistle" to "Whistle")
        add(StemSourceGroup.OTHER, "crowd" to "Crowd")
    }

    private val byId = sources.associateBy { it.id }
    private val vocalRole = setOf("lead-vocals", "backing-vocals")
    private val vocalGender = setOf("male-vocals", "female-vocals")
    private val choirParts = setOf("soprano-vocals", "alto-vocals", "tenor-vocals", "bass-vocals")
    private val drumParts = setOf("kick", "snare", "toms", "cymbals", "hi-hat", "ride", "crash")
    private val guitarRole = setOf("lead-guitar", "rhythm-guitar")
    private val guitarType = setOf("acoustic-guitar", "electric-guitar")
    private val plucked = guitarRole + guitarType + setOf("guitar", "bass", "harp", "mandolin", "banjo", "sitar", "ukulele", "dobro")
    private val bowed = setOf("violin", "viola", "cello", "double-bass")
    private val keyboard = sources.filter { it.group == StemSourceGroup.KEYS_AND_SYNTHS }.map { it.id }.toSet() - "keys"
    private val brass = setOf("trumpet", "trombone", "french-horn", "tuba")
    private val woodwind = setOf("saxophone", "flute", "oboe", "clarinet", "bassoon", "bagpipes")
    private val percussion = sources.filter { it.group == StemSourceGroup.DRUMS_AND_PERCUSSION }.map { it.id }.toSet() - "percussion"
    private val broadScopes = mapOf(
        "vocals" to (vocalRole + vocalGender + choirParts + "choir"),
        "choir" to choirParts,
        "drums" to drumParts,
        "cymbals" to setOf("hi-hat", "ride", "crash"),
        "percussion" to (percussion + setOf("celesta")),
        "metal-bars" to setOf("vibraphone", "glockenspiel", "bells", "cowbell", "triangle"),
        "guitar" to (guitarRole + guitarType + "dobro"),
        "plucked-strings" to plucked,
        "strings" to bowed,
        "bass" to setOf("double-bass"),
        "keys" to keyboard,
        "piano" to setOf("digital-piano", "rhodes"),
        "wind" to (brass + woodwind + setOf("brass", "woodwind", "harmonica", "whistle")),
        "brass" to brass,
        "woodwind" to woodwind
    )

    private val coverage = sources.associate { source ->
        val reached = mutableSetOf<String>()
        fun expand(id: String) {
            if (reached.add(id)) broadScopes[id].orEmpty().forEach(::expand)
        }
        expand(source.id)
        source.id to reached
    }

    fun source(id: String): StemSource? = byId[id]

    /** Same rule used by the chooser, submission and portable-package reader. */
    fun selectionIssue(selectedIds: Collection<String>, maxTargets: Int = MAX_TARGETS): String? {
        require(maxTargets in 1..MAX_TARGETS) { "Source capacity must be between 1 and $MAX_TARGETS" }
        if (selectedIds.isEmpty()) return "Choose at least one source."
        if (selectedIds.size != selectedIds.toSet().size) return "Choose each source only once."
        if (selectedIds.any { it !in byId }) return "This source is not supported."
        if (selectedIds.size > maxTargets) return "Choose up to $maxTargets sources."
        val selected = selectedIds.toList()
        for (leftIndex in selected.indices) {
            for (rightIndex in leftIndex + 1 until selected.size) {
                val left = selected[leftIndex]
                val right = selected[rightIndex]
                if (overlap(left, right)) {
                    return "Choose either ${byId.getValue(left).label} or ${byId.getValue(right).label}; these sources overlap."
                }
            }
        }
        return null
    }

    fun requireSelection(selectedIds: Collection<String>, maxTargets: Int = MAX_TARGETS): List<String> {
        val issue = selectionIssue(selectedIds, maxTargets)
        require(issue == null) { issue.orEmpty() }
        return selectedIds.toList()
    }

    private fun overlap(left: String, right: String): Boolean =
        coverage.getValue(left).any(coverage.getValue(right)::contains) ||
            crosses(left, right, vocalRole, vocalGender + choirParts + "choir") ||
            crosses(left, right, vocalGender, choirParts + "choir") ||
            crosses(left, right, guitarRole, guitarType)

    private fun crosses(left: String, right: String, first: Set<String>, second: Set<String>): Boolean =
        (left in first && right in second) || (right in first && left in second)
}
