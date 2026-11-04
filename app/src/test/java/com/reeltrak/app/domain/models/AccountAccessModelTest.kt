package com.reeltrak.app.domain.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountAccessModelTest {

    @Test
    fun `test AccountType fromString parsing`() {
        assertEquals(AccountType.TESTER, AccountType.fromString("TESTER"))
        assertEquals(AccountType.TESTER, AccountType.fromString("tester"))
        assertEquals(AccountType.TESTER, AccountType.fromString(" Tester "))
        assertEquals(AccountType.NORMAL, AccountType.fromString("NORMAL"))
        assertEquals(AccountType.NORMAL, AccountType.fromString("normal"))
        assertEquals(AccountType.NORMAL, AccountType.fromString(null))
        assertEquals(AccountType.NORMAL, AccountType.fromString(""))
        assertEquals(AccountType.NORMAL, AccountType.fromString("unknown_value"))
    }

    @Test
    fun `test AccountStatus fromString parsing`() {
        assertEquals(AccountStatus.RESTRICTED, AccountStatus.fromString("RESTRICTED"))
        assertEquals(AccountStatus.RESTRICTED, AccountStatus.fromString("restricted"))
        assertEquals(AccountStatus.ACTIVE, AccountStatus.fromString("ACTIVE"))
        assertEquals(AccountStatus.ACTIVE, AccountStatus.fromString("active"))
        assertEquals(AccountStatus.ACTIVE, AccountStatus.fromString(null))
        assertEquals(AccountStatus.ACTIVE, AccountStatus.fromString(""))
    }

    @Test
    fun `test AccountAccessInfo default values and helper properties`() {
        val defaultInfo = AccountAccessInfo.DEFAULT
        assertEquals(AccountStatus.ACTIVE, defaultInfo.accountStatus)
        assertEquals(AccountType.NORMAL, defaultInfo.accountType)
        assertFalse(defaultInfo.isLoading)
        assertFalse(defaultInfo.isRestricted)
        assertFalse(defaultInfo.isTester)

        val testerActiveInfo = AccountAccessInfo(
            accountStatus = AccountStatus.ACTIVE,
            accountType = AccountType.TESTER,
            isLoading = false
        )
        assertFalse(testerActiveInfo.isRestricted)
        assertTrue(testerActiveInfo.isTester)

        val testerRestrictedInfo = AccountAccessInfo(
            accountStatus = AccountStatus.RESTRICTED,
            accountType = AccountType.TESTER,
            isLoading = false
        )
        assertTrue("RESTRICTED status must mark account as restricted even if accountType is TESTER", testerRestrictedInfo.isRestricted)
        assertTrue(testerRestrictedInfo.isTester)
    }

    @Test
    fun `test loading state prevents premature tester classification`() {
        val loadingInfo = AccountAccessInfo.LOADING
        assertTrue(loadingInfo.isLoading)
        assertFalse(loadingInfo.isRestricted)
    }
}
