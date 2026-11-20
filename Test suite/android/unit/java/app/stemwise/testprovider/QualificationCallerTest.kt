package app.stemwise.testprovider

import org.junit.Assert.assertThrows
import org.junit.Test

class QualificationCallerTest {
    @Test fun onlyTheActualDevelopmentResultOwnerCanRequestFixtureMutation() {
        GrantQualificationTreeActivity.requireAuthorizedCaller("app.stemwise.development", "androidx.activity.ComponentActivity")
        for (caller in listOf(null to null, "unrelated.application" to "androidx.activity.ComponentActivity",
            "app.stemwise.development" to null, "app.stemwise.development" to "other.Activity")) {
            assertThrows(SecurityException::class.java) {
                GrantQualificationTreeActivity.requireAuthorizedCaller(caller.first, caller.second)
            }
        }
    }
}
