package com.trading.bot.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource
import java.io.IOException
import java.io.InputStream

/**
 * [BuildIdentity] — чтение git SHA из ресурса сборки: отсутствие файла,
 * `git.commit.id.full` / legacy `git.commit.id`, строки без `=`, кэш после
 * первого разрешения и fail-soft на ошибке чтения.
 */
class BuildIdentityTest {
    private fun propertyLines(vararg lines: String): ClassPathResource =
        object : ClassPathResource("inline-git.properties") {
            override fun exists(): Boolean = true

            override fun getInputStream(): InputStream = lines.joinToString("\n").byteInputStream()
        }

    @Test
    fun `missing git properties resolve to null`() {
        val identity = BuildIdentity(ClassPathResource("definitely-absent-git.properties"))

        assertNull(identity.gitCommitSha())
    }

    @Test
    fun `first matching commit id line wins`() {
        val fullFirst =
            BuildIdentity(
                propertyLines(
                    "git.commit.id.full=0123456789abcdef0123456789abcdef01234567",
                    "git.commit.id=short",
                ),
            )
        val shortFirst = BuildIdentity(propertyLines("git.commit.id=short", "git.commit.id.full=full"))

        assertEquals("0123456789abcdef0123456789abcdef01234567", fullFirst.gitCommitSha())
        assertEquals("short", shortFirst.gitCommitSha())
    }

    @Test
    fun `legacy short commit id is used when full id is absent`() {
        val identity = BuildIdentity(propertyLines("git.branch=main", "git.commit.id=0123456789abcdef"))

        assertEquals("0123456789abcdef", identity.gitCommitSha())
    }

    @Test
    fun `malformed lines and unrelated keys are ignored`() {
        val identity = BuildIdentity(propertyLines("no-equals-here", "git.branch=main", "git.commit.id=abc"))

        assertEquals("abc", identity.gitCommitSha())
    }

    @Test
    fun `empty properties file resolves to null`() {
        val identity = BuildIdentity(propertyLines())

        assertNull(identity.gitCommitSha())
    }

    @Test
    fun `resolved identity is cached across calls`() {
        var reads = 0
        val resource =
            object : ClassPathResource("counting-git.properties") {
                override fun exists(): Boolean = true

                override fun getInputStream(): InputStream {
                    reads++
                    return "git.commit.id=abc".byteInputStream()
                }
            }
        val identity = BuildIdentity(resource)

        assertEquals("abc", identity.gitCommitSha())
        assertEquals("abc", identity.gitCommitSha())
        assertEquals(1, reads, "second call must use the cached value")
    }

    @Test
    fun `read failure is fail-soft`() {
        val resource =
            object : ClassPathResource("broken-git.properties") {
                override fun exists(): Boolean = true

                override fun getInputStream(): InputStream = throw IOException("disk error")
            }

        assertNull(BuildIdentity(resource).gitCommitSha())
    }
}
