package app.stemwise.data

/** Seed already verified fixture media through the current projection boundary. */
internal suspend fun seedVerifiedBinding(
    registry: SourceBindingRegistry,
    mutations: PackageMutationCoordinator,
    root: RootHandle,
    sourceId: String,
    packageId: String
) = mutations.withRootMutation {
    val verified = registry.activePackageIdsBySource(root) + (sourceId to packageId)
    registry.rebuildFromVerifiedBindingsUnderRootLock(root, verified)
}
