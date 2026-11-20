package app.stemwise.ui.player.surface

import java.io.File

internal fun readPlayerSurfaceImplementation(view: File): String {
    check(view.name == "PlayerSurfaceView.kt")
    val policy = File(view.parentFile, "PlayerSurfacePolicies.kt")
    check(policy.isFile) { "Player surface policy owner is missing" }
    val cache = File(view.parentFile, "PlayerArtworkAuraCache.kt")
    val feedback = File(view.parentFile, "PlayerSurfaceMotionPainter.kt")
    val motion = File(view.parentFile, "PlayerArtworkAuraMotionOwner.kt")
    check(cache.isFile && feedback.isFile && motion.isFile) { "Player retained painters are missing" }
    val geometry = File(view.parentFile, "PlayerSurfaceRouteGeometry.kt")
    check(geometry.isFile) { "Player route geometry owner is missing" }
    return policy.readText() + "\n" + cache.readText() + "\n" + geometry.readText() + "\n" + view.readText() + "\n" + feedback.readText() + "\n" + motion.readText()
}
