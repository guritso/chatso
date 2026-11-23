package com.example

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.example.crypto.CryptoEngine
import com.example.data.AppLanguage
import com.example.data.DeliveryStatus
import com.example.data.LocalIdentityStore
import com.example.data.LocaleHelper
import com.example.data.MediaStorageHelper
import com.example.data.ThemePalette
import com.example.ui.theme.AppIconManager
import com.example.ui.theme.buildChatsoColorScheme
import com.example.ui.theme.spec
import android.app.Notification
import android.app.NotificationManager
import com.example.network.ChatsoNotificationActionReceiver
import com.example.network.ChatsoNotificationHelper
import com.example.network.NetworkUtils
import com.example.network.UpdateCheckResult
import com.example.network.UpdateChecker
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppBasicsRobolectricTest {

    @Test
    fun `app name matches chatso`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("chatso", context.getString(R.string.app_name))
    }

    @Test
    fun `locale helper switches languages and resolves localized strings`() {
        val base = ApplicationProvider.getApplicationContext<Context>()

        LocaleHelper.saveLanguage(base, AppLanguage.EN)
        val enCtx = LocaleHelper.wrapContext(base)
        assertEquals("On Network", enCtx.getString(R.string.connect_lan_tab))
        assertEquals("Visible", enCtx.getString(R.string.status_visible))

        LocaleHelper.saveLanguage(base, AppLanguage.ES)
        val esCtx = LocaleHelper.wrapContext(base)
        assertEquals("En Red", esCtx.getString(R.string.connect_lan_tab))
        assertEquals("Invisible", esCtx.getString(R.string.status_invisible))

        LocaleHelper.saveLanguage(base, AppLanguage.FR)
        val frCtx = LocaleHelper.wrapContext(base)
        assertEquals("Sur Réseau", frCtx.getString(R.string.connect_lan_tab))

        LocaleHelper.saveLanguage(base, AppLanguage.DE)
        val deCtx = LocaleHelper.wrapContext(base)
        assertEquals("Im Netz", deCtx.getString(R.string.connect_lan_tab))
        assertEquals("Sichtbar", deCtx.getString(R.string.status_visible))

        LocaleHelper.saveLanguage(base, AppLanguage.PT)
        val ptCtx = LocaleHelper.wrapContext(base)
        assertEquals("Na Rede", ptCtx.getString(R.string.connect_lan_tab))
        assertEquals("Visível", ptCtx.getString(R.string.status_visible))

        LocaleHelper.saveLanguage(base, AppLanguage.ZH)
        val zhCtx = LocaleHelper.wrapContext(base)
        assertEquals("局域网内", zhCtx.getString(R.string.connect_lan_tab))
        assertEquals("可见", zhCtx.getString(R.string.status_visible))

        LocaleHelper.saveLanguage(base, AppLanguage.JA)
        val jaCtx = LocaleHelper.wrapContext(base)
        assertEquals("ネットワーク内", jaCtx.getString(R.string.connect_lan_tab))
        assertEquals("公開", jaCtx.getString(R.string.status_visible))

        LocaleHelper.saveLanguage(base, AppLanguage.SYSTEM)
    }

    @Test
    fun `update checker compares semantic versions and extracts apk asset`() {
        assertTrue(UpdateChecker.isRemoteVersionNewer("v1.0.1", "1.0.0"))
        assertTrue(UpdateChecker.isRemoteVersionNewer("1.2.0", "1.0.9"))
        assertFalse(UpdateChecker.isRemoteVersionNewer("1.0.0", "1.0.0"))
        assertFalse(UpdateChecker.isRemoteVersionNewer("0.9.9", "1.0.0"))

        val releaseJson = """
            {
              "tag_name": "v1.1.0",
              "html_url": "https://github.com/guritso/chatso/releases/tag/v1.1.0",
              "body": "Bug fixes and video player",
              "assets": [
                {
                  "name": "chatso-1.1.0.apk",
                  "browser_download_url": "https://github.com/guritso/chatso/releases/download/v1.1.0/chatso-1.1.0.apk"
                }
              ]
            }
        """.trimIndent()
        val res = UpdateChecker.parseReleaseJson(releaseJson, "1.0.0")
        assertTrue(res is UpdateCheckResult.UpdateAvailable)
        val avail = res as UpdateCheckResult.UpdateAvailable
        assertEquals("1.1.0", avail.latestVersion)
        assertEquals(
            "https://github.com/guritso/chatso/releases/download/v1.1.0/chatso-1.1.0.apk",
            avail.downloadUrl
        )

        // Verify ABI-split release picks the exact matching APK for v7, arm64, and x86_64
        val splitReleaseJson = """
            {
              "tag_name": "v1.3.0",
              "html_url": "https://github.com/guritso/chatso/releases/tag/v1.3.0",
              "body": "ABI splits + R8",
              "assets": [
                {
                  "name": "Chatso-v1.3.0-armeabi-v7a-release.apk",
                  "browser_download_url": "https://github.com/guritso/chatso/releases/download/v1.3.0/Chatso-v1.3.0-armeabi-v7a-release.apk"
                },
                {
                  "name": "Chatso-v1.3.0-arm64-v8a-release.apk",
                  "browser_download_url": "https://github.com/guritso/chatso/releases/download/v1.3.0/Chatso-v1.3.0-arm64-v8a-release.apk"
                },
                {
                  "name": "Chatso-v1.3.0-x86_64-release.apk",
                  "browser_download_url": "https://github.com/guritso/chatso/releases/download/v1.3.0/Chatso-v1.3.0-x86_64-release.apk"
                }
              ]
            }
        """.trimIndent()

        val arm64Res = UpdateChecker.parseReleaseJson(splitReleaseJson, "1.2.5", "arm64-v8a") as UpdateCheckResult.UpdateAvailable
        assertEquals("Chatso-v1.3.0-arm64-v8a-release.apk", arm64Res.assetName)
        assertEquals(
            "https://github.com/guritso/chatso/releases/download/v1.3.0/Chatso-v1.3.0-arm64-v8a-release.apk",
            arm64Res.downloadUrl
        )

        val v7Res = UpdateChecker.parseReleaseJson(splitReleaseJson, "1.2.5", "armeabi-v7a") as UpdateCheckResult.UpdateAvailable
        assertEquals("Chatso-v1.3.0-armeabi-v7a-release.apk", v7Res.assetName)
        assertEquals(
            "https://github.com/guritso/chatso/releases/download/v1.3.0/Chatso-v1.3.0-armeabi-v7a-release.apk",
            v7Res.downloadUrl
        )

        val x86Res = UpdateChecker.parseReleaseJson(splitReleaseJson, "1.2.5", "x86_64") as UpdateCheckResult.UpdateAvailable
        assertEquals("Chatso-v1.3.0-x86_64-release.apk", x86Res.assetName)
        assertEquals(
            "https://github.com/guritso/chatso/releases/download/v1.3.0/Chatso-v1.3.0-x86_64-release.apk",
            x86Res.downloadUrl
        )

        // Verify short alias asset naming (v7, 64, x86) also resolves directly
        val shortAliasJson = """
            {
              "tag_name": "v1.3.1",
              "html_url": "https://github.com/guritso/chatso/releases/tag/v1.3.1",
              "assets": [
                { "name": "chatso-v7.apk", "browser_download_url": "https://example.com/v7.apk" },
                { "name": "chatso-64.apk", "browser_download_url": "https://example.com/64.apk" },
                { "name": "chatso-x86.apk", "browser_download_url": "https://example.com/x86.apk" }
              ]
            }
        """.trimIndent()
        assertEquals("https://example.com/v7.apk", (UpdateChecker.parseReleaseJson(shortAliasJson, "1.2.5", "armeabi-v7a") as UpdateCheckResult.UpdateAvailable).downloadUrl)
        assertEquals("https://example.com/64.apk", (UpdateChecker.parseReleaseJson(shortAliasJson, "1.2.5", "arm64-v8a") as UpdateCheckResult.UpdateAvailable).downloadUrl)
        assertEquals("https://example.com/x86.apk", (UpdateChecker.parseReleaseJson(shortAliasJson, "1.2.5", "x86_64") as UpdateCheckResult.UpdateAvailable).downloadUrl)

        // Verify installed ABI detection prioritizes the APK's nativeLibraryDir / process bitness
        assertEquals(
            UpdateChecker.ABI_ARMEABI_V7A,
            UpdateChecker.detectDeviceAbi(
                supportedAbis = arrayOf("arm64-v8a", "armeabi-v7a"),
                is64Bit = false,
                nativeLibraryDir = "/data/app/~~abc/com.example/lib/arm"
            )
        )
        assertEquals(
            UpdateChecker.ABI_ARM64_V8A,
            UpdateChecker.detectDeviceAbi(
                supportedAbis = arrayOf("arm64-v8a", "armeabi-v7a"),
                is64Bit = true,
                nativeLibraryDir = "/data/app/~~abc/com.example/lib/arm64"
            )
        )
        assertEquals(
            UpdateChecker.ABI_X86_64,
            UpdateChecker.detectDeviceAbi(
                supportedAbis = arrayOf("x86_64", "x86"),
                is64Bit = true,
                nativeLibraryDir = "/data/app/~~abc/com.example/lib/x86_64"
            )
        )
    }

    @Test
    fun `notification uses monochrome drawable icon and handles missing avatar safely`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val notif = ChatsoNotificationHelper.buildServiceNotification(context, connectedCount = 2)
        assertNotNull(notif)
        assertEquals(R.drawable.ic_notification_chatso, notif.smallIcon.resId)
        ChatsoNotificationHelper.showIncomingMessageNotification(
            context = context,
            peerId = "peer_notif_1",
            senderName = "Guritso",
            preview = "Oi!",
            avatarPath = null,
            localUsername = "Tester"
        )
        ChatsoNotificationHelper.showIncomingReactionNotification(
            context = context,
            peerId = "peer_notif_1",
            messageId = "m_1",
            senderName = "Guritso",
            emoji = "❤️",
            reactedSnippet = "Oi!",
            avatarPath = null,
            localUsername = "Tester"
        )
        NetworkUtils.isIgnoringBatteryOptimizations(context)
    }

    @Test
    fun `conversation notification has shortcutId, mark read, and inline reply remote input`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val shadowApp = org.robolectric.Shadows.shadowOf(context as android.app.Application)
        shadowApp.grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val shadowNm = org.robolectric.Shadows.shadowOf(nm)

        val peerId = "peer_conv_notif"
        ChatsoNotificationHelper.cancelPeerNotification(context, peerId)
        ChatsoNotificationHelper.showIncomingMessageNotification(
            context = context,
            peerId = peerId,
            senderName = "Marina",
            preview = "Olá!",
            avatarPath = null,
            localUsername = "Você"
        )

        val posted: Notification? = shadowNm.getNotification(peerId.hashCode())
        assertNotNull(posted)
        assertEquals("conv_$peerId", posted!!.shortcutId)
        assertEquals(R.drawable.ic_notification_chatso, posted.smallIcon.resId)
        assertEquals(2, posted.actions?.size)
        val replyAction = posted.actions[1]
        assertNotNull(replyAction.remoteInputs)
        assertEquals(ChatsoNotificationHelper.KEY_TEXT_REPLY, replyAction.remoteInputs[0].resultKey)

        // Verify background mark-read via ChatsoNotificationActionReceiver works when app is backgrounded
        val app = context as ChatsoApplication
        val dao = app.container.database.chatDao()
        dao.deleteAllMessagesForPeer(peerId)
        dao.deletePeer(peerId)
        app.container.isAppInForeground = false
        val mat = CryptoEngine.createIdentityMaterial()
        app.container.repository.onPeerHandshaked(
            peerId = peerId,
            publicKeyBase64 = mat.publicKeyBase64,
            remoteIp = "10.8.0.55",
            remoteListenPort = 42850,
            username = "Marina",
            avatarBase64 = null
        )
        app.container.repository.onIncomingTextMessage(
            peerId = peerId,
            messageId = "msg_notif_unread_1",
            text = "Olá!",
            selfDestructSec = 0,
            createdAt = System.currentTimeMillis()
        )
        assertEquals(1, dao.getPeerById(peerId)!!.unreadCount)

        app.container.repository.markConversationRead(peerId, fromNotification = true)
        assertEquals(0, dao.getPeerById(peerId)!!.unreadCount)
        assertEquals(DeliveryStatus.READ.name, dao.getMessageById("msg_notif_unread_1")!!.status)

        dao.deleteAllMessagesForPeer(peerId)
        dao.deletePeer(peerId)
        ChatsoNotificationHelper.cancelPeerNotification(context, peerId)
    }

    @Test
    fun `theme palette defaults to purple persists selection advanced mode and switches app icon`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = LocalIdentityStore(context)

        assertEquals(ThemePalette.PURPLE, store.getSnapshot().themePalette)
        assertFalse(store.getSnapshot().themeAdvanced)
        assertEquals(12, ThemePalette.entries.size)
        assertEquals("1.2.7", BuildConfig.VERSION_NAME)
        assertEquals(19, BuildConfig.VERSION_CODE)

        val lightPrimaries = ThemePalette.entries.map { palette ->
            store.setThemePalette(palette)
            assertEquals(palette, store.getSnapshot().themePalette)
            val scheme = buildChatsoColorScheme(darkTheme = false, palette = palette, advanced = false)
            assertEquals(palette.spec().light.primary, scheme.primary)
            scheme.primary
        }.toSet()

        assertEquals(ThemePalette.entries.size, lightPrimaries.size)

        val darkPrimaries = ThemePalette.entries.map { palette ->
            val scheme = buildChatsoColorScheme(darkTheme = true, palette = palette, advanced = false)
            // Verify dark background is elevated charcoal rather than harsh near-black
            assertTrue(scheme.background.red >= 0.09f)
            scheme.primary
        }.toSet()
        assertEquals(ThemePalette.entries.size, darkPrimaries.size)

        // Verify advanced mode tints backgrounds, surfaces, and secondary colors
        store.setThemeAdvanced(true)
        assertTrue(store.getSnapshot().themeAdvanced)
        val basicDark = buildChatsoColorScheme(darkTheme = true, palette = ThemePalette.ORANGE, advanced = false)
        val advDark = buildChatsoColorScheme(darkTheme = true, palette = ThemePalette.ORANGE, advanced = true)
        assertTrue(basicDark.background != advDark.background)
        assertTrue(basicDark.surface != advDark.surface)
        assertTrue(basicDark.secondaryContainer != advDark.secondaryContainer)

        // Verify default icon is dark-navy DEFAULT_ALIAS_CLASS when full theme is OFF,
        // and switches to the palette alias only when full theme is ON + background flush runs
        assertEquals(
            AppIconManager.DEFAULT_ALIAS_CLASS,
            AppIconManager.resolveTargetAlias(ThemePalette.PURPLE, fullThemeEnabled = false)
        )
        assertEquals(
            AppIconManager.DEFAULT_ALIAS_CLASS,
            AppIconManager.resolveTargetAlias(ThemePalette.ORANGE, fullThemeEnabled = false)
        )
        assertEquals(
            ThemePalette.PURPLE.aliasClassName,
            AppIconManager.resolveTargetAlias(ThemePalette.PURPLE, fullThemeEnabled = true)
        )

        AppIconManager.applyAlias(context, AppIconManager.DEFAULT_ALIAS_CLASS)
        AppIconManager.clearTemporarySuppression()
        AppIconManager.scheduleIconSync(ThemePalette.ORANGE, fullThemeEnabled = false)
        AppIconManager.flushPendingIconIfNeeded(context)
        assertTrue(AppIconManager.isDefaultIconActive(context))
        assertFalse(AppIconManager.isPaletteIconActive(context, ThemePalette.ORANGE))

        AppIconManager.scheduleIconSync(ThemePalette.ORANGE, fullThemeEnabled = true)
        // Still default dark-navy icon while in foreground until background flush runs
        assertTrue(AppIconManager.isDefaultIconActive(context))
        AppIconManager.flushPendingIconIfNeeded(context)
        assertTrue(AppIconManager.isPaletteIconActive(context, ThemePalette.ORANGE))
        assertFalse(AppIconManager.isDefaultIconActive(context))

        // Turning full theme back OFF schedules revert to dark-navy default icon
        AppIconManager.scheduleIconSync(ThemePalette.ORANGE, fullThemeEnabled = false)
        AppIconManager.flushPendingIconIfNeeded(context)
        assertTrue(AppIconManager.isDefaultIconActive(context))
        assertFalse(AppIconManager.isPaletteIconActive(context, ThemePalette.ORANGE))

        // Simulate app upgrade where an old palette alias remained ENABLED alongside DEFAULT_ALIAS_CLASS
        context.packageManager.setComponentEnabledSetting(
            android.content.ComponentName(context.packageName, ThemePalette.PURPLE.aliasClassName),
            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            android.content.pm.PackageManager.DONT_KILL_APP
        )
        assertTrue(AppIconManager.isDefaultIconActive(context))
        assertTrue(AppIconManager.isPaletteIconActive(context, ThemePalette.PURPLE))
        AppIconManager.scheduleIconSync(ThemePalette.PURPLE, fullThemeEnabled = false)
        AppIconManager.flushPendingIconIfNeeded(context)
        assertTrue(AppIconManager.isDefaultIconActive(context))
        assertFalse(AppIconManager.isPaletteIconActive(context, ThemePalette.PURPLE))
    }

    @Test
    fun `profile avatar saves and encodes at high resolution`(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val mediaStorage = MediaStorageHelper(context)
        val srcFile = File.createTempFile("hi_res_src", ".png", context.cacheDir)
        try {
            val bmp = android.graphics.Bitmap.createBitmap(1200, 1200, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            canvas.drawColor(android.graphics.Color.rgb(32, 96, 200))
            srcFile.outputStream().use { out ->
                bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            }
            bmp.recycle()

            val savedPath = mediaStorage.saveProfileAvatar(android.net.Uri.fromFile(srcFile))
            assertNotNull(savedPath)
            val savedBounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(savedPath, savedBounds)
            assertTrue("Expected saved avatar >= 800px, got ${savedBounds.outWidth}", savedBounds.outWidth >= 800)

            val wireB64 = mediaStorage.encodeAvatarBase64(savedPath)
            assertNotNull(wireB64)
            val wireBytes = Base64.decode(wireB64, Base64.NO_WRAP)
            val wireBounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(wireBytes, 0, wireBytes.size, wireBounds)
            assertTrue("Expected wire avatar >= 512px, got ${wireBounds.outWidth}", wireBounds.outWidth >= 512)

            savedPath?.let { File(it).delete() }
            Unit
        } finally {
            srcFile.delete()
        }
    }
}
