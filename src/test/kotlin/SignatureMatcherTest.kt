import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SignatureMatcherTest {

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private fun callSite(
        calleeFqName: String,
        dispatchReceiverType: String? = null,
        extensionReceiverType: String? = null,
        returnType: String = "kotlin.Unit",
        argumentTypes: List<String> = emptyList(),
        line: Int = 1,
        column: Int = 1,
    ) = CallSiteAst(
        calleeFqName = calleeFqName,
        dispatchReceiverType = dispatchReceiverType,
        extensionReceiverType = extensionReceiverType,
        returnType = returnType,
        argumentTypes = argumentTypes,
        line = line,
        column = column,
    )

    // Extension function: kotlin.text.trim — receiver is the extension receiver.
    private val stringTrim = callSite(
        calleeFqName = "kotlin.text.trim",
        extensionReceiverType = "kotlin.String",
        returnType = "kotlin.String",
    )

    // Member function on String: startsWith(prefix, ignoreCase).
    private val stringStartsWith = callSite(
        calleeFqName = "kotlin.String.startsWith",
        dispatchReceiverType = "kotlin.String",
        returnType = "kotlin.Boolean",
        argumentTypes = listOf("kotlin.String", "kotlin.Boolean"),
    )

    // Java Stream.filter — dispatch receiver, generic argument type.
    private val streamFilter = callSite(
        calleeFqName = "java.util.stream.Stream.filter",
        dispatchReceiverType = "java.util.stream.Stream<nl.stokpop.memory.domain.ClassGrowth>",
        returnType = "java.util.stream.Stream<nl.stokpop.memory.domain.ClassGrowth>",
        argumentTypes = listOf("java.util.function.Predicate<nl.stokpop.memory.domain.ClassGrowth>"),
    )

    // Constructor call.
    private val exceptionInit = callSite(
        calleeFqName = "nl.stokpop.memory.MemoryCheckException.<init>",
        returnType = "nl.stokpop.memory.MemoryCheckException",
        argumentTypes = listOf("kotlin.String"),
    )

    // Top-level function with no receiver.
    private val println = callSite(
        calleeFqName = "kotlin.io.println",
        returnType = "kotlin.Unit",
        argumentTypes = listOf("kotlin.Any?"),
    )

    // ── parseSig ──────────────────────────────────────────────────────────────

    @Test
    fun `parseSig full signature`() {
        val sig = parseSig("kotlin.String#replaceFirst(kotlin.String,kotlin.String)")
        assertEquals("kotlin.String", sig.receiverType)
        assertEquals("replaceFirst", sig.methodName)
        assertEquals(listOf("kotlin.String", "kotlin.String"), sig.paramTypes)
    }

    @Test
    fun `parseSig wildcard receiver`() {
        val sig = parseSig("_#trim()")
        assertEquals(null, sig.receiverType)
        assertEquals("trim", sig.methodName)
        assertEquals(emptyList<String>(), sig.paramTypes)
    }

    @Test
    fun `parseSig star params`() {
        val sig = parseSig("kotlin.String#trim(*)")
        assertEquals("kotlin.String", sig.receiverType)
        assertEquals("trim", sig.methodName)
        assertEquals(null, sig.paramTypes)   // null = any params
    }

    @Test
    fun `parseSig no receiver prefix`() {
        val sig = parseSig("trim()")
        assertEquals(null, sig.receiverType)
        assertEquals("trim", sig.methodName)
    }

    @Test
    fun `parseSig constructor`() {
        val sig = parseSig("nl.stokpop.memory.MemoryCheckException#<init>(kotlin.String)")
        assertEquals("nl.stokpop.memory.MemoryCheckException", sig.receiverType)
        assertEquals("<init>", sig.methodName)
        assertEquals(listOf("kotlin.String"), sig.paramTypes)
    }

    @Test
    fun `parseSig generic param type`() {
        val sig = parseSig(
            "java.util.stream.Stream#filter(java.util.function.Predicate<nl.stokpop.memory.domain.ClassGrowth>)"
        )
        assertEquals(
            listOf("java.util.function.Predicate<nl.stokpop.memory.domain.ClassGrowth>"),
            sig.paramTypes,
        )
    }

    @Test
    fun `parseSig generic with multiple type args does not split on inner commas`() {
        val sig = parseSig("kotlin.collections.Map#get(kotlin.collections.Map<kotlin.String,kotlin.Int>)")
        assertEquals(listOf("kotlin.collections.Map<kotlin.String,kotlin.Int>"), sig.paramTypes)
    }

    @Test
    fun `parseSig throws on missing parentheses`() {
        assertThrows<IllegalArgumentException> { parseSig("kotlin.String#trim") }
    }

    @Test
    fun `parseSig throws on empty method name`() {
        assertThrows<IllegalArgumentException> { parseSig("kotlin.String#()") }
    }

    // ── Extension function matching ───────────────────────────────────────────

    @Test
    fun `matches extension function by exact signature`() {
        assertTrue(stringTrim.matchesSig("kotlin.String#trim()"))
    }

    @Test
    fun `does not match extension function with wrong receiver`() {
        assertFalse(stringTrim.matchesSig("kotlin.StringBuilder#trim()"))
    }

    @Test
    fun `does not match extension function with wrong name`() {
        assertFalse(stringTrim.matchesSig("kotlin.String#trimEnd()"))
    }

    @Test
    fun `does not match extension function when params expected but none present`() {
        assertFalse(stringTrim.matchesSig("kotlin.String#trim(kotlin.Boolean)"))
    }

    // ── Member function matching ──────────────────────────────────────────────

    @Test
    fun `matches member function by dispatch receiver`() {
        assertTrue(stringStartsWith.matchesSig("kotlin.String#startsWith(kotlin.String,kotlin.Boolean)"))
    }

    @Test
    fun `does not match member function with wrong arg types`() {
        assertFalse(stringStartsWith.matchesSig("kotlin.String#startsWith(kotlin.String,kotlin.String)"))
    }

    @Test
    fun `does not match member function with wrong arg count`() {
        assertFalse(stringStartsWith.matchesSig("kotlin.String#startsWith(kotlin.String)"))
    }

    // ── Wildcard: receiver ────────────────────────────────────────────────────

    @Test
    fun `wildcard receiver matches extension call`() {
        assertTrue(stringTrim.matchesSig("_#trim()"))
    }

    @Test
    fun `wildcard receiver matches dispatch call`() {
        assertTrue(stringStartsWith.matchesSig("_#startsWith(kotlin.String,kotlin.Boolean)"))
    }

    @Test
    fun `no-prefix signature is equivalent to wildcard receiver`() {
        assertTrue(stringTrim.matchesSig("trim()"))
    }

    // ── Wildcard: method name ─────────────────────────────────────────────────

    @Test
    fun `wildcard method name matches any name`() {
        assertTrue(stringTrim.matchesSig("kotlin.String#_()"))
    }

    @Test
    fun `wildcard method name respects param count`() {
        assertFalse(stringTrim.matchesSig("kotlin.String#_(kotlin.String)"))
    }

    // ── Wildcard: param types ─────────────────────────────────────────────────

    @Test
    fun `underscore param matches any single param type`() {
        assertTrue(stringStartsWith.matchesSig("kotlin.String#startsWith(_,_)"))
    }

    @Test
    fun `underscore param does not change arity requirement`() {
        assertFalse(stringStartsWith.matchesSig("kotlin.String#startsWith(_)"))
    }

    @Test
    fun `star params matches zero params`() {
        assertTrue(stringTrim.matchesSig("kotlin.String#trim(*)"))
    }

    @Test
    fun `star params matches multiple params`() {
        assertTrue(stringStartsWith.matchesSig("kotlin.String#startsWith(*)"))
    }

    @Test
    fun `star params with wildcard method and receiver matches anything`() {
        assertTrue(streamFilter.matchesSig("_#_(*)"))
    }

    // ── Erasure ───────────────────────────────────────────────────────────────

    @Test
    fun `erased receiver type matches generic actual`() {
        assertTrue(streamFilter.matchesSig("java.util.stream.Stream#filter(_)"))
    }

    @Test
    fun `exact generic receiver type must match type args`() {
        assertFalse(streamFilter.matchesSig("java.util.stream.Stream<kotlin.String>#filter(_)"))
    }

    @Test
    fun `erased param type matches generic actual`() {
        assertTrue(streamFilter.matchesSig("java.util.stream.Stream#filter(java.util.function.Predicate)"))
    }

    // ── Generic types ─────────────────────────────────────────────────────────

    @Test
    fun `matches generic dispatch receiver type exactly`() {
        assertTrue(
            streamFilter.matchesSig(
                "java.util.stream.Stream<nl.stokpop.memory.domain.ClassGrowth>#filter(_)"
            )
        )
    }

    @Test
    fun `does not match generic dispatch receiver with wrong type arg`() {
        assertFalse(
            streamFilter.matchesSig(
                "java.util.stream.Stream<kotlin.String>#filter(_)"
            )
        )
    }

    @Test
    fun `matches generic param type exactly`() {
        assertTrue(
            streamFilter.matchesSig(
                "java.util.stream.Stream#filter(java.util.function.Predicate<nl.stokpop.memory.domain.ClassGrowth>)"
            )
        )
    }

    @Test
    fun `underscore matches generic param type`() {
        assertTrue(streamFilter.matchesSig("java.util.stream.Stream#filter(_)"))
    }

    // ── Constructor ───────────────────────────────────────────────────────────

    @Test
    fun `matches constructor by fqn and param type`() {
        assertTrue(
            exceptionInit.matchesSig(
                "nl.stokpop.memory.MemoryCheckException#<init>(kotlin.String)"
            )
        )
    }

    @Test
    fun `does not match constructor with wrong class`() {
        assertFalse(
            exceptionInit.matchesSig(
                "nl.stokpop.memory.SomeOtherException#<init>(kotlin.String)"
            )
        )
    }

    // ── Top-level function (no receiver) ─────────────────────────────────────

    @Test
    fun `wildcard receiver matches call with no receiver`() {
        assertTrue(println.matchesSig("_#println(kotlin.Any?)"))
    }

    @Test
    fun `typed receiver does not match call with no receiver`() {
        assertFalse(println.matchesSig("kotlin.String#println(kotlin.Any?)"))
    }

    // ── List extension ────────────────────────────────────────────────────────

    @Test
    fun `list matchesSig filters correctly`() {
        val calls = listOf(stringTrim, stringStartsWith, streamFilter, exceptionInit, println)
        assertEquals(listOf(stringTrim), calls.matchesSig("kotlin.String#trim()"))
    }

    @Test
    fun `list matchesSig with wildcard receiver returns all matching names`() {
        val calls = listOf(stringTrim, stringStartsWith, streamFilter, exceptionInit)
        // All four have a method name that differs; only stringTrim has name "trim".
        assertEquals(listOf(stringTrim), calls.matchesSig("_#trim()"))
    }
}
