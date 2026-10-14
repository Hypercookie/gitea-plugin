package com.github.hypercookie.giteaplugin.accounts

import com.intellij.openapi.util.JDOMUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.xmlb.XmlSerializer

/** The account list must still load from the XML the plugin wrote before
 * the collaborationTools migration (a bean list of `GiteaAccount`). */
class GiteaPersistentAccountsTest : BasePlatformTestCase() {

    fun testLoadsPreMigrationState() {
        val oldXml = """
            <component name="GiteaAccounts">
              <option name="accounts">
                <list>
                  <GiteaAccount>
                    <option name="id" value="abc-123" />
                    <option name="name" value="plugindev" />
                    <option name="serverUrl" value="http://localhost:3000" />
                  </GiteaAccount>
                </list>
              </option>
            </component>
        """.trimIndent()
        val state = XmlSerializer.deserialize(JDOMUtil.load(oldXml), GiteaPersistentAccounts.AccountsState::class.java)
        val repo = GiteaPersistentAccounts().apply { loadState(state) }

        val account = repo.accounts.single()
        assertEquals("abc-123", account.id)
        assertEquals("plugindev", account.name)
        assertEquals("http://localhost:3000", account.serverUrl)
    }

    fun testRoundTripKeepsLayout() {
        val repo = GiteaPersistentAccounts()
        repo.accounts = setOf(GiteaAccount("id-1", "me", "https://gitea.example.com/"))
        val xml = JDOMUtil.write(XmlSerializer.serialize(repo.state))
        assertTrue(xml, xml.contains("<GiteaAccount>"))
        assertTrue(xml, xml.contains("value=\"https://gitea.example.com\""))
    }
}
