package app.stemwise.playback.engine

import app.stemwise.model.StemRoleContract

/** Package order and byte identity shared by reducer admission and native graph construction. */
internal fun canonicalEngineStemLanes(lanes: List<EngineStemLane>): List<EngineStemLane>? {
    if (lanes.isEmpty()) return emptyList()
    val normalized = lanes.map { it.copy(normalizedRole = StemRoleContract.normalize(it.normalizedRole)) }
    val roles = normalized.map(EngineStemLane::normalizedRole)
    if (!StemRoleContract.isPlayable(roles)) return null
    val byRole = normalized.associateBy(EngineStemLane::normalizedRole)
    return StemRoleContract.orderedPlayableRoles(roles).map(byRole::getValue)
}

internal fun engineStemPackageSignature(canonicalLanes: List<EngineStemLane>): String =
    canonicalLanes.joinToString(prefix = "source-v1[", postfix = "]", separator = "|") {
        "${it.normalizedRole.length}:${it.normalizedRole};" +
            "${it.contentSha256.length}:${it.contentSha256};" +
            "${it.location.length}:${it.location}"
    }

internal data class StemPackageRequest(
    val token: EngineSelectionToken,
    val signature: String,
    val stemRevision: String,
    val requestRevision: Long,
    val canonicalLanes: List<EngineStemLane>
) {
    init {
        require(signature.isNotBlank())
        require(stemRevision.isNotBlank())
        require(requestRevision > 0L)
        require(StemRoleContract.isPlayable(canonicalLanes.map(EngineStemLane::normalizedRole)))
    }
}

/** Actor-owned canonical package request; stable polling reuses the exact validated identity. */
internal class NativeStemPackageIdentity {
    private var rawLanes: List<EngineStemLane>? = null
    private var canonical: List<EngineStemLane>? = null
    private var signature: String? = null
    private var request: StemPackageRequest? = null

    fun resolve(token: EngineSelectionToken, lanes: List<EngineStemLane>, revision: String,
        requestRevision: Long): StemPackageRequest? {
        if (rawLanes != lanes) {
            rawLanes = lanes.toList()
            canonical = canonicalEngineStemLanes(lanes)
            signature = canonical?.takeIf { it.isNotEmpty() }?.let(::engineStemPackageSignature)
            request = null
        }
        val identity = signature ?: return null
        val actualRevision = revision.ifBlank { identity }
        return request?.takeIf { it.token == token && it.stemRevision == actualRevision &&
            it.requestRevision == requestRevision } ?: StemPackageRequest(token, identity,
                actualRevision, requestRevision, requireNotNull(canonical)).also { request = it }
    }
}
