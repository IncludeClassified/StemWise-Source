package app.stemwise.qualification

/** Explicit membership shared by managed audio qualification and device isolation. */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class ManagedAudioTest

/** Acoustic observations require their separate physical setup and are excluded from mandatory audio. */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class OptionalAcousticTest
