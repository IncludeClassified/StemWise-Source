package app.stemwise

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivitySplashPolicyTest {
    @Test
    fun shareEntryUsesOneDeclaredPrimaryTask() {
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val document = factory.newDocumentBuilder().parse(File(projectRoot(), "app/src/main/AndroidManifest.xml"))
        val activities = document.getElementsByTagName("activity")
        val android = "http://schemas.android.com/apk/res/android"
        val primary = (0 until activities.length).map { activities.item(it) as org.w3c.dom.Element }
            .single { it.getAttributeNS(android, "name") == ".MainActivity" }
        org.junit.Assert.assertEquals("singleTask", primary.getAttributeNS(android, "launchMode"))
    }

    private fun projectRoot(): File {
        val working = File(checkNotNull(System.getProperty("user.dir")))
        return generateSequence(working) { it.parentFile }
            .firstOrNull { File(it, "app/src/main").isDirectory }
            ?: error("Could not locate StemWise project from ${working.absolutePath}")
    }
}
