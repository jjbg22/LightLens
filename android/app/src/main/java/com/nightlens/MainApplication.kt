package com.nightlens

import com.nightlens.IATModelPackage;
import android.app.Application
import com.facebook.react.PackageList
import com.facebook.react.ReactApplication
import com.facebook.react.ReactNativeHost
import com.facebook.react.ReactPackage
import com.facebook.react.defaults.DefaultNewArchitectureEntryPoint.load
import com.facebook.soloader.SoLoader


class MainApplication : Application(), ReactApplication {

  override fun getReactNativeHost(): ReactNativeHost = object : ReactNativeHost(this) {
    override fun getUseDeveloperSupport(): Boolean = BuildConfig.DEBUG

    override fun getPackages(): List<ReactPackage> =
      PackageList(this).packages.apply {
        add(com.nightlens.IATModelPackage())
      }

    override fun getJSMainModuleName(): String = "index"
  }

  override fun onCreate() {
    super.onCreate()
    SoLoader.init(this, /* native exopackage */ false)
  }
}
