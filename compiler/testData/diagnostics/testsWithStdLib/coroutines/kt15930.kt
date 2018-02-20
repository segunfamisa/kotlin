// SKIP_TXT
// WITH_RUNTIME

import kotlin.coroutines.experimental.intrinsics.*

class A {
    var isMinusAssignCalled = false
    operator suspend fun minusAssign(y: String): Unit = suspendCoroutineOrReturn { x ->
        if (y != "56") return@suspendCoroutineOrReturn Unit
        isMinusAssignCalled = true
        x.resume(Unit)
        COROUTINE_SUSPENDED
    }
}

val a = A()

suspend fun foo5() {
    a -= "56"
    if (!a.isMinusAssignCalled) throw RuntimeException("fail 6")
}