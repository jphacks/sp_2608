package com.theacar.android.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class NetworkPrintTest {
    @Test fun storeLinksPointAtEachApp() {
        assertEquals("jp.co.sharp.printsystem.networkprint", PrintRoute.APP.packageName)
        assertEquals("jp.naver.line.android", PrintRoute.LINE.packageName)
        assertEquals("image/jpeg", NetworkPrint.MIME_TYPE)
        assertEquals("market://details?id=jp.co.sharp.printsystem.networkprint", PrintRoute.APP.storeUri)
        assertEquals("https://play.google.com/store/apps/details?id=jp.naver.line.android", PrintRoute.LINE.storeWebUrl)
        assertEquals("https://line.me/R/ti/p/%40wog2593e", NetworkPrint.LINE_ADD_FRIEND_URL)
    }

    @Test fun ticketMessageDependsOnRouteAndDirectLaunch() {
        assertTrue(PrintRoute.APP.ticketMessage(direct = true).startsWith("ネットワークプリントに渡しました"))
        assertTrue(PrintRoute.LINE.ticketMessage(direct = true).contains("ユーザー番号"))
        assertEquals("共有先で ネットワークプリント を選んでください", PrintRoute.APP.ticketMessage(direct = false))
        assertEquals("共有先で LINE を選んでください", PrintRoute.LINE.ticketMessage(direct = false))
        assertNotEquals(PrintRoute.APP.ticketMessage(true), PrintRoute.LINE.ticketMessage(true))
    }

    @Test fun requiredTitleNamesTheApp() {
        assertEquals("LINE が必要です", PrintRoute.LINE.requiredTitle)
        assertEquals("印刷アプリが必要です", PrintRoute.APP.requiredTitle)
    }
}
