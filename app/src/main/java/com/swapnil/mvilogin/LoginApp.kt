package com.swapnil.mvilogin

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * @HiltAndroidApp triggers Dagger code generation: it creates the root
 * SingletonComponent (the dependency graph) and attaches it to the Application.
 */
@HiltAndroidApp
class LoginApp : Application()
