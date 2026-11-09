package com.maguardian.security.util

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class LinkCheckerRegressionTest(private val url: String, private val expected: String) {
    @Test fun classifiesAddressAndKeepsRiskReasons() {
        val result = LinkChecker.check(url)
        assertEquals(url, expected, result.verdict.uppercase())
        assertTrue(result.score in 0..100)
        if (expected != "SEGURO") {
            assertTrue(url, result.score >= 25)
            assertTrue(url, result.reasons.isNotEmpty())
        } else {
            assertTrue(url, result.score < 25)
        }
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0} → {1}")
        fun cases(): List<Array<String>> = listOf(
            arrayOf("https://gov.br", "SEGURO"),
            arrayOf("HTTPS://GOV.BR", "SEGURO"),
            arrayOf("https://www.itau.com.br/", "SEGURO"),
            arrayOf("https://nubank.com.br", "SEGURO"),
            arrayOf("https://bb.com.br", "SEGURO"),
            arrayOf("https://banco.bradesco", "SEGURO"),
            arrayOf("https://santander.com.br", "SEGURO"),
            arrayOf("https://caixa.gov.br", "SEGURO"),
            arrayOf("https://inter.co", "SEGURO"),
            arrayOf("https://google.com", "SEGURO"),
            arrayOf("https://google.com/", "SEGURO"),
            arrayOf("https://itau.com.br:443", "SEGURO"),
            arrayOf("https://itau.com.br#inicio", "SEGURO"),
            arrayOf("https://example.org/ajuda", "SEGURO"),
            arrayOf("https://login.itau.com.br", "SEGURO"),
            arrayOf("https://servicos.gov.br/", "SEGURO"),
            arrayOf("https://bit.ly/abc", "SUSPEITO"),
            arrayOf("https://example.xyz", "SUSPEITO"),
            arrayOf("https://192.0.2.15/", "SUSPEITO"),
            arrayOf("http://192.0.2.15/login", "FRAUDULENTO"),
            arrayOf("https://itau-validar-conta.xyz/login", "FRAUDULENTO"),
            arrayOf("https://receita-regularize.xyz/login", "FRAUDULENTO"),
            arrayOf("https://example.org/arquivo.exe", "FRAUDULENTO"),
            arrayOf("https://example.org/arquivo.msi", "FRAUDULENTO"),
            arrayOf("https://example.org/arquivo.com", "FRAUDULENTO"),
            arrayOf("https://raw.githubusercontent.com/attacker/payload.ps1", "FRAUDULENTO"),
            arrayOf("https://g00gle.com/login", "FRAUDULENTO"),
            arrayOf("https://seguroitau.com/", "SUSPEITO"),
            arrayOf("https://seguroitau.com", "SUSPEITO"),
            arrayOf("https://falsoitau.com.br/", "SUSPEITO"),
            arrayOf("https://notgoogle.com/", "SUSPEITO"),
            arrayOf("https://fraudgov.br/", "FRAUDULENTO"),
            arrayOf("https://go.notgoogle.com/", "FRAUDULENTO"),
            arrayOf("https://www.bb.com.br.evil.example/login", "FRAUDULENTO"),
            arrayOf("https://google.com@evil.example/login", "FRAUDULENTO"),
            arrayOf("https://pix-premio.xyz/receber-pix", "FRAUDULENTO")
        )
    }
}
