package app.stemwise.integration

import app.stemwise.data.CanonicalJson
import app.stemwise.model.StemSourceCatalogue
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class MvsepSourcePlannerTest {
    private val baselineRoles = listOf("vocals", "bass", "drums", "guitar", "piano", "other", "instrum")

    @Test fun fourOutputOnlyDrumCatalogueAdmitsItsWholeFamilyAndRecoversTheChosenRoute() {
        val route = MvsepSourcePlanner.configuredOperations().single { it.configurationId == "mvsep-37-four-direct" }
        val provider = algorithm(37, emptyList(), MvsepCatalogueMetadata(route.expectedRoles,
            route.options.map { (name, value) -> MvsepOptionField(name, name, setOf(value)) },
            listOf(MvsepOutputVariant(route.options, route.expectedRoles))))
        val available = MvsepSourcePlanner.availableSources(listOf(provider)).map { it.id }.toSet()
        assertEquals(setOf("kick", "snare", "toms", "cymbals"), available)
        for (selection in listOf(listOf("kick"), listOf("kick", "cymbals"))) {
            val plan = MvsepSourcePlanner.plan(selection, listOf(provider))
            assertEquals(route.configurationId, plan.operations.single().configurationId)
            assertEquals(selection.toSet(), plan.operations.single().outputBindings.keys)
            MvsepSourcePlanner.requirePlanContract(plan)
        }
        assertThrows(IllegalArgumentException::class.java) { MvsepSourcePlanner.plan(listOf("hi-hat"), listOf(provider)) }
    }

    @Test
    fun configuredInventoryCoversEveryExactRouteAndBothDrumVariants() {
        val inventory = MvsepSourcePlanner.configuredOperations()
        assertEquals(77, StemSourceCatalogue.sources.size)
        assertEquals(62, inventory.size)
        assertEquals(62, inventory.map { it.configurationId }.distinct().size)
        assertEquals(61, inventory.map { it.renderId }.distinct().size)
        assertEquals(80, inventory.sumOf { it.outputBindings.size })
        assertEquals(StemSourceCatalogue.sources.map { it.id }.toSet(), inventory.flatMap { it.outputBindings.keys }.toSet())
        inventory.forEach(MvsepSourcePlanner::requireOperationContract)

        // Use normal planning to prove every inventoried binding is reachable, including the four-output drum route.
        val algorithms = inventory.groupBy { it.renderId }.map { (id, routes) ->
            val fields = routes.flatMap { it.options.keys }.distinct().map { name ->
                MvsepOptionField(name, name, routes.mapNotNull { it.options[name] }.toSet())
            }
            algorithm(id, emptyList(), MvsepCatalogueMetadata(routes.first().expectedRoles, fields,
                if (fields.isEmpty()) emptyList() else routes.map { MvsepOutputVariant(it.options, it.expectedRoles) }))
        }
        val plans = StemSourceCatalogue.sources.map { MvsepSourcePlanner.plan(listOf(it.id), algorithms) } +
            MvsepSourcePlanner.plan(listOf("kick", "snare", "toms", "cymbals"), algorithms)
        val reached = plans.flatMap { it.operations }.flatMap { operation ->
            operation.outputBindings.map { (source, role) -> Triple(operation.configurationId, source, role) }
        }.toSet()
        assertEquals(inventory.flatMap { operation ->
            operation.outputBindings.map { (source, role) -> Triple(operation.configurationId, source, role) }
        }.toSet(), reached)
        assertEquals(listOf("6", "7"), inventory.filter { it.renderId == 37 }.map { it.options.getValue("add_opt1") }.sorted())
        val cymbals = plans.last().operations.single()
        assertEquals("mvsep-37-four-direct", cymbals.configurationId)
        assertEquals(setOf("kick", "snare", "toms", "cymbals"), cymbals.outputBindings.keys)

        assertThrows(UnsupportedOperationException::class.java) { (inventory as MutableList<MvsepSourceOperation>).clear() }
        val route = inventory.first { it.options.isNotEmpty() }
        assertThrows(UnsupportedOperationException::class.java) { (route.options as MutableMap<String, String>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (route.expectedRoles as MutableSet<String>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (route.outputBindings as MutableMap<String, String>).clear() }
        val baseline = inventory.first { it.renderId == 63 }
        assertThrows(IllegalArgumentException::class.java) {
            MvsepSourcePlanner.requireOperationContract(baseline.copy(options = mapOf("add_opt1" to "0")))
        }
    }

    @Test
    fun hostTimingInventoryIsGeneratedFromCurrentProductionConfiguration() {
        val root = generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
            .first { File(it, "StemWise/app/src/main/java/app/stemwise/integration/MvsepSourcePlanner.kt").isFile }
        val sourcePaths = listOf(
            "StemWise/app/src/main/java/app/stemwise/integration/MvsepSourcePlanner.kt",
            "StemWise/audio-engine/src/main/java/app/stemwise/model/StemSourceCatalogue.kt",
            "StemWise/contracts/audio/playback-capacity.json"
        )
        val expected = JSONObject().put("schemaVersion", 1)
            .put("sourceHashes", JSONObject(sourcePaths.associateWith { path ->
                MessageDigest.getInstance("SHA-256").digest(
                    File(root, path).readText(Charsets.UTF_8).replace("\r\n", "\n").toByteArray(Charsets.UTF_8)
                ).joinToString("") { "%02x".format(it) }
            }))
            .put("selectedSourceIds", JSONArray(StemSourceCatalogue.sources.map { it.id }))
            .put("configurations", JSONArray(MvsepSourcePlanner.configuredOperations().map { operation ->
                JSONObject().put("configurationId", operation.configurationId).put("renderId", operation.renderId)
                    .put("options", JSONObject(operation.options))
                    .put("expectedRoles", JSONArray(operation.expectedRoles.sorted()))
                    .put("outputBindings", JSONObject(operation.outputBindings))
            }))
        val fixture = File(root, "Test suite/android/fixtures/mvsep-source-timing-inventory.json")
        if (System.getenv("STEMWISE_UPDATE_SOURCE_TIMING_INVENTORY") == "1") {
            fixture.writeText(expected.toString(2) + "\n", Charsets.UTF_8)
        }
        assertEquals("Regenerate with STEMWISE_UPDATE_SOURCE_TIMING_INVENTORY=1 and this test",
            CanonicalJson.encode(expected).toString(Charsets.UTF_8),
            CanonicalJson.encode(fixture.readText(Charsets.UTF_8)).toString(Charsets.UTF_8))
    }

    @Test
    fun defaultSelectionReusesOneSeparationAndRetainsFullProviderOutputContract() {
        val plan = MvsepSourcePlanner.plan(StemSourceCatalogue.defaultSelection, listOf(baseline()))
        assertEquals(listOf("vocals", "bass", "drums", "guitar", "piano"), plan.selectedSourceIds)
        val operation = plan.operations.single()
        assertEquals(63, operation.renderId)
        assertEquals(emptyMap<String, String>(), operation.options)
        assertEquals(baselineRoles.toSet(), operation.expectedRoles)
        assertEquals(plan.selectedSourceIds.associateWith { it }, operation.outputBindings)
        assertFalse(StemSourceCatalogue.sources.any { it.id in setOf("other", "remaining") })
    }

    @Test
    fun richOutputsOverrideIncompleteCoarseOutputStems() {
        val provider = baseline().copy(outputStems = baselineRoles - "instrum", catalogueMetadata = metadata(baselineRoles))
        val operation = MvsepSourcePlanner.plan(listOf("vocals"), listOf(provider)).operations.single()
        assertEquals(baselineRoles.toSet(), operation.expectedRoles)
        assertEquals(mapOf("vocals" to "vocals"), operation.outputBindings)
        assertThrows(IllegalArgumentException::class.java) {
            MvsepSourcePlanner.plan(listOf("vocals"), listOf(provider.copy(catalogueMetadata = metadata(baselineRoles - "instrum"))))
        }
    }

    @Test
    fun selectionRejectsUnknownDuplicatesEmptyExcessAndOverlappingScopes() {
        listOf(
            emptyList(), listOf("other"), listOf("remaining"), listOf("new-provider-output"),
            listOf("bass", "bass"),
            listOf("vocals", "backing-vocals"), listOf("vocals", "male-vocals"),
            listOf("lead-vocals", "female-vocals"), listOf("choir", "alto-vocals"),
            listOf("drums", "kick"), listOf("cymbals", "hi-hat"),
            listOf("guitar", "acoustic-guitar"), listOf("lead-guitar", "electric-guitar"),
            listOf("keys", "piano"), listOf("piano", "digital-piano"),
            listOf("strings", "violin"), listOf("bass", "double-bass"),
            listOf("wind", "flute"), listOf("brass", "trumpet"),
            listOf("percussion", "tambourine")
        ).forEach { selected ->
            assertNotNull("Rejected selection $selected", StemSourceCatalogue.selectionIssue(selected))
            assertThrows(IllegalArgumentException::class.java) {
                MvsepSourcePlanner.plan(selected, listOf(baseline()))
            }
        }
        listOf(
            listOf("lead-vocals", "backing-vocals"),
            listOf("male-vocals", "female-vocals"),
            listOf("acoustic-guitar", "electric-guitar"),
            listOf("brass", "woodwind"), listOf("kick", "snare", "toms", "cymbals"),
            listOf("violin", "cello"), listOf("vocals", "organ", "bass")
        ).forEach { selected -> assertNull("Allowed selection $selected", StemSourceCatalogue.selectionIssue(selected)) }
    }

    @Test
    fun sharedAndTransitiveCoverageIsRejectedInBothOrders() {
        listOf(listOf("strings", "bass"), listOf("keys", "percussion"),
            listOf("plucked-strings", "double-bass"), listOf("plucked-strings", "strings"))
            .forEach { pair ->
                for (selected in listOf(pair, pair.reversed())) {
                    assertNotNull(selected.toString(), StemSourceCatalogue.selectionIssue(selected))
                    assertThrows(IllegalArgumentException::class.java) { MvsepSourcePlanner.plan(selected, listOf(baseline())) }
                }
            }
        for (left in StemSourceCatalogue.sources) for (right in StemSourceCatalogue.sources) {
            assertEquals("${left.id}/${right.id}",
                StemSourceCatalogue.selectionIssue(listOf(left.id, right.id)) == null,
                StemSourceCatalogue.selectionIssue(listOf(right.id, left.id)) == null)
        }
    }

    @Test
    fun selectionCapacityIsBoundedAndCanBeLoweredBeforeSubmission() {
        val eight = listOf("lead-vocals", "backing-vocals", "drums", "bass", "acoustic-guitar", "electric-guitar",
            "harp", "mandolin")
        assertEquals(8, StemSourceCatalogue.MAX_TARGETS)
        assertNull(StemSourceCatalogue.selectionIssue(eight))
        assertNotNull(StemSourceCatalogue.selectionIssue(eight + "harmonica"))
        assertNotNull(StemSourceCatalogue.selectionIssue(listOf("bass", "piano"), maxTargets = 1))
        assertNull(StemSourceCatalogue.selectionIssue(listOf("bass"), maxTargets = 1))
        assertThrows(IllegalArgumentException::class.java) { StemSourceCatalogue.selectionIssue(listOf("bass"), 9) }
    }

    @Test
    fun roleAndGenderPairsReuseTheirOwnPinnedDirectConfigurations() {
        val lead = MvsepSourcePlanner.plan(
            listOf("backing-vocals", "lead-vocals"), listOf(karaoke())
        ).operations.single()
        assertEquals(49, lead.renderId)
        assertEquals(mapOf("add_opt1" to "6", "add_opt2" to "0"), lead.options)
        assertEquals(mapOf("backing-vocals" to "vocals-back", "lead-vocals" to "vocals-lead"), lead.outputBindings)

        val gender = MvsepSourcePlanner.plan(
            listOf("female-vocals", "male-vocals"), listOf(gender())
        ).operations.single()
        assertEquals(57, gender.renderId)
        assertEquals(mapOf("add_opt1" to "2", "add_opt2" to "0"), gender.options)
        assertEquals(mapOf("female-vocals" to "female", "male-vocals" to "male"), gender.outputBindings)
    }

    @Test
    fun drumPartsUseOneMatchingConfigurationAndNeverTakeTheFullDrumOutput() {
        val full = MvsepSourcePlanner.plan(listOf("kick", "snare", "hi-hat"), listOf(drums())).operations.single()
        assertEquals(mapOf("add_opt1" to "7", "add_opt2" to "0"), full.options)
        assertEquals(mapOf("kick" to "kick", "snare" to "snare", "hi-hat" to "hh"), full.outputBindings)
        val cymbals = MvsepSourcePlanner.plan(listOf("kick", "cymbals", "toms"), listOf(drums())).operations.single()
        assertEquals(mapOf("add_opt1" to "6", "add_opt2" to "0"), cymbals.options)
        assertEquals(setOf("kick", "cymbals", "toms"), cymbals.outputBindings.keys)
        assertFalse(cymbals.outputBindings.values.any { it in setOf("drums", "residual") })
    }

    @Test
    fun mixedSelectionsKeepOnlyRequestedBindingsAndReuseBaselineOutputs() {
        val organ = algorithm(58, listOf("organ", "other"), metadata(
            roles = listOf("organ", "other"), fields = listOf(field("add_opt1", "model_type", "3"))
        ))
        val plan = MvsepSourcePlanner.plan(listOf("organ", "bass", "piano", "backing-vocals"), listOf(organ, baseline(), karaoke()))
        assertEquals(listOf(58, 63, 49), plan.operations.map { it.renderId })
        assertEquals(mapOf("bass" to "bass", "piano" to "piano"), plan.operations[1].outputBindings)
        assertEquals(setOf("organ", "bass", "piano", "backing-vocals"), plan.operations.flatMap { it.outputBindings.keys }.toSet())
    }

    @Test
    fun catalogueDriftAndUndocumentedConfigurationCannotBeAcceptedByNameOrPrice() {
        val changed = listOf(
            baseline().copy(outputStems = baselineRoles + "unexpected"),
            baseline().copy(outputStems = baselineRoles.dropLast(1)),
            baseline().copy(outputStems = baselineRoles + "vocals"),
            baseline().copy(orientation = 2),
            baseline().copy(priceCoefficient = Double.NaN),
            baseline().copy(renderId = 999, name = "BS Roformer SW")
        )
        changed.forEach { entry ->
            assertThrows(IllegalArgumentException::class.java) {
                MvsepSourcePlanner.plan(listOf("vocals"), listOf(entry))
            }
        }
        val freeLooking = baseline().copy(priceCoefficient = 0.0)
        assertEquals(63, MvsepSourcePlanner.plan(listOf("vocals"), listOf(freeLooking)).operations.single().renderId)
        assertThrows(IllegalArgumentException::class.java) {
            MvsepSourcePlanner.plan(listOf("lead-vocals"), listOf(karaoke().copy(catalogueMetadata = null)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            MvsepSourcePlanner.plan(listOf("lead-vocals"), listOf(karaoke().copy(catalogueMetadata = metadata(
                roles = listOf("vocals-lead", "vocals-back", "instrum-only", "back-instrum"),
                fields = listOf(field("add_opt1", "model_type", "7"), field("add_opt2", "extract_type", "0"))
            ))))
        }
    }

    @Test
    fun duplicateProviderIdsAndVariantMatchesFailClosed() {
        assertThrows(IllegalArgumentException::class.java) {
            MvsepSourcePlanner.plan(listOf("vocals"), listOf(baseline(), baseline()))
        }
        val entry = karaoke()
        val meta = entry.catalogueMetadata!!
        assertThrows(IllegalArgumentException::class.java) {
            MvsepSourcePlanner.plan(listOf("lead-vocals"), listOf(entry.copy(catalogueMetadata = meta.copy(
                outputVariants = meta.outputVariants + meta.outputVariants.single()
            ))))
        }
    }

    @Test
    fun retainedPlansRejectForgedBindingsMissingSourcesDuplicateOperationsAndChangedOptions() {
        val plan = MvsepSourcePlanner.plan(listOf("vocals", "bass"), listOf(baseline()))
        MvsepSourcePlanner.requirePlanContract(plan)
        MvsepSourcePlanner.validateOperation(plan.operations.single(), listOf(baseline()))
        val operation = plan.operations.single()
        listOf(
            plan.copy(selectedSourceIds = listOf("vocals")),
            plan.copy(operations = plan.operations + operation),
            plan.copy(operations = listOf(operation.copy(outputBindings = mapOf("vocals" to "vocals")))),
            plan.copy(operations = listOf(operation.copy(outputBindings = mapOf("vocals" to "other", "bass" to "bass")))),
            plan.copy(operations = listOf(operation.copy(options = mapOf("add_opt1" to "0")))),
            plan.copy(operations = listOf(operation.copy(expectedRoles = setOf("vocals", "bass"))))
        ).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { MvsepSourcePlanner.requirePlanContract(invalid) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            MvsepSourcePlanner.validateOperation(operation, listOf(baseline().copy(outputStems = listOf("vocals", "other"))))
        }
    }

    @Test
    fun availableSourcesFollowVerifiedContractsAndAvailabilityDoesNotAffectConflictPolicy() {
        assertEquals(StemSourceCatalogue.defaultSelection.toSet(), MvsepSourcePlanner.availableSources(listOf(baseline())).map { it.id }.toSet())
        assertEquals(setOf("lead-vocals", "backing-vocals"), MvsepSourcePlanner.availableSources(listOf(karaoke())).map { it.id }.toSet())
        assertEquals(emptyList<String>(), MvsepSourcePlanner.availableSources(listOf(karaoke().copy(catalogueMetadata = null))).map { it.id })
        assertNotNull(StemSourceCatalogue.selectionIssue(listOf("vocals", "backing-vocals")))
    }

    @Test
    fun metadataParserReadsNestedJsonStringsWithoutGuessingMissingOutputRoles() {
        val entry = JSONObject()
            .put("outputs", "{\"male\":\"Male vocals\",\"female\":\"Female vocals\"}")
            .put("output_variants", "{\"keys\":[\"extract_type\"],\"variants\":[{\"match\":{\"extract_type\":\"0\"},\"outputs\":{\"male\":\"Male vocals\",\"female\":\"Female vocals\"}}]}")
            .put("algorithm_fields", JSONArray().put(JSONObject()
                .put("name", "add_opt2").put("server_key", "extract_type")
                .put("options", "{\"0\":\"Direct\",\"1\":\"Extract vocals first\"}")))
        val parsed = parseMvsepCatalogueMetadata(entry)
        assertEquals(setOf("male", "female"), parsed.outputRoles)
        assertEquals(setOf("0", "1"), parsed.optionFields.single().values)
        assertEquals(mapOf("extract_type" to "0"), parsed.outputVariants.single().match)
        assertEquals(emptySet<String>(), parseMvsepCatalogueMetadata(JSONObject().put("name", "Male/Female separation")).outputRoles)
    }

    private fun baseline() = algorithm(63, baselineRoles)
    private fun karaoke(): MvsepAlgorithm {
        val roles = listOf("vocals-lead", "vocals-back", "instrum-only", "back-instrum")
        return algorithm(49, roles, metadata(roles,
            listOf(field("add_opt1", "model_type", "6"), field("add_opt2", "extract_type", "0", "1")),
            listOf(MvsepOutputVariant(mapOf("model_type" to "6", "extract_type" to "0"), roles.toSet()))))
    }
    private fun gender(): MvsepAlgorithm {
        val roles = listOf("male", "female")
        return algorithm(57, emptyList(), metadata(roles,
            listOf(field("add_opt1", "model_type", "2"), field("add_opt2", "extract_type", "0", "1")),
            listOf(MvsepOutputVariant(mapOf("extract_type" to "0"), roles.toSet()))))
    }
    private fun drums(): MvsepAlgorithm {
        val six = setOf("drums", "kick", "snare", "toms", "ride", "hh", "crash", "residual")
        val four = setOf("drums", "kick", "snare", "toms", "cymbals", "residual")
        return algorithm(37, listOf("4-6-stems-kick", "snare", "cymbals", "toms", "ride", "hh", "crash"),
            metadata(six.toList(),
                listOf(field("add_opt1", "model_type", "6", "7"), field("add_opt2", "extract_type", "0", "1")),
                listOf(MvsepOutputVariant(mapOf("model_type" to "7", "extract_type" to "0"), six),
                    MvsepOutputVariant(mapOf("model_type" to "6", "extract_type" to "0"), four))))
    }
    private fun field(name: String, serverKey: String, vararg values: String) =
        MvsepOptionField(name, serverKey, values.toSet())
    private fun metadata(roles: List<String>, fields: List<MvsepOptionField> = emptyList(), variants: List<MvsepOutputVariant> = emptyList()) =
        MvsepCatalogueMetadata(roles.toSet(), fields, variants)
    private fun algorithm(id: Int, roles: List<String>, metadata: MvsepCatalogueMetadata? = null) =
        MvsepAlgorithm(id, "Provider name must not determine capability", roles, 1.0, 0, metadata)
}
