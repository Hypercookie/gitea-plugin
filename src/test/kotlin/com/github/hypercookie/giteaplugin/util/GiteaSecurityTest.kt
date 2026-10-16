package com.github.hypercookie.giteaplugin.util

import com.github.hypercookie.giteaplugin.repo.GiteaUrlUtil
import com.github.hypercookie.giteaplugin.settings.GiteaLogin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class GiteaSecurityTest {

    @Test
    fun `only absolute http urls are opened`() {
        assertTrue(GiteaBrowser.isSafe("https://git.example.com/o/r/pulls/1"))
        assertTrue(GiteaBrowser.isSafe("HTTP://ci.example.com/job/1"))
        listOf(
            null, "", "  ", "/o/r/pulls/1", "file:///etc/passwd", "smb://host/share",
            "javascript:alert(1)", "vbscript:x", "data:text/html,x", "jetbrains://idea/x", "https:///nohost",
        ).forEach { assertFalse(it.toString(), GiteaBrowser.isSafe(it)) }
    }

    @Test
    fun `plain http to a remote host is insecure`() {
        assertTrue(GiteaLogin.isInsecure("http://git.example.com"))
        assertFalse(GiteaLogin.isInsecure("https://git.example.com"))
        assertFalse(GiteaLogin.isInsecure("http://localhost:3000"))
        assertFalse(GiteaLogin.isInsecure("http://gitea.localhost"))
        assertFalse(GiteaLogin.isInsecure("http://127.0.0.1:3000"))
        assertFalse(GiteaLogin.isInsecure("http://[::1]:3000"))
    }

    @Test
    fun `dot segments are not an owner or repository`() {
        val server = GiteaUrlUtil.parseRemoteUrl("https://git.example.com")!!
        assertEquals("o" to "r", GiteaUrlUtil.ownerAndRepo(GiteaUrlUtil.parseRemoteUrl("https://git.example.com/o/r.git")!!, server))
        assertNull(GiteaUrlUtil.ownerAndRepo(GiteaUrlUtil.parseRemoteUrl("https://git.example.com/o/..")!!, server))
        assertNull(GiteaUrlUtil.ownerAndRepo(GiteaUrlUtil.parseRemoteUrl("https://git.example.com/./r")!!, server))
    }

    @Test
    fun `oversized images are not decoded`() {
        assertNotNull(GiteaImages.decode(png(32, 32)))
        assertNull(GiteaImages.decode(png(3000, 1)))
        assertNull(GiteaImages.decode("not an image".toByteArray()))
    }

    private fun png(width: Int, height: Int): ByteArray =
        ByteArrayOutputStream().also { ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "png", it) }.toByteArray()
}
