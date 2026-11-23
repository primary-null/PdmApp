package com.pdm.app

import com.pdm.app.data.AppPermissions
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keeps the declared permissions honest: each one has a reason users can read, nothing on the
 * refused list sneaks in, the local network permission follows the target SDK (declared from 37,
 * absent before, per Google's guidance), and backup stays off.
 */
class ManifestPolicyTest {
    private val manifest = File("src/main/AndroidManifest.xml")
    private val gradle = File("build.gradle.kts")
    private val android = "http://schemas.android.com/apk/res/android"

    private val doc by lazy {
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(manifest)
    }

    private fun declared(): List<String> {
        val nodes = doc.getElementsByTagName("uses-permission")
        return (0 until nodes.length).map { nodes.item(it).attributes.getNamedItemNS(android, "name").nodeValue }
    }

    private fun targetSdk(): Int =
        Regex("""targetSdk\s*=\s*(\d+)""").find(gradle.readText())!!.groupValues[1].toInt()

    @Test
    fun everyDeclaredPermissionHasAReasonAndNoneIsDeclaredTwice() {
        val names = declared()
        assertEquals(names.distinct(), names)
        val unexplained = names.filter { it !in AppPermissions.reasons }
        assertTrue("no reason in AppPermissions for $unexplained", unexplained.isEmpty())
    }

    @Test
    fun nothingRefusedIsDeclared() {
        val hits = declared().filter { it in AppPermissions.refused }
        assertTrue("refused permissions declared: $hits", hits.isEmpty())
    }

    @Test
    fun localNetworkPermissionFollowsTheTargetSdk() {
        val target = targetSdk()
        val has = AppPermissions.LOCAL_NETWORK in declared()
        if (target >= AppPermissions.LOCAL_NETWORK_SDK) {
            assertTrue("targetSdk $target blocks LAN access unless ACCESS_LOCAL_NETWORK is declared and requested", has)
        } else {
            assertFalse("targetSdk $target: do not declare ACCESS_LOCAL_NETWORK before 37 (Google guidance)", has)
        }
    }

    @Test
    fun backupAndCleartextStayOff() {
        val app = doc.getElementsByTagName("application").item(0).attributes
        assertEquals("false", app.getNamedItemNS(android, "allowBackup").nodeValue)
        assertEquals("false", app.getNamedItemNS(android, "usesCleartextTraffic").nodeValue)
        // allowBackup=false does not stop device-to-device transfer on Android 12+; the rules must.
        val rules = File("src/main/res/xml/data_extraction_rules.xml").readText()
        listOf("cloud-backup", "device-transfer").forEach { section ->
            val body = rules.substringAfter("<$section>").substringBefore("</$section>")
            listOf("sharedpref", "file", "database", "root").forEach { domain ->
                assertTrue("$section must exclude $domain", body.contains("domain=\"$domain\" path=\".\""))
            }
        }
    }

    @Test
    fun onlyTheLauncherActivityIsExported() {
        val acts = doc.getElementsByTagName("activity")
        val exported = (0 until acts.length).map { acts.item(it).attributes }
            .filter { it.getNamedItemNS(android, "exported")?.nodeValue == "true" }
            .map { it.getNamedItemNS(android, "name").nodeValue }
        assertEquals(listOf(".MainActivity"), exported)
    }
}
