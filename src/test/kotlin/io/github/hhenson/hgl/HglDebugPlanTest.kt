package io.github.hhenson.hgl

import com.intellij.execution.ExecutionException
import io.github.hhenson.hgl.debug.HglDebugPlan
import io.github.hhenson.hgl.debug.HglDebugResults
import junit.framework.TestCase
import java.nio.file.Path

class HglDebugPlanTest : TestCase() {
    fun testLaunchesPreserveCompilerOrderAndArguments() {
        val data = """{"executable":"/bin/test","arguments":["arg one","--two"],"workingDirectory":"/work","environment":{"K":"V"}}"""
        val app = HglDebugPlan.parse("""{"version":1,${data.drop(1)}""", testRun = false).launches.single()
        val launch = data.dropLast(1) + ""","module":"second","tests":["second::b","second::a"],"resultsFile":"/work/results"}"""
        val plan = HglDebugPlan.parse("""{"version":1,"launches":[$launch],"skipped":[{"name":"first::c","reason":"unavailable"}]}""", true)
        assertEquals(app.arguments, plan.launches.single().arguments)
        assertEquals(listOf("arg one", "--two"), app.arguments["args"])
        assertEquals(listOf("second::b", "second::a"), plan.launches.single().tests)
        assertEquals(Path.of("/work/results"), plan.launches.single().resultsFile)
        assertEquals(listOf("first::c: unavailable"), plan.skipped)
        assertTrue(HglDebugPlan.parse("""{"version":1,"launches":[],"skipped":[]}""", true).launches.isEmpty())
        try {
            HglDebugPlan.parse("""{"version":1,"launches":[{}],"skipped":[]}""", true)
            fail("Missing launch fields must fail the build")
        } catch (_: ExecutionException) { }
    }

    fun testOnlyTerminalResultsAdvanceTheGroup() {
        val tests = listOf("prices::a", "prices::b")
        val partial = listOf("start 0", "passed 0", "start 1", "passed 8", "garbage")
        assertFalse(HglDebugResults(tests, partial).complete)
        val failed = HglDebugResults(tests, partial + "failed 1 12 first\\nsecond")
        assertTrue(failed.complete)
        assertEquals(listOf("prices::b: failed 12 first\nsecond"), failed.failures)
        val unloaded = HglDebugResults(tests, partial + "unloaded 1 missing")
        assertTrue(unloaded.complete)
        assertEquals(listOf("prices::b: unloaded missing"), unloaded.failures)
    }
}
