package com.phonepvr.friends.ui.permissions

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the Google Play Call Log policy in source: a screen must never ask for
 * READ_CALL_LOG / WRITE_CALL_LOG itself. Every request goes through
 * [rememberCallLogAccess] or [CallLogAccessPolicy.requestable], which only allow
 * the prompt while Bondwidth is the phone app. Checking the permission
 * (`checkSelfPermission`) is fine anywhere; asking for it is not.
 */
class CallLogRequestGuardTest {

    private val allowedFiles = setOf(
        // The gate itself.
        "ui/permissions/CallLogAccess.kt",
        // The onboarding list, filtered through CallLogAccessPolicy.requestable.
        "ui/permissions/PermissionExplanations.kt",
    )

    private val callLogPermission = Regex("""Manifest\.permission\.(READ|WRITE)_CALL_LOG""")
    private val requestContext = Regex("""\.launch\(|arrayOf\(|listOf\(""")

    @Test
    fun noScreenAsksForCallLogAccessDirectly() {
        val root = File("src/main/java/com/phonepvr/friends")
        assertTrue("source root not found from ${File(".").absolutePath}", root.isDirectory)

        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { it.relativeTo(root).invariantSeparatorsPath in allowedFiles }
            .flatMap { file ->
                val lines = file.readLines()
                lines.indices.filter { i ->
                    val window = (maxOf(0, i - 3)..i).map { lines[it] }
                    callLogPermission.containsMatchIn(lines[i]) &&
                        // The permission named inside a launch(...) or a list built
                        // for a launcher, on this line or within the lines before it…
                        window.any { requestContext.containsMatchIn(it) } &&
                        // …unless that list is filtered through the policy.
                        window.none { "CallLogAccessPolicy.requestable(" in it }
                }.map { i -> "${file.relativeTo(root).invariantSeparatorsPath}:${i + 1}: ${lines[i].trim()}" }
            }
            .toList()

        assertTrue(
            "Call-log permission requested outside the phone-app gate:\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }
}
