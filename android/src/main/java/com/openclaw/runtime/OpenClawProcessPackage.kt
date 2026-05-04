package com.openclaw.runtime

import com.facebook.react.BaseReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.module.model.ReactModuleInfo
import com.facebook.react.module.model.ReactModuleInfoProvider
import com.facebook.react.uimanager.ViewManager

class OpenClawProcessPackage : BaseReactPackage() {

    override fun getModule(name: String, reactContext: ReactApplicationContext): NativeModule? =
        when (name) {
            OpenClawProcessModule.NAME -> OpenClawProcessModule(reactContext)
            else -> null
        }

    override fun getReactModuleInfoProvider(): ReactModuleInfoProvider =
        ReactModuleInfoProvider {
            mapOf(
                OpenClawProcessModule.NAME to ReactModuleInfo(
                    OpenClawProcessModule.NAME,
                    OpenClawProcessModule.NAME,
                    false,  // canOverrideExistingModule
                    false,  // needsEagerInit
                    true,   // isCxxModule
                    false,  // isTurboModule
                )
            )
        }

    override fun createViewManagers(
        reactContext: ReactApplicationContext
    ): List<ViewManager<in Nothing, in Nothing>> = emptyList()
}
