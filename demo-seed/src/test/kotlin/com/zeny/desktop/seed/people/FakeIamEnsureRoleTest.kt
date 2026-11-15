package com.zeny.desktop.seed.people

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** O falso tem de se comportar como o adaptador do SDK (`SdkUserGatewayRoleAtaqueTest`). */
class FakeIamEnsureRoleTest {
    private val wanted = mapOf("a.read" to "ALL", "b.write" to "OWN")

    @Test
    fun createsThenKeepsEqualWithoutBumpingTheVersion() =
        runTest {
            val iam = FakeIam()
            val first = iam.ensureRole("Recepção demo", "D", wanted)
            val reversed = wanted.entries.reversed().associate { it.key to it.value }
            val second = iam.ensureRole("RECEPÇÃO DEMO", "D", reversed)
            assertEquals(RoleEnsured(first.id, created = true), first)
            assertEquals(RoleEnsured(first.id, created = false), second)
            assertEquals(1, iam.customRoles.getValue(first.id).version)
        }

    @Test
    fun rewritesToExactlyTheRequestedSetAndDescriptionOnSupersetSubsetOrScopeChange() =
        runTest {
            val iam = FakeIam()
            val id = iam.ensureRole("R", "antiga", wanted + ("extra.x" to "ALL")).id
            iam.ensureRole("R", "nova", wanted)
            val role = iam.customRoles.getValue(id)
            assertEquals(wanted, role.grants)
            assertEquals("nova", role.description)
            iam.ensureRole("R", "nova", mapOf("a.read" to "ALL"))
            assertEquals(mapOf("a.read" to "ALL"), role.grants)
            iam.ensureRole("R", "nova", mapOf("a.read" to "OWN"))
            assertEquals(mapOf("a.read" to "OWN"), role.grants)
            assertEquals(4, role.version)
        }

    @Test
    fun reactivatesAnInactiveRole() =
        runTest {
            val iam = FakeIam()
            val id = iam.ensureRole("R", "D", wanted).id
            iam.customRoles.getValue(id).active = false
            assertEquals(RoleEnsured(id, created = false), iam.ensureRole("R", "D", wanted))
            assertEquals(true, iam.customRoles.getValue(id).active)
        }

    @Test
    fun doesNotMatchByContainmentAndCreatesDistinctIds() =
        runTest {
            val iam = FakeIam()
            val a = iam.ensureRole("Recepção demo 2", "D", wanted)
            val b = iam.ensureRole("Recepção demo", "D", wanted)
            assertEquals(true, b.created)
            assertEquals(2, iam.customRoles.size)
            assertEquals(false, a.id == b.id)
        }
}
