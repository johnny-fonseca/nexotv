package Nexotv.iptv.player

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkSecurityConfigTest {
    @Test
    fun eachFlavorAllowsOnlyItsDefaultStreamHost() {
        mapOf(
            "teletica" to "190.61.101.11",
            "futv" to "190.61.90.17",
        ).forEach { (flavor, expectedHost) ->
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(File("src/$flavor/res/xml/network_security_config.xml"))
            val baseConfigs = document.getElementsByTagName("base-config")
            val permittedConfigs = document.getElementsByTagName("domain-config")

            assertEquals(1, baseConfigs.length)
            assertEquals("false", baseConfigs.item(0).attributes.getNamedItem("cleartextTrafficPermitted").nodeValue)
            assertEquals(1, permittedConfigs.length)
            assertEquals("true", permittedConfigs.item(0).attributes.getNamedItem("cleartextTrafficPermitted").nodeValue)

            val domains = permittedConfigs.item(0).childNodes
            val declaredHosts = (0 until domains.length).mapNotNull { index ->
                domains.item(index).takeIf { it.nodeName == "domain" }
            }
            assertEquals(1, declaredHosts.size)
            assertEquals(expectedHost, declaredHosts.single().textContent.trim())
            assertEquals(
                "false",
                declaredHosts.single().attributes.getNamedItem("includeSubdomains").nodeValue,
            )
        }
    }

    @Test
    fun commonConfigDoesNotPermitCleartextGlobally() {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/xml/network_security_config.xml"))
        val root = document.documentElement
        val manifestFactory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val manifest = manifestFactory.newDocumentBuilder()
            .parse(File("src/main/AndroidManifest.xml"))
        val androidNamespace = "http://schemas.android.com/apk/res/android"

        assertFalse(root.attributes.getNamedItem("cleartextTrafficPermitted")?.nodeValue == "true")
        assertEquals(
            "false",
            document.getElementsByTagName("base-config").item(0)
                .attributes.getNamedItem("cleartextTrafficPermitted").nodeValue,
        )
        assertTrue(document.getElementsByTagName("domain-config").length == 0)
        assertEquals(
            "false",
            manifest.getElementsByTagName("application").item(0)
                .attributes.getNamedItemNS(androidNamespace, "usesCleartextTraffic").nodeValue,
        )
    }
}