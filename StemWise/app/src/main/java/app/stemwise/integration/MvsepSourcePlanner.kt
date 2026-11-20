package app.stemwise.integration

import app.stemwise.model.StemSource
import app.stemwise.model.StemSourceCatalogue
import java.util.Collections
import org.json.JSONArray
import org.json.JSONObject

data class MvsepOptionField(val name: String, val serverKey: String, val values: Set<String>)
data class MvsepOutputVariant(val match: Map<String, String>, val roles: Set<String>)
data class MvsepCatalogueMetadata(
    val outputRoles: Set<String>,
    val optionFields: List<MvsepOptionField>,
    val outputVariants: List<MvsepOutputVariant>
)

/** Frozen provider request. Bindings contain requested sources, never provider complements. */
data class MvsepSourceOperation(
    val configurationId: String,
    val renderId: Int,
    val options: Map<String, String>,
    val expectedRoles: Set<String>,
    val outputBindings: Map<String, String>
)

data class MvsepSourcePlan(val selectedSourceIds: List<String>, val operations: List<MvsepSourceOperation>)

/**
 * Small explicit catalogue of direct provider routes, rather than guesses based on model names.
 * Options and output variants are rechecked against https://mvsep.com/api/app/algorithms.
 * Price coefficients describe paid pricing; they do not establish free access. Submission must
 * retain the account's premium-disable protection independently of this planner.
 */
object MvsepSourcePlanner {
    private data class Configuration(
        val id: String,
        val renderId: Int,
        val options: Map<String, String>,
        val roles: Set<String>,
        val bindings: Map<String, String>
    )

    private val baselineIds = setOf("vocals", "bass", "drums", "guitar", "piano")
    private val drumIds = setOf("kick", "snare", "toms", "cymbals", "hi-hat", "ride", "crash")
    private val configurations: List<Configuration> = buildList {
        fun route(id: String, renderId: Int, options: Map<String, String>, roles: Set<String>, bindings: Map<String, String>) {
            add(Configuration(id, renderId, options, roles, bindings))
        }
        fun single(sourceId: String, renderId: Int, options: Map<String, String> = emptyMap(), role: String = sourceId) {
            route("mvsep-$renderId-direct", renderId, options, setOf(role, "other"), mapOf(sourceId to role))
        }
        route("mvsep-63", 63, emptyMap(), baselineIds + setOf("other", "instrum"), baselineIds.associateWith { it })
        route("mvsep-49-direct", 49, mapOf("add_opt1" to "6", "add_opt2" to "0"),
            setOf("vocals-lead", "vocals-back", "instrum-only", "back-instrum"),
            mapOf("lead-vocals" to "vocals-lead", "backing-vocals" to "vocals-back"))
        route("mvsep-57-direct", 57, mapOf("add_opt1" to "2", "add_opt2" to "0"),
            setOf("male", "female"), mapOf("male-vocals" to "male", "female-vocals" to "female"))
        single("choir", 112, mapOf("add_opt2" to "0"))
        route("mvsep-111-direct", 111, mapOf("add_opt1" to "3", "add_opt2" to "0"),
            setOf("soprano", "alto", "tenor", "bass"),
            mapOf("soprano-vocals" to "soprano", "alto-vocals" to "alto", "tenor-vocals" to "tenor", "bass-vocals" to "bass"))
        route("mvsep-37-six-direct", 37, mapOf("add_opt1" to "7", "add_opt2" to "0"),
            setOf("drums", "kick", "snare", "toms", "ride", "hh", "crash", "residual"),
            mapOf("kick" to "kick", "snare" to "snare", "toms" to "toms", "hi-hat" to "hh", "ride" to "ride", "crash" to "crash"))
        route("mvsep-37-four-direct", 37, mapOf("add_opt1" to "6", "add_opt2" to "0"),
            setOf("drums", "kick", "snare", "toms", "cymbals", "residual"),
            mapOf("kick" to "kick", "snare" to "snare", "toms" to "toms", "cymbals" to "cymbals"))
        single("percussion", 105)
        single("tambourine", 76)
        single("marimba", 84)
        single("glockenspiel", 85)
        single("timpani", 86)
        single("triangle", 89)
        single("congas", 94)
        single("bells", 95)
        single("wind-chimes", 98)
        single("xylophone", 109, mapOf("add_opt2" to "0"))
        single("vibraphone", 129, role = "vibes")
        single("metal-bars", 130)
        single("clap", 133)
        single("cowbell", 128)
        single("acoustic-guitar", 66, mapOf("add_opt2" to "0"))
        single("electric-guitar", 81, mapOf("add_opt2" to "0"))
        route("mvsep-101-direct", 101, mapOf("add_opt1" to "1"),
            setOf("lead-guitar", "rhythm-guitar", "all-guitar", "no-guitar"),
            mapOf("lead-guitar" to "lead-guitar", "rhythm-guitar" to "rhythm-guitar"))
        single("plucked-strings", 102)
        single("harp", 72)
        single("mandolin", 74)
        single("banjo", 83)
        single("sitar", 90)
        single("ukulele", 96)
        single("dobro", 97)
        single("strings", 52, mapOf("add_opt1" to "1", "add_opt2" to "0"))
        single("violin", 65)
        single("viola", 69, mapOf("add_opt2" to "0"))
        single("cello", 70, mapOf("add_opt2" to "0"))
        single("double-bass", 73, mapOf("add_opt2" to "0"))
        single("keys", 106)
        single("digital-piano", 79, mapOf("add_opt2" to "0"))
        single("organ", 58, mapOf("add_opt1" to "3"))
        single("synth", 88, mapOf("add_opt1" to "0"))
        single("harpsichord", 91)
        single("accordion", 99)
        single("rhodes", 131)
        single("clavinet", 135)
        single("mellotron", 136)
        single("celesta", 110, mapOf("add_opt2" to "0"))
        single("wind", 54, mapOf("add_opt1" to "3", "add_opt2" to "0", "add_opt3" to "0"))
        single("brass", 107, mapOf("add_opt1" to "0"))
        single("woodwind", 108, mapOf("add_opt1" to "0"))
        single("saxophone", 61, mapOf("add_opt1" to "3"))
        single("flute", 67, mapOf("add_opt1" to "1", "add_opt2" to "0"))
        single("trumpet", 71, mapOf("add_opt2" to "0"))
        single("trombone", 75, mapOf("add_opt2" to "0"))
        single("oboe", 77, mapOf("add_opt2" to "0"))
        single("clarinet", 78, mapOf("add_opt2" to "0"))
        single("french-horn", 82, mapOf("add_opt2" to "0"))
        single("harmonica", 87, mapOf("add_opt2" to "0"))
        single("tuba", 92)
        single("bassoon", 93)
        single("bagpipes", 116, mapOf("add_opt2" to "0"))
        single("whistle", 132)
        single("crowd", 34, mapOf("add_opt1" to "2"))
    }
    private val byConfigurationId = configurations.associateBy { it.id }

    /** Every exact configured route, including bindings hidden by singleton selection defaults. */
    fun configuredOperations(): List<MvsepSourceOperation> = Collections.unmodifiableList(configurations.map {
        MvsepSourceOperation(it.id, it.renderId, immutableMap(it.options), immutableSet(it.roles), immutableMap(it.bindings))
    })

    fun availableSources(algorithms: List<MvsepAlgorithm>): List<StemSource> =
        StemSourceCatalogue.sources.filter { source ->
            runCatching { plan(listOf(source.id), algorithms) }.isSuccess
        }

    fun plan(
        selectedIds: Collection<String>,
        algorithms: List<MvsepAlgorithm>,
        maxTargets: Int = StemSourceCatalogue.MAX_TARGETS
    ): MvsepSourcePlan {
        val selected = StemSourceCatalogue.requireSelection(selectedIds, maxTargets)
        val drumSelection = selected.filter { it in drumIds }
        val drumConfiguration = if (drumSelection.isEmpty()) null else configurations.firstOrNull { route ->
            route.renderId == 37 && route.bindings.keys.containsAll(drumSelection) &&
                runCatching { requireLiveConfiguration(route, algorithms) }.isSuccess
        } ?: throw IllegalArgumentException("This source is currently unavailable.")
        val groups = linkedMapOf<Configuration, MutableList<String>>()
        for (sourceId in selected) {
            val configuration = if (sourceId in drumIds) checkNotNull(drumConfiguration) else configurationFor(sourceId)
            requireLiveConfiguration(configuration, algorithms)
            groups.getOrPut(configuration) { mutableListOf() }.add(sourceId)
        }
        val operations = groups.map { (configuration, sourceIds) ->
            MvsepSourceOperation(
                configuration.id, configuration.renderId,
                immutableMap(configuration.options), immutableSet(configuration.roles),
                immutableMap(sourceIds.associateWith { configuration.bindings.getValue(it) })
            )
        }
        return MvsepSourcePlan(Collections.unmodifiableList(selected), Collections.unmodifiableList(operations))
    }

    /** Recovery admits only a complete known plan; no live provider dependency after admission. */
    fun requirePlanContract(plan: MvsepSourcePlan) {
        val selected = StemSourceCatalogue.requireSelection(plan.selectedSourceIds)
        require(plan.operations.isNotEmpty()) { "MVSEP_SOURCE_PLAN_INVALID" }
        val actualSources = plan.operations.flatMap { operation ->
            requireStaticOperation(operation)
            operation.outputBindings.keys.toList()
        }
        require(actualSources.size == selected.size && actualSources.toSet() == selected.toSet()) {
            "MVSEP_SOURCE_PLAN_INVALID"
        }
        require(plan.operations.map { it.configurationId }.distinct().size == plan.operations.size) {
            "MVSEP_SOURCE_PLAN_INVALID"
        }
        require(plan.operations.count { it.renderId == 37 } <= 1) { "MVSEP_SOURCE_PLAN_INVALID" }
    }

    /** Fresh provider admission immediately before each network submission. */
    fun validateOperation(operation: MvsepSourceOperation, algorithms: List<MvsepAlgorithm>) {
        val configuration = requireStaticOperation(operation)
        requireLiveConfiguration(configuration, algorithms)
    }

    fun requireOperationContract(operation: MvsepSourceOperation) {
        requireStaticOperation(operation)
    }

    private fun requireStaticOperation(operation: MvsepSourceOperation): Configuration {
        val configuration = byConfigurationId[operation.configurationId]
        require(configuration != null && operation.renderId == configuration.renderId &&
            operation.options == configuration.options && operation.expectedRoles == configuration.roles &&
            operation.outputBindings.isNotEmpty() && operation.outputBindings.all { (source, role) ->
                configuration.bindings[source] == role
            }) { "MVSEP_SOURCE_PLAN_INVALID" }
        StemSourceCatalogue.requireSelection(operation.outputBindings.keys)
        return configuration
    }

    private fun configurationFor(sourceId: String): Configuration {
        val id = when {
            sourceId in baselineIds -> "mvsep-63"
            else -> configurations.singleOrNull { sourceId in it.bindings }?.id
        }
        return byConfigurationId[id] ?: throw IllegalArgumentException("This source is not supported.")
    }

    private fun requireLiveConfiguration(configuration: Configuration, algorithms: List<MvsepAlgorithm>) {
        val algorithm = algorithms.singleOrNull { it.renderId == configuration.renderId }
        require(algorithm != null && algorithm.orientation in 0..1 &&
            algorithm.priceCoefficient.isFinite() && algorithm.priceCoefficient >= 0.0) {
            "This source is currently unavailable."
        }
        val metadata = algorithm.catalogueMetadata
        val fields = metadata?.optionFields.orEmpty()
        require(fields.map { it.name }.toSet() == configuration.options.keys &&
            fields.size == fields.map { it.name }.distinct().size &&
            fields.map { it.serverKey }.distinct().size == fields.size &&
            fields.all { configuration.options[it.name] in it.values }) {
            "This source's available settings have changed."
        }
        val roles = if (metadata != null && metadata.outputVariants.isNotEmpty()) {
            val serverOptions = fields.associate { it.serverKey to configuration.options.getValue(it.name) }
            val variant = metadata.outputVariants.singleOrNull { variant ->
                variant.match.isNotEmpty() && variant.match.all { (key, value) -> serverOptions[key] == value }
            }
            require(variant != null) { "This source's available outputs have changed." }
            variant.roles
        } else if (metadata != null && metadata.outputRoles.isNotEmpty()) {
            metadata.outputRoles
        } else if (algorithm.outputStems.isNotEmpty()) {
            require(algorithm.outputStems.distinct().size == algorithm.outputStems.size) {
                "This source's available outputs have changed."
            }
            algorithm.outputStems.toSet()
        } else {
            metadata?.outputRoles.orEmpty()
        }
        require(roles == configuration.roles) { "This source's available outputs have changed." }
    }

    private fun <K, V> immutableMap(values: Map<K, V>): Map<K, V> =
        Collections.unmodifiableMap(LinkedHashMap(values))
    private fun <T> immutableSet(values: Set<T>): Set<T> = Collections.unmodifiableSet(LinkedHashSet(values))
}

/** Parse only declared capabilities. Malformed supplied metadata cannot become an inferred route. */
fun parseMvsepCatalogueMetadata(item: JSONObject): MvsepCatalogueMetadata {
    fun objectValue(raw: Any?): JSONObject? = when (raw) {
        null, JSONObject.NULL -> null
        is JSONObject -> raw
        is String -> JSONObject(raw)
        else -> throw IllegalArgumentException("MVSEP_CATALOGUE_INVALID")
    }
    fun roles(raw: Any?): Set<String> {
        val value = objectValue(raw) ?: return emptySet()
        return value.keys().asSequence().toSet().also { keys ->
            require(keys.all { it.matches(Regex("[a-z][a-z0-9-]*")) }) { "MVSEP_CATALOGUE_INVALID" }
        }
    }
    val fields = (item.opt("algorithm_fields") as? JSONArray)?.let { array ->
        (0 until array.length()).map { index ->
            val field = array.getJSONObject(index)
            val options = objectValue(field.opt("options")) ?: throw IllegalArgumentException("MVSEP_CATALOGUE_INVALID")
            val name = field.getString("name")
            val serverKey = field.getString("server_key")
            require(name.matches(Regex("add_opt[1-3]")) && serverKey.matches(Regex("[a-z][a-z0-9_]*"))) {
                "MVSEP_CATALOGUE_INVALID"
            }
            MvsepOptionField(name, serverKey, options.keys().asSequence().toSet())
        }
    }.orEmpty()
    val variantObject = objectValue(item.opt("output_variants"))
    val variants = variantObject?.getJSONArray("variants")?.let { array ->
        (0 until array.length()).map { index ->
            val variant = array.getJSONObject(index)
            val match = variant.getJSONObject("match")
            MvsepOutputVariant(match.keys().asSequence().associateWith { match.getString(it) }, roles(variant.get("outputs")))
        }
    }.orEmpty()
    return MvsepCatalogueMetadata(roles(item.opt("outputs")), fields, variants)
}
