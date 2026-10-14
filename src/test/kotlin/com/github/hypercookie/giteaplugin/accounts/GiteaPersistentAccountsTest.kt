package com.github.hypercookie.giteaplugin.accounts

import com.intellij.openapi.util.JDOMUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.xmlb.XmlSerializer

/** The account list must still load from the XML the plugin wrote before
 * the collaborationTools migration. The fixture is the `GiteaAccounts`
 * component copied from a real pre-migration sandbox config
 * (`.intellijPlatform/sandbox/.../options/gitea-accounts.xml`). */
class GiteaPersistentAccountsTest : BasePlatformTestCase() {

    fun testLoadsPreMigrationState() {
        val oldXml = """
            <component name="GiteaAccounts">
              <option name="accounts">
                <list>
                  <GiteaAccount>
                    <option name="id" value="58d55538-c0ac-40ba-b03e-72b7ce4337e2" />
                    <option name="name" value="Test" />
                    <option name="serverUrl" value="http://localhost:3000" />
                  </GiteaAccount>
                </list>
              </option>
            </component>
        """.trimIndent()
        val state = XmlSerializer.deserialize(JDOMUtil.load(oldXml), GiteaPersistentAccounts.AccountsState::class.java)
        val repo = GiteaPersistentAccounts().apply { loadState(state) }

        val account = repo.accounts.single()
        assertEquals("58d55538-c0ac-40ba-b03e-72b7ce4337e2", account.id)
        assertEquals("Test", account.name)
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
