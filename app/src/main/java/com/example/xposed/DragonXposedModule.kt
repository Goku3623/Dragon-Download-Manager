package com.example.xposed

import android.content.Context
import android.content.SharedPreferences

enum class LSPosedContainerType(val label: String) {
    NONE("Standard Android Runtime"),
    LSPOSED_ZYGISK("LSPosed (Zygisk) Container"),
    LSPOSED_RIRU("LSPosed (Riru) Container"),
    LSPOSED_HOOKED("LSPosed Hooked Module Container"),
    LSPATCH_CONTAINER("LSPatch Non-Root Container"),
    XPOSED_LEGACY("Xposed Legacy Container")
}

data class LSPosedRuntimeDetection(
    val isInContainer: Boolean,
    val containerType: LSPosedContainerType,
    val isSelfHookActive: Boolean,
    val isXposedClassLoaderInjected: Boolean,
    val isNativeBridgeDetected: Boolean,
    val isFrameworkFilesPresent: Boolean,
    val apiVersion: Int,
    val isWorldReadablePrefsSupported: Boolean,
    val details: String
)

interface DragonXposedModuleInterface {
    val modulePackageId: String
    val entryPointClassName: String
    val configProviderAuthority: String

    fun isInsideLSPosedContainer(context: Context? = null): Boolean
    fun getContainerType(context: Context? = null): LSPosedContainerType
    fun getActiveXposedApiVersion(): Int
    fun isSelfHookActive(): Boolean
    fun getConfiguredScopePackages(context: Context): List<String>
    fun openAdBlockerSharedPrefs(context: Context): Pair<SharedPreferences, Boolean>
}

object DragonXposedModule : DragonXposedModuleInterface {

    override val modulePackageId: String = "com.android.ddm"
    override val entryPointClassName: String = "com.example.xposed.DragonLSPosedEntry"
    override val configProviderAuthority: String = "com.android.ddm.lsposed.provider"

    const val AD_BLOCKER_PREFS_NAME = "dragon_adblocker_prefs"

    @JvmStatic
    fun isModuleActiveHooked(): Boolean = false

    @JvmStatic
    fun getHookedApiVersion(): Int = 0

    @JvmStatic
    fun getHookedContainerName(): String = ""

    override fun isSelfHookActive(): Boolean = false

    override fun getActiveXposedApiVersion(): Int = 0

    override fun isInsideLSPosedContainer(context: Context?): Boolean = false

    override fun getContainerType(context: Context?): LSPosedContainerType = LSPosedContainerType.NONE

    override fun getConfiguredScopePackages(context: Context): List<String> = emptyList()

    override fun openAdBlockerSharedPrefs(context: Context): Pair<SharedPreferences, Boolean> {
        return context.getSharedPreferences(AD_BLOCKER_PREFS_NAME, Context.MODE_PRIVATE) to false
    }

    fun detectContainerRuntime(context: Context?): LSPosedRuntimeDetection {
        return LSPosedRuntimeDetection(
            isInContainer = false,
            containerType = LSPosedContainerType.NONE,
            isSelfHookActive = false,
            isXposedClassLoaderInjected = false,
            isNativeBridgeDetected = false,
            isFrameworkFilesPresent = false,
            apiVersion = 0,
            isWorldReadablePrefsSupported = false,
            details = LSPosedContainerType.NONE.label
        )
    }
}
